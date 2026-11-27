package org.wastaken.kotatsu.api21.reader.ui.media

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.booru.media.BooruMediaItem
import org.wastaken.kotatsu.api21.booru.media.BooruMediaService
import org.wastaken.kotatsu.api21.booru.media.BooruMediaType
import org.wastaken.kotatsu.api21.booru.media.ui.BooruPlayerActivity
import org.wastaken.kotatsu.api21.core.util.ext.getDisplayMessage
import org.wastaken.kotatsu.api21.core.util.ext.isNetworkUri
import org.wastaken.kotatsu.api21.databinding.LayoutBooruVideoOverlayBinding
import org.wastaken.kotatsu.api21.reader.ui.pager.ReaderPage
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

/**
 * In-reader video page for booru sources (TSD-BOORU-MMBD-001 M2). The overlay
 * owns NO player: playback runs on the single shared engine inside
 * [BooruMediaService], reached through [ReaderSharedMediaController], so the
 * reader never holds more than one hardware codec instance on API 21 no
 * matter how many video pages a gallery has.
 *
 * Behavior contract:
 *  - Placeholder-first: nothing loads until "Load video" (plus the optional
 *    quality pick), UNLESS the Media-player setting "Auto-play videos in
 *    reader" is on and the page gains focus (paged: current page; webtoon:
 *    center-most page - driven by the fragments, see controller mode).
 *  - Reader videos launch MUTED (TSD §3.4); the HUD mute tap unmutes the
 *    session. Audio focus is only taken while unmuted.
 *  - Scroll-away / background pauses and detaches (non-goal §1.3: no
 *    background audio); scrolling back resumes at the remembered position.
 *  - [Fullscreen] promotes the ad-hoc item into the service queue and opens
 *    [BooruPlayerActivity] without re-sourcing (position rides along).
 *  - Double-tap edges seek ±10s; tap toggles the HUD.
 *
 * Streaming runs through the service's engine pipeline (ExoPlayer SimpleCache
 * / libVLC / system MediaPlayer + VideoStreamProxy per the media-player
 * engine setting) with full source headers. The only way a full video file
 * lands on the device is still "Download video" in the Actions dialog.
 */
