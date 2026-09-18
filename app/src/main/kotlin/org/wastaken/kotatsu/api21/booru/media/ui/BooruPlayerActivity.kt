package org.wastaken.kotatsu.api21.booru.media.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.booru.media.AspectRatioMode
import org.wastaken.kotatsu.api21.booru.media.BooruMediaItem
import org.wastaken.kotatsu.api21.booru.media.BooruMediaQueue
import org.wastaken.kotatsu.api21.booru.media.BooruMediaService
import org.wastaken.kotatsu.api21.booru.media.BooruMediaType
import org.wastaken.kotatsu.api21.booru.media.BooruVideoEngine
import org.wastaken.kotatsu.api21.core.model.MangaSource
import org.wastaken.kotatsu.api21.core.model.UnknownMangaSource
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.ui.BaseFullscreenActivity
import org.wastaken.kotatsu.api21.databinding.ActivityBooruPlayerBinding
import org.wastaken.kotatsu.api21.reader.ui.media.videoMimeType
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max


/**
 * Dedicated full-screen player for all booru GIF/video playback (Task 2).
 * The media engine lives in [BooruMediaService]; this activity only binds,
 * submits its TextureView surface and mirrors state into the controls.
 *
 * Reader never involved: booru posts route here directly.
 */
@AndroidEntryPoint
class BooruPlayerActivity :
	BaseFullscreenActivity<ActivityBooruPlayerBinding>(),
	BooruMediaService.Listener,
	BooruMediaQueue.Listener,
	TextureView.SurfaceTextureListener {

	@Inject
	lateinit var settings: AppSettings

	private var service: BooruMediaService? = null
	private var serviceBound = false
	private var pendingItem: BooruMediaItem? = null
	private var surface: Surface? = null
	private var surfaceTex: SurfaceTexture? = null
	private var surfaceW = 0
	private var surfaceH = 0
	private var controlsVisible = true
	private var locked = false
	private var isSeeking = false
	private var scaleFactor = 1f
	private var aspectMode = AspectRatioMode.FIT
	private var videoAspect = 0f
	private var gifLoop = true

	/** Session-level loop toggle for the current video; seeded from the persisted pref. */
	private var videoLoopSession = false
	private var currentSpeed = 1f
	private val handler = Handler(Looper.getMainLooper())
	private lateinit var gestureDetector: GestureDetector
	private lateinit var scaleDetector: ScaleGestureDetector
	private lateinit var audioManager: AudioManager

	private val hideControlsRunnable = Runnable {
		if (!locked) setControlsVisible(false)
	}

	private val positionPollRunnable = object : Runnable {
		override fun run() {
			refreshPositionUi()
			handler.postDelayed(this, POLL_MS)
		}
	}

	private val connection = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
			service = (binder as BooruMediaService.LocalBinder).service
			serviceBound = true
			service?.let { service ->
				service.addListener(this@BooruPlayerActivity)
				service.queue.addListener(this@BooruPlayerActivity)
				service.onVideoSizeChanged = { w, h ->
					videoAspect = if (h > 0) w.toFloat() / h else 0f
					applyAspectMode()
				}
				// engine-neutral: system MEDIA_INFO_* and libVLC Buffering land here alike
				service.onBufferingChange = { buffering ->
					viewBinding.bufferingProgress.visibility = if (buffering) View.VISIBLE else View.GONE
				}
				// libVLC TimeChanged events drive the seekbar (system engine is polled)
				service.onPlaybackProgress = { position, duration ->
					if (!isSeeking && duration > 0) {
						viewBinding.seekBar.max = duration
						viewBinding.seekBar.progress = position
						viewBinding.textDuration.text = formatTime(duration)
						viewBinding.textPosition.text = formatTime(position)
					}
				}
				// the texture may have become available before the bind landed;
				// re-push the saved surface so it is never lost on connect
				surface?.let { service.bindSurface(it, surfaceW, surfaceH) }
				surfaceTex?.let { service.bindSurfaceTexture(it, surfaceW, surfaceH) }
				onQueueChanged()
				onPlaybackStateChanged(service.playbackState, service.currentItem)
				val item = pendingItem
				if (item != null) {
					pendingItem = null
					startPlayback(item)
				} else {
					service.currentItem?.let { syncWithCurrentItem(it) }
				}
			}
		}

		override fun onServiceDisconnected(name: ComponentName?) {
			serviceBound = false
			service = null
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityBooruPlayerBinding.inflate(layoutInflater))
		audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
		aspectMode = settings.mediaAspectRatio
		videoLoopSession = settings.isMediaVideoLoop
		currentSpeed = settings.mediaDefaultSpeed
		pendingItem = itemFromIntent(intent)
		gifLoop = settings.isMediaGifLoop
		setupControls()
		setAspectButtonLabel(aspectMode)
		setupGestures()
		viewBinding.videoTexture.surfaceTextureListener = this
		BooruMediaService.start(this)
		bindService(
			Intent(this, BooruMediaService::class.java),
			connection,
			Context.BIND_AUTO_CREATE,
		)
		setControlsVisible(true)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		// launchMode is singleTask: every later tap on a media post lands here,
		// not in onCreate — parse and play it, otherwise it is silently dropped
		setIntent(intent)
		viewBinding.gifView.release()
		val item = itemFromIntent(intent) ?: return
		if (serviceBound) {
			startPlayback(item)
		} else {
			pendingItem = item
		}
	}



	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.controlsOverlay.updatePadding(
			left = systemBars.left,
			top = systemBars.top,
			right = systemBars.right,
			bottom = systemBars.bottom,
		)
		return insets
	}

	override fun onDestroy() {
		service?.removeListener(this)
		service?.queue?.removeListener(this)
		service?.bindSurface(null)
		service?.bindSurfaceTexture(null)
		if (serviceBound) unbindService(connection)
		surface?.release()
		surface = null
		surfaceTex = null
		handler.removeCallbacksAndMessages(null)
		super.onDestroy()
	}

	// region surfaces

	override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
		val s = Surface(surfaceTexture)
		surface = s
		surfaceTex = surfaceTexture
		surfaceW = width
		surfaceH = height
		service?.bindSurface(s, width, height)
		service?.bindSurfaceTexture(surfaceTexture, width, height)
	}

	override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
		surfaceW = width
		surfaceH = height
		service?.bindSurfaceTexture(surfaceTexture, width, height)
	}

	override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
		service?.bindSurface(null)
		service?.bindSurfaceTexture(null)
		surface?.release()
		surface = null
		surfaceTex = null
		return true
	}

	override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

	// endregion

	// region service callbacks

	override fun onQueueChanged() {
		runOnUiThread {
			val item = service?.currentItem ?: return@runOnUiThread
			if (item.url == lastPreparedUrl) return@runOnUiThread
			lastPreparedUrl = item.url
			prepareUiFor(item)
			if (item.mediaType == BooruMediaType.GIF) {
				loadGif(item)
			}
			// VIDEO: the service side already opened it (playIndex/auto-advance)
		}
	}

	override fun onPlaybackStateChanged(state: BooruMediaService.PlaybackState, item: BooruMediaItem?) {
		runOnUiThread {
			when (state) {
				BooruMediaService.PlaybackState.PREPARING -> {
					viewBinding.bufferingProgress.visibility = View.VISIBLE
				}
				BooruMediaService.PlaybackState.PLAYING, BooruMediaService.PlaybackState.PAUSED -> {
					viewBinding.bufferingProgress.visibility = View.GONE
					startPositionPolling()
				}
				BooruMediaService.PlaybackState.IDLE -> {
					viewBinding.bufferingProgress.visibility = View.GONE
				}
			}
			updatePlayButton()
		}
	}

	override fun onPlaybackError(item: BooruMediaItem?, what: Int, extra: Int) {
		runOnUiThread {
			viewBinding.bufferingProgress.visibility = View.GONE
			offerExternalPlayback(item)
		}
	}

	// endregion

	// region playback glue

	private var lastPreparedUrl: String? = null

	private fun startPlayback(item: BooruMediaItem) {
		// UI prep must run even when the same item re-arrives (e.g. re-entry
		// through onNewIntent or expand-from-floating), otherwise the screen
		// keeps the previous item's title/views
		prepareUiFor(item)
		if (item.url == lastPreparedUrl) return
		lastPreparedUrl = item.url
		when (item.mediaType) {
			BooruMediaType.VIDEO -> service?.playNow(item)
			BooruMediaType.GIF -> {
				service?.playNow(item) // queue cursor + service state; rendering below
				loadGif(item)
			}
		}
	}

	private fun syncWithCurrentItem(item: BooruMediaItem) {
		prepareUiFor(item)
	}

	private fun prepareUiFor(item: BooruMediaItem) {
		viewBinding.mediaTitle.text = item.title
		val isVideo = item.mediaType == BooruMediaType.VIDEO
		viewBinding.videoTexture.visibility = if (isVideo) View.VISIBLE else View.GONE
		viewBinding.gifView.visibility = if (isVideo) View.GONE else View.VISIBLE
		if (isVideo) viewBinding.gifView.release()
		viewBinding.seekRow.visibility = if (isVideo) View.VISIBLE else View.GONE
		viewBinding.videoControlsRow.visibility = if (isVideo) View.VISIBLE else View.GONE
		viewBinding.gifControlsRow.visibility = if (isVideo) View.GONE else View.VISIBLE
		viewBinding.buttonSpeed.visibility =
			if (isVideo && isSpeedControlAvailable) View.VISIBLE else View.GONE
		viewBinding.buttonSpeed.text = speedLabel(currentSpeed)
		viewBinding.mediaSurfaceFrame.fixedAspectRatio = 0f
		viewBinding.mediaSurfaceFrame.scaleX = 1f
		viewBinding.mediaSurfaceFrame.scaleY = 1f
		scaleFactor = 1f
	}

	private fun loadGif(item: BooruMediaItem) {
		viewBinding.bufferingProgress.visibility = View.VISIBLE
		lifecycleScope.launch(Dispatchers.IO) {
			val result = runCatching { service?.loadGifBytes(item) }
			withContext(Dispatchers.Main) {
				viewBinding.bufferingProgress.visibility = View.GONE
				val bytes = result.getOrNull()
				if (bytes == null) {
					offerExternalPlayback(item)
					return@withContext
				}
				val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
				if (movie == null) {
					offerExternalPlayback(item)
					return@withContext
				}
				viewBinding.gifView.setMovie(movie, gifLoop)
				viewBinding.gifView.play()
				updateGifPlayButton()
			}
		}
	}

	private fun offerExternalPlayback(item: BooruMediaItem?) {
		item ?: return
		AlertDialog.Builder(this)
			.setTitle(item.title)
			.setMessage(R.string.media_play_external_summary)
			.setPositiveButton(R.string.open_external) { _, _ ->
				if (openExternalPlayer(item)) finish()
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
			.show()
	}

	/**
	 * On-demand external playback (top bar button): mirrors the old in-reader
	 * overlay action dialog — VLC first, then any external handler.
	 */
	private fun startExternalPlay() {
		val item = service?.currentItem ?: return
		AlertDialog.Builder(this)
			.setTitle(item.title)
			.setItems(
				arrayOf(
					getString(R.string.play_in_vlc),
					getString(R.string.open_external),
				),
			) { _, which ->
				when (which) {
					0 -> if (!openExternalPlayer(item, VLC_PACKAGE) && !openExternalPlayer(item)) {
						Toast.makeText(this, R.string.media_no_external_handler, Toast.LENGTH_SHORT).show()
					}
					1 -> if (!openExternalPlayer(item)) {
						Toast.makeText(this, R.string.media_no_external_handler, Toast.LENGTH_SHORT).show()
					}
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	// same intent shape the old reader overlay handed to external players,
	// incl. the "title" extra so VLC shows the file name instead of the URL
	private fun openExternalPlayer(item: BooruMediaItem, packageName: String? = null): Boolean {
		val uri = android.net.Uri.parse(item.url)
		// extension-less URLs get no signal from videoMimeType(); the resolved
		// media type (incl. the tag/title fallbacks) decides the mime
		val mime = if (item.mediaType == BooruMediaType.GIF) "image/gif" else item.url.videoMimeType()
		val intent = Intent(Intent.ACTION_VIEW)
			.setDataAndType(uri, mime)
			.putExtra("title", item.title)
		if (packageName != null) intent.setPackage(packageName)
		// resolveActivity is unfiltered here: the manifest holds QUERY_ALL_PACKAGES
		return if (intent.resolveActivity(packageManager) != null) {
			runCatching { startActivity(intent) }.isSuccess
		} else {
			false
		}
	}

	// endregion

	// region controls

	private fun setupControls() {
		viewBinding.buttonBack.setOnClickListener { finish() }
		viewBinding.buttonPlayPause.setOnClickListener { service?.togglePlayPause(); updatePlayButton() }
		viewBinding.buttonSkipBack.setOnClickListener {
			service?.seekBy(-settings.mediaSkipIntervalSec * 1000)
		}
		viewBinding.buttonSkipForward.setOnClickListener {
			service?.seekBy(settings.mediaSkipIntervalSec * 1000)
		}
		viewBinding.buttonPrevious.setOnClickListener { service?.playPrevious() }
		viewBinding.buttonNext.setOnClickListener { service?.playNext() }
		viewBinding.buttonQueue.setOnClickListener { showQueueDialog() }
		viewBinding.buttonFloating.setOnClickListener { openFloating() }
		viewBinding.buttonExternal.setOnClickListener { startExternalPlay() }
		viewBinding.buttonLock.setOnClickListener { toggleLock() }
		viewBinding.buttonSpeed.setOnClickListener { showSpeedDialog() }
		viewBinding.buttonLoop.setOnClickListener { toggleLoop() }
		viewBinding.buttonAspect.setOnClickListener { cycleAspectMode() }
		viewBinding.buttonGifPlayPause.setOnClickListener {
			val view = viewBinding.gifView
			if (view.isPlaying()) view.stop() else view.play()
			updateGifPlayButton()
		}
		viewBinding.buttonFrameBack.setOnClickListener { viewBinding.gifView.stepBackFrame() }
		viewBinding.buttonFrameForward.setOnClickListener { viewBinding.gifView.stepForwardFrame() }
		viewBinding.buttonSaveFrame.setOnClickListener { saveCurrentFrame() }
		viewBinding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
				if (fromUser) viewBinding.textPosition.text = formatTime(progress)
			}

			override fun onStartTrackingTouch(seekBar: SeekBar?) {
				isSeeking = true
				handler.removeCallbacks(hideControlsRunnable)
			}

			override fun onStopTrackingTouch(seekBar: SeekBar?) {
				service?.seekTo(seekBar?.progress ?: 0)
				isSeeking = false
				scheduleHideControls()
			}
		})
	}

	private fun updatePlayButton() {
		val playing = service?.isVideoPlaying() == true
		viewBinding.buttonPlayPause.setImageResource(
			if (playing) R.drawable.ic_action_pause else R.drawable.ic_play,
		)
		viewBinding.buttonPlayPause.contentDescription = getString(R.string.play)
		if (playing) startPositionPolling()
	}

	private fun updateGifPlayButton() {
		viewBinding.buttonGifPlayPause.setImageResource(
			if (viewBinding.gifView.isPlaying()) R.drawable.ic_action_pause else R.drawable.ic_play,
		)
	}

	private fun toggleLoop() {
		val service = service ?: return
		val item = service.currentItem
		if (item?.mediaType == BooruMediaType.GIF) {
			gifLoop = !gifLoop
			viewBinding.gifView.setLoop(gifLoop)
		} else {
			videoLoopSession = !videoLoopSession
			service.setVideoLooping(videoLoopSession)
		}
		refreshLoopButton()
	}

	private fun refreshLoopButton() {
		val item = service?.currentItem
		val on = if (item?.mediaType == BooruMediaType.GIF) gifLoop else videoLoopSession
		viewBinding.buttonLoop.alpha = if (on) 1f else 0.5f
	}

	/** System MediaPlayer needs API 23+ for rate control; libVLC supports it everywhere. */
	private val isSpeedControlAvailable: Boolean
		get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ||
			settings.booruVideoEngine == BooruVideoEngine.LIBVLC

	private fun showSpeedDialog() {
		if (!isSpeedControlAvailable) return
		val speeds = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
		val labels = speeds.map { speedLabel(it) }.toTypedArray()
		AlertDialog.Builder(this)
			.setTitle(R.string.media_default_speed)
			.setItems(labels) { _, which ->
				currentSpeed = speeds[which]
				service?.setSpeed(speeds[which])
				viewBinding.buttonSpeed.text = labels[which]
			}
			.show()
	}

	private fun speedLabel(speed: Float): String {
		return if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"
	}

	private fun cycleAspectMode() {
		aspectMode = when (aspectMode) {
			AspectRatioMode.FIT -> AspectRatioMode.FILL
			AspectRatioMode.FILL -> AspectRatioMode.CROP
			AspectRatioMode.CROP -> AspectRatioMode.RATIO_16_9
			AspectRatioMode.RATIO_16_9 -> AspectRatioMode.RATIO_4_3
			AspectRatioMode.RATIO_4_3 -> AspectRatioMode.FIT
		}
		applyAspectMode()
		setAspectButtonLabel(aspectMode)
	}

	private fun setAspectButtonLabel(mode: AspectRatioMode) {
		viewBinding.buttonAspect.setText(
			when (mode) {
				AspectRatioMode.FIT -> R.string.media_ratio_fit
				AspectRatioMode.FILL -> R.string.media_ratio_fill
				AspectRatioMode.CROP -> R.string.media_ratio_crop
				AspectRatioMode.RATIO_16_9 -> R.string.media_ratio_16_9
				AspectRatioMode.RATIO_4_3 -> R.string.media_ratio_4_3
			},
		)
	}

	private fun applyAspectMode() {
		val texture = viewBinding.videoTexture
		val frame = viewBinding.mediaSurfaceFrame
		frame.fixedAspectRatio = when (aspectMode) {
			AspectRatioMode.RATIO_16_9 -> 16f / 9f
			AspectRatioMode.RATIO_4_3 -> 4f / 3f
			else -> 0f
		}
		val viewW = texture.width
		val viewH = texture.height
		if (viewW <= 0 || viewH <= 0) {
			texture.post { applyAspectMode() }
			return
		}
		val contentAspect = videoAspect.takeIf { it > 0f } ?: (viewW.toFloat() / viewH)
		// TextureView matrices: scale <1 compresses (letterbox), >1 zooms (crop).
		// Derived per-axis candidates; fit keeps the compressing axis, crop the zooming one.
		val candidateX = viewH * contentAspect / viewW
		val candidateY = viewW / (contentAspect * viewH)
		when (aspectMode) {
			AspectRatioMode.FILL -> texture.setTransform(null)
			AspectRatioMode.CROP -> {
				val matrix = Matrix()
				if (candidateX > 1f) matrix.setScale(candidateX, 1f, viewW / 2f, viewH / 2f)
				else matrix.setScale(1f, candidateY, viewW / 2f, viewH / 2f)
				texture.setTransform(matrix)
			}
			else -> { // FIT and the two fixed ratios
				val matrix = Matrix()
				if (candidateX < 1f) matrix.setScale(candidateX, 1f, viewW / 2f, viewH / 2f)
				else matrix.setScale(1f, candidateY, viewW / 2f, viewH / 2f)
				texture.setTransform(matrix)
			}
		}
	}

	private fun toggleLock() {
		locked = !locked
		viewBinding.buttonLock.setImageResource(
			if (locked) R.drawable.ic_screen_rotation_lock else R.drawable.ic_lock,
		)
		viewBinding.buttonLock.contentDescription = getString(
			if (locked) R.string.media_unlock else R.string.media_lock,
		)
		setControlsEnabled(!locked)
		viewBinding.gestureLayer.isEnabled = !locked
		if (locked) {
			setControlsVisible(true)
			handler.removeCallbacks(hideControlsRunnable)
		} else {
			scheduleHideControls()
		}
	}

	private fun setControlsEnabled(enabled: Boolean) {
		val containers = listOf(viewBinding.topBar, viewBinding.bottomBar)
		for (container in containers) {
			for (i in 0 until container.childCount) {
				setEnabledRecursive(container.getChildAt(i), enabled)
			}
		}
		viewBinding.buttonLock.isEnabled = true
	}

	private fun setEnabledRecursive(view: View, enabled: Boolean) {
		if (view === viewBinding.buttonLock) return
		view.isEnabled = enabled
		if (view is android.view.ViewGroup) {
			for (i in 0 until view.childCount) setEnabledRecursive(view.getChildAt(i), enabled)
		}
	}

	private fun setControlsVisible(visible: Boolean) {
		controlsVisible = visible
		viewBinding.controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
		systemUiController.setSystemUiVisible(visible)
		if (visible) scheduleHideControls()
	}

	private fun scheduleHideControls() {
		handler.removeCallbacks(hideControlsRunnable)
		if (!locked) handler.postDelayed(hideControlsRunnable, CONTROLS_AUTOHIDE_MS)
	}

	private fun startPositionPolling() {
		handler.removeCallbacks(positionPollRunnable)
		handler.post(positionPollRunnable)
	}

	private fun refreshPositionUi() {
		if (isSeeking) return
		val service = service ?: return
		// libVLC TimeChanged events already feed the seekbar; the poll is the
		// system engine's update path
		if (service.activeVideoEngine == BooruVideoEngine.LIBVLC) return
		val duration = service.videoDuration()
		val position = service.videoPosition()
		if (duration > 0) {
			viewBinding.seekBar.max = duration
			viewBinding.seekBar.progress = position
			viewBinding.textDuration.text = formatTime(duration)
			viewBinding.textPosition.text = formatTime(position)
		}
	}

	private fun formatTime(ms: Int): String {
		val totalSeconds = ms / 1000
		val seconds = totalSeconds % 60
		val minutes = (totalSeconds / 60) % 60
		val hours = totalSeconds / 3600
		return if (hours > 0) {
			String.format("%d:%02d:%02d", hours, minutes, seconds)
		} else {
			String.format("%d:%02d", minutes, seconds)
		}
	}

	private fun showQueueDialog() {
		BooruQueueSheetFragment.show(this)
	}

	private fun openFloating() {
		val service = service ?: return
		if (!service.openFloating()) {
			AlertDialog.Builder(this)
				.setMessage(R.string.media_floating_permission)
				.setPositiveButton(android.R.string.ok) { _, _ ->
					runCatching { startActivity(service.overlaySettingsIntent()) }
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
			return
		}
		finish()
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		applyAspectMode()
		// the TextureView resized without a new SurfaceTexture: re-push the
		// target size so libVLC's window follows the rotation
		viewBinding.videoTexture.post {
			surfaceTex?.let { service?.bindSurfaceTexture(it, viewBinding.videoTexture.width, viewBinding.videoTexture.height) }
		}
	}

	// endregion

	// region gestures

	private fun setupGestures() {
		gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
			override fun onDown(e: MotionEvent): Boolean = true

			override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
				setControlsVisible(!controlsVisible)
				return true
			}

			override fun onDoubleTap(e: MotionEvent): Boolean {
				if (scaleFactor > 1f) {
					resetZoom()
					return true
				}
				val third = viewBinding.gestureLayer.width / 4f
				val skipMs = settings.mediaSkipIntervalSec * 1000
				when {
					e.x < third -> service?.seekBy(-skipMs)
					e.x > viewBinding.gestureLayer.width - third -> service?.seekBy(skipMs)
					else -> service?.togglePlayPause()
				}
				updatePlayButton()
				return true
			}
		})
		scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
			override fun onScale(detector: ScaleGestureDetector): Boolean {
				if (!settings.isMediaPinchZoom || locked) return false
				scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1f, 3f)
				viewBinding.mediaSurfaceFrame.scaleX = scaleFactor
				viewBinding.mediaSurfaceFrame.scaleY = scaleFactor
				return true
			}
		})
		viewBinding.gestureLayer.setOnTouchListener { _, event ->
			if (locked) return@setOnTouchListener false
			var handled = scaleDetector.onTouchEvent(event)
			handled = gestureDetector.onTouchEvent(event) || handled
			handled = handleSwipeGesture(event) || handled
			handled
		}
	}

	private fun resetZoom() {
		scaleFactor = 1f
		viewBinding.mediaSurfaceFrame.scaleX = 1f
		viewBinding.mediaSurfaceFrame.scaleY = 1f
	}

	private var swipeStartY = 0f
	private var swipeStartX = 0f
	private var swipeMode = SWIPE_NONE
	private var startBrightness = 0f
	private var startVolume = 0

	private fun handleSwipeGesture(event: MotionEvent): Boolean {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				swipeStartY = event.y
				swipeStartX = event.x
				swipeMode = SWIPE_NONE
			}
			MotionEvent.ACTION_MOVE -> {
				if (scaleFactor > 1f || scaleDetector.isInProgress) return false
				val dy = swipeStartY - event.y
				val dx = event.x - swipeStartX
				if (swipeMode == SWIPE_NONE) {
					if (abs(dy) > SWIPE_SLOP && abs(dy) > abs(dx) * 2) {
						val leftHalf = swipeStartX < viewBinding.gestureLayer.width / 2f
						swipeMode = when {
							leftHalf && settings.isMediaBrightnessGesture -> SWIPE_BRIGHTNESS
							!leftHalf && settings.isMediaVolumeGesture -> SWIPE_VOLUME
							else -> SWIPE_IGNORED
						}
						if (swipeMode == SWIPE_BRIGHTNESS) {
							startBrightness = window.attributes.screenBrightness
								.takeIf { it >= 0f } ?: 0.5f
						} else if (swipeMode == SWIPE_VOLUME) {
							startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
						}
					} else {
						return false
					}
				}
				when (swipeMode) {
					SWIPE_BRIGHTNESS -> {
						val delta = dy / viewBinding.gestureLayer.height
						val brightness = (startBrightness + delta).coerceIn(0.01f, 1f)
						val attrs = window.attributes
						attrs.screenBrightness = brightness
						window.attributes = attrs
						return true
					}
					SWIPE_VOLUME -> {
						val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
						val delta = dy / viewBinding.gestureLayer.height * maxVolume
						val volume = (startVolume + delta).toInt().coerceIn(0, maxVolume)
						audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
						return true
					}
					else -> return false
				}
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeMode = SWIPE_NONE
		}
		return false
	}

	// endregion

	// region gif frame save

	private fun saveCurrentFrame() {
		val bitmap = viewBinding.gifView.renderToBitmap() ?: return
		val title = service?.currentItem?.title ?: "frame"
		lifecycleScope.launch(Dispatchers.IO) {
			runCatching {
				MediaStore.Images.Media.insertImage(contentResolver, bitmap, title, null)
			}
			bitmap.recycle()
			withContext(Dispatchers.Main) {
				Toast.makeText(this@BooruPlayerActivity, R.string.media_frame_saved, Toast.LENGTH_SHORT).show()
			}
		}
	}

	// endregion

	// region intent mapping

	private fun itemFromIntent(intent: Intent): BooruMediaItem? {
		val url = intent.getStringExtra(EXTRA_URL) ?: return null
		val typeName = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: return null
		val type = runCatching { BooruMediaType.valueOf(typeName) }.getOrNull() ?: return null
		val sourceName = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
		val source = MangaSource(sourceName)
		if (source == UnknownMangaSource) return null
		return BooruMediaItem(
			id = BooruMediaItem.uidOf(url),
			url = url,
			source = source,
			title = intent.getStringExtra(EXTRA_TITLE) ?: url,
			thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL),
			mediaType = type,
		)
	}

	// endregion

	companion object {

		private const val EXTRA_URL = "url"
		private const val EXTRA_SOURCE = "source"
		private const val EXTRA_TITLE = "title"
		private const val EXTRA_THUMBNAIL = "thumbnail"
		private const val EXTRA_MEDIA_TYPE = "media_type"
		private const val CONTROLS_AUTOHIDE_MS = 3000L
		private const val POLL_MS = 500L
		private const val SWIPE_NONE = 0
		private const val SWIPE_BRIGHTNESS = 1
		private const val SWIPE_VOLUME = 2
		private const val SWIPE_IGNORED = 3
		private const val SWIPE_SLOP = 40
		private const val VLC_PACKAGE = "org.videolan.vlc"

		fun newIntent(context: Context, item: BooruMediaItem): Intent {
			return Intent(context, BooruPlayerActivity::class.java)
				.putExtra(EXTRA_URL, item.url)
				.putExtra(EXTRA_SOURCE, item.source.name)
				.putExtra(EXTRA_TITLE, item.title)
				.putExtra(EXTRA_THUMBNAIL, item.thumbnailUrl)
				.putExtra(EXTRA_MEDIA_TYPE, item.mediaType.name)
		}

		/** Task 2 entry — redirect target for every booru video post. */
		fun start(context: Context, item: BooruMediaItem) {
			context.startActivity(newIntent(context, item))
		}

		/** Task 2 entry — gif expansion target (same activity; type rides in the item). */
		fun startGif(context: Context, item: BooruMediaItem) {
			start(context, item)
		}
	}
}