@Suppress("DEPRECATION")
class VideoPageOverlay(
	private val binding: LayoutBooruVideoOverlayBinding,
	private val lifecycleOwner: LifecycleOwner,
) : DefaultLifecycleObserver, TextureView.SurfaceTextureListener, ReaderSharedMediaController.Host {

	var isHandling = false
		private set

	private var loadJob: Job? = null
	private var boundPage: ReaderPage? = null
	private var currentStreamUrl: String? = null

	private var playbackStarted = false
	private var playbackErrored = false
	private var wasPlayingBeforePause = false

	private var isLooping = false
	private var speedIndex = SPEED_DEFAULT_INDEX

	private val controlsHandler = Handler(Looper.getMainLooper())

	private val progressRunnable = object : Runnable {
		override fun run() {
			if (!controller.isAttachedHost(this@VideoPageOverlay)) return
			if (!binding.seekVideo.isPressed) {
				val position = controller.positionMs()
				val duration = controller.durationMs()
				if (duration > 0 && binding.seekVideo.max != duration) {
					binding.seekVideo.max = duration
					binding.textDuration.text = formatTime(duration)
				}
				binding.seekVideo.progress = position
				binding.textPosition.text = formatTime(position)
			}
			controlsHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
		}
	}

	private val autoHideRunnable = Runnable {
		if (isPlayingSafe()) {
			binding.controlsPanel.isGone = true
		}
	}

	private val gestureDetector = GestureDetector(
		binding.root.context,
		object : GestureDetector.SimpleOnGestureListener() {

			override fun onDown(e: MotionEvent): Boolean = true

			override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
				toggleControlsVisibility()
				return true
			}

			override fun onDoubleTap(e: MotionEvent): Boolean {
				val width = binding.videoContainer.width
				when {
					e.x < width * DOUBLE_TAP_EDGE -> controller.seekBy(this@VideoPageOverlay, -SKIP_MS)
					e.x > width * (1f - DOUBLE_TAP_EDGE) -> controller.seekBy(this@VideoPageOverlay, SKIP_MS)
				}
				return true
			}
		},
	)

	private val appContext: Context
		get() = binding.root.context.applicationContext

	private val entryPoint: ReaderMediaEntryPoint by lazy(LazyThreadSafetyMode.NONE) {
		EntryPointAccessors.fromApplication(appContext, ReaderMediaEntryPoint::class.java)
	}

	private val controller: ReaderSharedMediaController by lazy(LazyThreadSafetyMode.NONE) {
		ReaderSharedMediaController.get(appContext)
	}

	init {
		lifecycleOwner.lifecycle.addObserver(this)
	}

	/** @return true if this overlay handles the page and the normal page load must be suppressed. */
	fun onBind(page: ReaderPage): Boolean {
		// source check comes first, always (see BooruMedia.kt); the URL must
		// carry the video signal on this route
		if (!page.url.looksLikeVideo()) {
			reset()
			isHandling = false
			return false
		}
		return bindPage(page)
	}

	/**
	 * Late-classification entry (TSD-BOORU-MMBD-001 M1): the MediaSniffer probe
	 * proved video bytes behind an extension-less URL, so the URL check is
	 * skipped. Everything else (source gate, placeholder-first) is unchanged.
	 */
	fun onBindForced(page: ReaderPage): Boolean = bindPage(page)

	private fun bindPage(page: ReaderPage): Boolean {
		reset()
		isHandling = page.source.isMediaPlayerEnabled(entryPoint.settings())
		if (!isHandling) {
			return false
		}
		boundPage = page
		isLooping = entryPoint.settings().isMediaVideoLoop
		binding.root.isVisible = true
		binding.videoSurface.surfaceTextureListener = this
		showButtonState()
		binding.buttonLoadVideo.setOnClickListener { showChoiceDialog(page) }
		// direct route to the external destinations without starting playback
		binding.buttonLoadVideo.setOnLongClickListener {
			showChoiceDialog(page, actionsOnly = true)
			true
		}
		initControlsOnce()
		return true
	}

	fun onRecycled() {
		reset()
	}

	private fun reset() {
		loadJob?.cancel()
		loadJob = null
		controller.detach(this)
		// the holder recycles bind() calls on the same views - never stack callbacks
		binding.videoSurface.surfaceTextureListener = null
		controlsHandler.removeCallbacks(progressRunnable)
		controlsHandler.removeCallbacks(autoHideRunnable)
		boundPage = null
		currentStreamUrl = null
		playbackStarted = false
		playbackErrored = false
		wasPlayingBeforePause = false
		speedIndex = SPEED_DEFAULT_INDEX
		binding.videoContainer.isGone = true
		binding.progressBuffering.isGone = true
		binding.root.isGone = true
	}

	// region shared-engine playback

	private fun buildItem(page: ReaderPage, streamUrl: String): BooruMediaItem = BooruMediaItem(
		id = BooruMediaItem.uidOf(streamUrl),
		url = streamUrl,
		source = page.source,
		title = streamUrl.urlFileName(),
		thumbnailUrl = page.preview,
		mediaType = BooruMediaType.VIDEO,
	)

	private fun startPlayback(streamUrl: String) {
		val page = boundPage ?: return
		currentStreamUrl = streamUrl
		playbackErrored = false
		playbackStarted = true
		binding.panelVideo.isGone = true
		binding.progressVideo.isGone = true
		binding.videoContainer.isVisible = true
		binding.progressBuffering.isVisible = true
		binding.seekVideo.progress = 0
		binding.textPosition.text = formatTime(0)
		showControls(hideLater = true)
		controller.attach(this, binding.videoSurface, buildItem(page, streamUrl))
	}

	/** Auto-play entry (paged on-resume / webtoon center): no dialogs, default variant. */
	fun startFromAutoPlay() {
		val page = boundPage ?: return
		if (!isHandling || playbackStarted || playbackErrored) return
		if (!entryPoint.settings().isMediaReaderVideoAutoplay) return
		startPlayback(page.url.videoStreamVariants().first().url)
	}

	/** Webtoon center-viewport entry (TSD §3.3): the center-most video page plays. */
	fun onCenterSelected() {
		if (!isHandling || controller.isAttachedHost(this)) return
		val page = boundPage ?: return
		when {
			playbackStarted && wasPlayingBeforePause -> {
				wasPlayingBeforePause = false
				currentStreamUrl?.let { url ->
					controller.attach(this, binding.videoSurface, buildItem(page, url))
				}
			}
			else -> startFromAutoPlay()
		}
	}

	/** Webtoon center-viewport exit: pause + detach, resume point remembered. */
	fun onCenterCleared() {
		controller.detach(this)
	}

	private fun isPlayingSafe(): Boolean =
		controller.isAttachedHost(this) && controller.isPlaying()

	// letterbox the texture inside the container (aspect-fit), like an image page
	private fun fitVideo(videoW: Int, videoH: Int) {
		val container = binding.videoContainer
		val cw = container.width
		val ch = container.height
		if (cw <= 0 || ch <= 0 || videoW <= 0 || videoH <= 0) {
			return
		}
		val scale = minOf(cw.toFloat() / videoW, ch.toFloat() / videoH)
		val lp = binding.videoSurface.layoutParams as FrameLayout.LayoutParams
		lp.width = (videoW * scale).toInt()
		lp.height = (videoH * scale).toInt()
		lp.gravity = android.view.Gravity.CENTER
		binding.videoSurface.layoutParams = lp
	}

	private fun onPlaybackError() {
		if (playbackErrored) {
			// one failure dialog per playback attempt, never two
			return
		}
		playbackErrored = true
		val url = currentStreamUrl
		controller.detach(this)
		controlsHandler.removeCallbacks(progressRunnable)
		binding.videoContainer.isGone = true
		binding.progressBuffering.isGone = true
		showButtonState()
		if (url != null) {
			// graceful fallback: still offer the external destinations for this stream
			showActionDialogFromUrl(url, titleRes = R.string.error_occurred)
		} else {
			showErrorSnackbar(Snackbar.LENGTH_SHORT)
		}
	}

	// endregion

	// region TextureView.SurfaceTextureListener

	override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
		controller.onSurfaceReady(this, surface, width, height)
	}

	override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
		controller.onSurfaceReady(this, surface, width, height)
	}

	override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
		// TSD §6 race rule: synchronously unbind the engine BEFORE the
		// SurfaceTexture is released, then let it go
		controller.onSurfaceDestroyed(this)
		return true
	}

	override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

	// endregion

	// region ReaderSharedMediaController.Host

	override fun onReaderPlaybackStateChanged(state: BooruMediaService.PlaybackState) {
		when (state) {
			BooruMediaService.PlaybackState.PLAYING -> {
				binding.progressBuffering.isGone = true
				updatePlayPauseIcon()
				controlsHandler.removeCallbacks(progressRunnable)
				controlsHandler.post(progressRunnable)
				showControls(hideLater = true)
			}

			BooruMediaService.PlaybackState.PAUSED -> {
				updatePlayPauseIcon()
				showControls(hideLater = false)
			}

			BooruMediaService.PlaybackState.PREPARING -> {
				binding.progressBuffering.isVisible = true
			}

			BooruMediaService.PlaybackState.IDLE -> {
				// ended (non-looping): rewind affordance, controls stay up
				controlsHandler.removeCallbacks(progressRunnable)
				binding.progressBuffering.isGone = true
				updatePlayPauseIcon()
				showControls(hideLater = false)
			}
		}
	}

	override fun onReaderBufferingChanged(buffering: Boolean) {
		binding.progressBuffering.isVisible = buffering
	}

	override fun onReaderVideoSizeChanged(width: Int, height: Int) {
		fitVideo(width, height)
	}

	override fun onReaderPlaybackError() {
		onPlaybackError()
	}

	override fun onReaderDetached(wasPlaying: Boolean) {
		wasPlayingBeforePause = wasPlaying
		controlsHandler.removeCallbacks(progressRunnable)
		updatePlayPauseIcon()
	}

	// endregion

	// region controls

	private var controlsInitialized = false

	private fun initControlsOnce() {
		if (controlsInitialized) {
			return
		}
		controlsInitialized = true
		binding.buttonPlayPause.setOnClickListener {
			controller.togglePlayPause(this)
			updatePlayPauseIcon()
			showControls(hideLater = true)
		}
		binding.buttonSkipBack.setOnClickListener { controller.seekBy(this, -SKIP_MS) }
		binding.buttonSkipForward.setOnClickListener { controller.seekBy(this, SKIP_MS) }
		binding.buttonLoop.setOnClickListener {
			isLooping = !isLooping
			controller.setLooping(this, isLooping)
			updateLoopButton()
		}
		updateLoopButton()
		binding.buttonMute.setOnClickListener {
			controller.setMuted(!controller.isMuted)
			updateMuteButton()
		}
		updateMuteButton()
		binding.buttonFullscreen.setOnClickListener { openFullscreen() }
		binding.buttonActions.setOnClickListener {
			currentStreamUrl?.let { url -> showActionDialogFromUrl(url, titleRes = R.string.load_video) }
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			updateSpeedButton()
			binding.buttonSpeed.setOnClickListener {
				speedIndex = (speedIndex + 1) % SPEED_STEPS.size
				controller.setSpeed(this, SPEED_STEPS[speedIndex])
				updateSpeedButton()
			}
		} else {
			// PlaybackParams requires API 23; hide the feature on older devices
			binding.buttonSpeed.isGone = true
		}
		binding.seekVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

			override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
				if (fromUser) {
					binding.textPosition.text = formatTime(progress)
				}
			}

			override fun onStartTrackingTouch(seekBar: SeekBar) {
				controlsHandler.removeCallbacks(progressRunnable)
			}

			override fun onStopTrackingTouch(seekBar: SeekBar) {
				controller.seekTo(this@VideoPageOverlay, seekBar.progress)
				controlsHandler.post(progressRunnable)
			}
		})
		binding.videoContainer.setOnTouchListener { _, event ->
			gestureDetector.onTouchEvent(event)
			true
		}
	}

	/**
	 * Fullscreen handoff (TSD §3.4): the ad-hoc item joins the service queue
	 * unmuted without re-sourcing; the player activity finds it already
	 * current and simply binds its surface - no cold start.
	 */
	private fun openFullscreen() {
		val page = boundPage ?: return
		val url = currentStreamUrl ?: return
		val item = if (controller.isAttachedHost(this)) {
			controller.promoteToQueue(this)
		} else {
			null
		} ?: buildItem(page, url)
		// ownership transfer: the queue/fullscreen player owns the video now;
		// this page goes back to its placeholder (a return does not re-capture it)
		controlsHandler.removeCallbacks(progressRunnable)
		controlsHandler.removeCallbacks(autoHideRunnable)
		binding.videoContainer.isGone = true
		binding.progressBuffering.isGone = true
		playbackStarted = false
		wasPlayingBeforePause = false
		showButtonState()
		BooruPlayerActivity.start(binding.root.context, item)
	}

	private fun toggleControlsVisibility() {
		if (binding.controlsPanel.isGone) {
			showControls(hideLater = true)
		} else {
			hideControls()
		}
	}

	private fun showControls(hideLater: Boolean) {
		binding.controlsPanel.isVisible = true
		controlsHandler.removeCallbacks(autoHideRunnable)
		if (hideLater && isPlayingSafe()) {
			controlsHandler.postDelayed(autoHideRunnable, CONTROLS_HIDE_DELAY_MS)
		}
	}

	private fun hideControls() {
		controlsHandler.removeCallbacks(autoHideRunnable)
		binding.controlsPanel.isGone = true
	}

	private fun updatePlayPauseIcon() {
		val playing = isPlayingSafe()
		binding.buttonPlayPause.setImageResource(
			if (playing) R.drawable.ic_action_pause else R.drawable.ic_play,
		)
		binding.buttonPlayPause.contentDescription = binding.root.resources.getString(
			if (playing) R.string.pause else R.string.play,
		)
	}

	private fun updateLoopButton() {
		binding.buttonLoop.alpha = if (isLooping) 1f else 0.5f
	}

	private fun updateMuteButton() {
		binding.buttonMute.setText(
			if (controller.isMuted) R.string.reader_video_unmute else R.string.reader_video_mute,
		)
	}

	private fun updateSpeedButton() {
		binding.buttonSpeed.text = SPEED_LABELS[speedIndex]
	}

	// endregion

	// region lifecycle

	override fun onPause(owner: LifecycleOwner) {
		// page lost focus (paged swipe, app background): pause + detach -
		// reader videos never play on in the background (TSD non-goal §1.3)
		controller.detach(this)
	}

	override fun onResume(owner: LifecycleOwner) {
		if (!isHandling) return
		val page = boundPage ?: return
		when {
			// webtoon drives attach from the center-scroll listener instead
			controller.mode == ReaderSharedMediaController.Mode.WEBTOON -> Unit
			!playbackStarted -> startFromAutoPlay()
			wasPlayingBeforePause -> {
				wasPlayingBeforePause = false
				currentStreamUrl?.let { url -> controller.attach(this, binding.videoSurface, buildItem(page, url)) }
			}
		}
	}

	override fun onDestroy(owner: LifecycleOwner) {
		reset()
	}

	// endregion

	// region download to storage

	private fun downloadVideo(page: ReaderPage, streamUrl: String, mimeType: String) {
		if (loadJob?.isActive == true) {
			return
		}
		loadJob = lifecycleOwner.lifecycleScope.launch {
			binding.progressBuffering.isVisible = binding.videoContainer.isVisible
			binding.progressVideo.isVisible = binding.videoContainer.isGone
			try {
				val dir = entryPoint.settings().getPagesSaveDir(appContext)
				if (dir == null) {
					Snackbar.make(binding.root, R.string.no_download_folder, Snackbar.LENGTH_LONG).show()
				} else {
					val baseName = SAVE_BASE_NAME + nameDateFormat.format(Date())
					val extension = streamUrl.urlExtension().ifEmpty { FALLBACK_EXTENSION }
					val doc = dir.createFile(mimeType, "$baseName.$extension")
						?: throw IOException("Cannot create destination file")
					if (streamUrl.toUri().isNetworkUri()) {
						// the ONLY path that persists a full video: direct streaming
						// download, chunked, no proxy chain, no page cache
						entryPoint.originalImageDownloader().download(streamUrl, page.source, doc.uri)
					} else {
						throw IOException("Only network video URLs can be downloaded")
					}
					Snackbar.make(binding.root, R.string.video_saved, Snackbar.LENGTH_LONG).show()
				}
				binding.progressVideo.isGone = true
				binding.progressBuffering.isGone = true
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				binding.progressVideo.isGone = true
				binding.progressBuffering.isGone = true
				Snackbar.make(
					binding.root,
					e.getDisplayMessage(binding.root.resources),
					Snackbar.LENGTH_SHORT,
				).show()
			}
		}
	}

	// endregion

	// region dialogs

	private var actionDialogPage: ReaderPage? = null

	private fun showChoiceDialog(page: ReaderPage, actionsOnly: Boolean = false) {
		val variants = page.url.videoStreamVariants()
		if (variants.size > 1) {
			showQualityDialog(page, variants, actionsOnly)
		} else if (actionsOnly) {
			showActionDialog(page, variants.first().url)
		} else {
			startPlayback(variants.first().url)
		}
	}

	private fun showQualityDialog(page: ReaderPage, variants: List<StreamVariant>, actionsOnly: Boolean = false) {
		MaterialAlertDialogBuilder(binding.root.context)
			.setTitle(R.string.select_quality)
			.setItems(variants.map { it.label }.toTypedArray()) { _, which ->
				if (actionsOnly) {
					showActionDialog(page, variants[which].url)
				} else {
					startPlayback(variants[which].url)
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun showActionDialogFromUrl(streamUrl: String, titleRes: Int) {
		val page = boundPage ?: actionDialogPage ?: return
		showActionDialogWithTitle(page, streamUrl, titleRes)
	}

	private fun showActionDialog(page: ReaderPage, streamUrl: String) {
		showActionDialogWithTitle(page, streamUrl, titleRes = R.string.load_video)
	}

	private fun showActionDialogWithTitle(page: ReaderPage, streamUrl: String, titleRes: Int) {
		actionDialogPage = page
		currentStreamUrl = streamUrl
		val res = binding.root.resources
		val items = arrayOf(
			res.getString(R.string.play_in_vlc),
			res.getString(R.string.download_video),
			res.getString(R.string.open_external),
		)
		MaterialAlertDialogBuilder(binding.root.context)
			.setTitle(titleRes)
			.setItems(items) { _, which ->
				when (which) {
					0 -> {
						// VLC first; transparently fall back to any external handler
						if (!openExternal(streamUrl, VLC_PACKAGE) && !openExternal(streamUrl)) {
							showErrorSnackbar(Snackbar.LENGTH_SHORT)
						}
					}
					1 -> downloadVideo(page, streamUrl, streamUrl.videoMimeType())
					2 -> if (!openExternal(streamUrl)) {
						showErrorSnackbar(Snackbar.LENGTH_SHORT)
					}
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	// endregion

	// region external players

	private fun openExternal(url: String, packageName: String? = null): Boolean {
		val intent = Intent(Intent.ACTION_VIEW)
			.setDataAndType(url.toUri(), url.videoMimeType())
		if (packageName != null) {
			intent.setPackage(packageName)
		}
		// proper player metadata: external apps show the file name instead of the raw URL.
		// resolveActivity with a fixed package is unfiltered here because the manifest
		// holds QUERY_ALL_PACKAGES (Android 11 package visibility would otherwise hide VLC)
		intent.putExtra("title", url.urlFileName())
		val context = binding.root.context
		return if (intent.resolveActivity(context.packageManager) != null) {
			context.startActivity(intent)
			true
		} else {
			false
		}
	}

	private fun showErrorSnackbar(length: Int) {
		Snackbar.make(binding.root, R.string.error_occurred, length).show()
	}

	// endregion

	private fun showButtonState() {
		if (!isHandling) {
			return
		}
		binding.panelVideo.isVisible = true
		binding.progressVideo.isGone = true
		binding.buttonLoadVideo.isVisible = true
	}

	private companion object {

		private const val VLC_PACKAGE = "org.videolan.vlc"
		private const val SAVE_BASE_NAME = "booru-video-"
		private const val FALLBACK_EXTENSION = "mp4"

		private const val SKIP_MS = 10_000
		private const val PROGRESS_INTERVAL_MS = 250L
		private const val CONTROLS_HIDE_DELAY_MS = 3_000L
		private const val DOUBLE_TAP_EDGE = 0.35f

		private val SPEED_STEPS = floatArrayOf(0.5f, 1f, 1.5f, 2f)
		private val SPEED_LABELS = arrayOf("0.5×", "1×", "1.5×", "2×")
		private const val SPEED_DEFAULT_INDEX = 1

		// all accesses happen on the main thread (same pattern as PageSaveHelper)
		private val nameDateFormat = SimpleDateFormat("yyyy-MM-dd_HHmm")

		private fun formatTime(ms: Int): String {
			val totalSeconds = (ms / 1000).coerceAtLeast(0)
			val seconds = totalSeconds % 60
			val minutes = (totalSeconds / 60) % 60
			val hours = totalSeconds / 3600
			return if (hours > 0) {
				"$hours:${minutes.pad()}:${seconds.pad()}"
			} else {
				"$minutes:${seconds.pad()}"
			}
		}

		private fun Int.pad(): String = if (this < 10) "0$this" else toString()
	}
}
