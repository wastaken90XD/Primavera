package org.wastaken.kotatsu.api21.reader.ui.media

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
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
import org.wastaken.kotatsu.api21.core.parser.ParserMangaRepository
import org.wastaken.kotatsu.api21.core.util.ext.getDisplayMessage
import org.wastaken.kotatsu.api21.core.util.ext.isNetworkUri
import org.wastaken.kotatsu.api21.databinding.LayoutBooruVideoOverlayBinding
import org.wastaken.kotatsu.api21.reader.ui.pager.ReaderPage
import java.io.IOException
import java.net.IDN
import java.text.SimpleDateFormat
import java.util.Date

/**
 * In-reader video player for booru reader pages. **Booru sources only**
 * (source check first, always — see BooruMedia.kt).
 *
 * Videos never load or play automatically: a static placeholder with a
 * "Load video" button replaces the page and the normal image pipeline stays
 * suppressed (no prefetch, no page download). Once tapped (plus the optional
 * quality pick when the source publishes its variants), the reader UI changes
 * into a small but complete player: play/pause, seek timeline with position,
 * skip +/-10s, loop, playback speed (API 23+ only — [PlaybackParams] does not
 * exist below that), a buffering indicator and tap/double-tap gestures.
 *
 * Caching rule, strictly: playback streams through [VideoStreamProxy], a
 * loopback relay that reads the source via OkHttp (source headers replicated
 * here as [buildStreamHeaders], plus all interceptors, cookie jar and
 * CloudFlare handling apply inside OkHttp) into a bounded memory-only
 * read-ahead ring — the same architectural reason VLC/ExoPlayer play smoothly
 * (decouple network from decode by seconds, not the framework player's tiny
 * fixed HTTP cache). The full file is never written to app storage, never
 * enters the image-proxy chain, never touches the pages cache or the OkHttp
 * disk cache (no-store). The one and only way a full video file lands on the
 * device is "Download video" in the Actions dialog (chunked download straight
 * into the configured folder).
 *
 * "Actions" additionally keeps the previous destinations: Play in VLC (title
 * extra, transparent fallback to any external handler) and Open external.
 */
@Suppress("DEPRECATION") // classic AudioManager focus & STREAM_MUSIC: correct for API 21-25 targets
class VideoPageOverlay(
	private val binding: LayoutBooruVideoOverlayBinding,
	private val lifecycleOwner: LifecycleOwner,
) : DefaultLifecycleObserver, SurfaceHolder.Callback {

	var isHandling = false
		private set

	private var loadJob: Job? = null
	private var boundPage: ReaderPage? = null
	private var currentStreamUrl: String? = null

	private var mediaPlayer: MediaPlayer? = null
	private var videoProxy: VideoStreamProxy? = null
	private var surfaceReady = false
	private var pendingStartUrl: String? = null
	private var prepared = false
	private var playbackErrored = false

	private var isLooping = false
	private var speedIndex = SPEED_DEFAULT_INDEX
	private var lifecycleAutoPaused = false
	private var focusPausedPlaying = false

	private val controlsHandler = Handler(Looper.getMainLooper())

	private val progressRunnable = object : Runnable {
		override fun run() {
			val mp = mediaPlayer ?: return
			if (prepared && !binding.seekVideo.isPressed) {
				val position = runCatching { mp.currentPosition }.getOrDefault(0)
				binding.seekVideo.progress = position
				binding.textPosition.text = formatTime(position)
			}
			if (isPlayingSafe()) {
				controlsHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
			}
		}
	}

	private val autoHideRunnable = Runnable {
		if (isPlayingSafe()) {
			binding.controlsPanel.isGone = true
		}
	}

	private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
		when (focusChange) {
			AudioManager.AUDIOFOCUS_LOSS -> {
				focusPausedPlaying = false
				abandonAudioFocus()
				pausePlayback()
			}
			AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
			AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
				focusPausedPlaying = isPlayingSafe()
				if (focusPausedPlaying) {
					pausePlayback()
				}
			}
			AudioManager.AUDIOFOCUS_GAIN -> {
				if (focusPausedPlaying) {
					focusPausedPlaying = false
					startPlaybackResume()
				}
			}
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
					e.x < width * DOUBLE_TAP_EDGE -> seekBy(-SKIP_MS)
					e.x > width * (1f - DOUBLE_TAP_EDGE) -> seekBy(SKIP_MS)
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

	init {
		lifecycleOwner.lifecycle.addObserver(this)
	}

	/** @return true if this overlay handles the page and the normal page load must be suppressed. */
	fun onBind(page: ReaderPage): Boolean {
		reset()
		// source check comes first, always (see BooruMedia.kt)
		isHandling = page.source.isMediaPlayerEnabled(entryPoint.settings()) && page.url.looksLikeVideo()
		if (!isHandling) {
			return false
		}
		boundPage = page
		binding.root.isVisible = true
		binding.videoSurface.holder.addCallback(this)
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
		releasePlayer()
		// the holder recycles bind() calls on the same views - never stack callbacks
		binding.videoSurface.holder.removeCallback(this)
		boundPage = null
		currentStreamUrl = null
		pendingStartUrl = null
		speedIndex = SPEED_DEFAULT_INDEX
		binding.root.isGone = true
	}

	// region surface + media player

	override fun surfaceCreated(holder: SurfaceHolder) {
		surfaceReady = true
		pendingStartUrl?.let { url ->
			pendingStartUrl = null
			attachPlayer(url)
		}
	}

	override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

	override fun surfaceDestroyed(holder: SurfaceHolder) {
		surfaceReady = false
	}

	private fun startPlayback(streamUrl: String) {
		currentStreamUrl = streamUrl
		playbackErrored = false
		releasePlayer()
		binding.panelVideo.isGone = true
		binding.progressVideo.isGone = true
		binding.videoContainer.isVisible = true
		binding.progressBuffering.isVisible = true
		showControls(hideLater = true)
		if (surfaceReady) {
			attachPlayer(streamUrl)
		} else {
			// surfaceCreated has not fired for the freshly shown container yet;
			// it picks the URL up from here
			pendingStartUrl = streamUrl
		}
	}

	private fun attachPlayer(streamUrl: String) {
		val page = boundPage ?: return
		val mp = MediaPlayer()
		mediaPlayer = mp
		prepared = false
		mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
		mp.setDisplay(binding.videoSurface.holder)
		mp.setOnPreparedListener { player -> onPlayerPrepared(player) }
		mp.setOnInfoListener { _, what, _ -> onPlayerInfo(what) }
		mp.setOnCompletionListener { onPlayerCompletion() }
		mp.setOnErrorListener { _, _, _ ->
			onPlaybackError()
			true
		}
		mp.setOnVideoSizeChangedListener { player, _, _ -> fitSurface(player) }
		mp.setOnBufferingUpdateListener { _, percent ->
			val duration = binding.seekVideo.max
			if (duration > 0) {
				binding.seekVideo.secondaryProgress = duration * percent / 100
			}
		}
		try {
			// smooth like real players: VLC/ExoPlayer decouple network from decode
			// with a large read-ahead buffer; the framework player's own HTTP cache
			// is tiny. The loopback relay gives it one (bounded memory ring, okio
			// segments, zero disk, no-store upstream, Range-aware seeks).
			val proxy = VideoStreamProxy(entryPoint.okHttpClient())
			videoProxy = proxy
			val localUrl = proxy.start(streamUrl, page.source, buildStreamHeaders(page))
			mp.setDataSource(appContext, Uri.parse(localUrl))
			mp.prepareAsync()
		} catch (e: Exception) {
			onPlaybackError()
		}
	}

	private fun onPlayerPrepared(mp: MediaPlayer) {
		prepared = true
		fitSurface(mp)
		val duration = runCatching { mp.duration }.getOrDefault(0).coerceAtLeast(0)
		binding.seekVideo.max = duration
		binding.seekVideo.progress = 0
		binding.textPosition.text = formatTime(0)
		binding.textDuration.text = formatTime(duration)
		mp.isLooping = isLooping
		applySpeed(mp)
		binding.progressBuffering.isGone = true
		requestAudioFocus()
		mp.start()
		updatePlayPauseIcon()
		controlsHandler.post(progressRunnable)
		showControls(hideLater = true)
	}

	private fun onPlayerInfo(what: Int): Boolean {
		when (what) {
			MediaPlayer.MEDIA_INFO_BUFFERING_START -> binding.progressBuffering.isVisible = true
			MediaPlayer.MEDIA_INFO_BUFFERING_END -> binding.progressBuffering.isGone = true
		}
		return false
	}

	private fun onPlayerCompletion() {
		if (isLooping) {
			return
		}
		updatePlayPauseIcon()
		showControls(hideLater = false)
	}

	private fun onPlaybackError() {
		if (playbackErrored) {
			// both the prepared/setDataSource path and the listener can report the
			// same failure once - the fallback dialog must fire only once
			return
		}
		playbackErrored = true
		val url = currentStreamUrl
		releasePlayer()
		showButtonState()
		if (url != null) {
			// graceful fallback: still offer the external destinations for this stream
			showActionDialogFromUrl(url, titleRes = R.string.error_occurred)
		} else {
			showErrorSnackbar(Snackbar.LENGTH_SHORT)
		}
	}

	private fun releasePlayer() {
		controlsHandler.removeCallbacks(progressRunnable)
		controlsHandler.removeCallbacks(autoHideRunnable)
		mediaPlayer?.let { mp ->
			runCatching { mp.setDisplay(null) }
			runCatching { mp.release() }
		}
		mediaPlayer = null
		videoProxy?.stop()
		videoProxy = null
		prepared = false
		abandonAudioFocus()
		focusPausedPlaying = false
		lifecycleAutoPaused = false
		binding.videoContainer.isGone = true
		binding.progressBuffering.isGone = true
	}

	private fun isPlayingSafe(): Boolean {
		val mp = mediaPlayer ?: return false
		return prepared && runCatching { mp.isPlaying }.getOrDefault(false)
	}

	// letterbox the surface inside the container (aspect-fit), like an image page
	private fun fitSurface(mp: MediaPlayer) {
		val container = binding.videoContainer
		val cw = container.width
		val ch = container.height
		val vw = runCatching { mp.videoWidth }.getOrDefault(0)
		val vh = runCatching { mp.videoHeight }.getOrDefault(0)
		if (cw <= 0 || ch <= 0 || vw <= 0 || vh <= 0) {
			return
		}
		val scale = minOf(cw.toFloat() / vw, ch.toFloat() / vh)
		val lp = binding.videoSurface.layoutParams as FrameLayout.LayoutParams
		lp.width = (vw * scale).toInt()
		lp.height = (vh * scale).toInt()
		lp.gravity = android.view.Gravity.CENTER
		binding.videoSurface.layoutParams = lp
	}

	/**
	 * Rebuilds the headers [org.wastaken.kotatsu.api21.core.network.CommonHeadersInterceptor]
	 * would have added if this request went through OkHttp: parser request headers first,
	 * then the default user agent, then a referer derived from the source domain.
	 * Cookies/Cloudflare handling cannot be replicated outside OkHttp; direct video CDNs
	 * used by booru sources only key on referer/user-agent in practice.
	 */
	private fun buildStreamHeaders(page: ReaderPage): Map<String, String> {
		val result = LinkedHashMap<String, String>()
		val repository = runCatching {
			entryPoint.mangaRepositoryFactory().create(page.source) as? ParserMangaRepository
		}.getOrNull()
		repository?.getRequestHeaders()?.let { headers ->
			for (name in headers.names()) {
				headers[name]?.let { value -> result.putIfAbsent(name, value) }
			}
		}
		if (result.hasNoHeader(HEADER_USER_AGENT)) {
			result[HEADER_USER_AGENT] = entryPoint.mangaLoaderContext().getDefaultUserAgent()
		}
		if (repository != null && result.hasNoHeader(HEADER_REFERER)) {
			result[HEADER_REFERER] = "https://${IDN.toASCII(repository.domain)}/"
		}
		return result
	}

	private fun Map<String, String>.hasNoHeader(name: String): Boolean =
		keys.none { it.equals(name, ignoreCase = true) }

	// endregion

	// region controls

	private var controlsInitialized = false

	private fun initControlsOnce() {
		if (controlsInitialized) {
			return
		}
		controlsInitialized = true
		binding.buttonPlayPause.setOnClickListener {
			if (isPlayingSafe()) {
				pausePlayback()
			} else {
				startPlaybackResume()
			}
		}
		binding.buttonSkipBack.setOnClickListener { seekBy(-SKIP_MS) }
		binding.buttonSkipForward.setOnClickListener { seekBy(SKIP_MS) }
		binding.buttonLoop.setOnClickListener {
			isLooping = !isLooping
			mediaPlayer?.let { mp -> runCatching { mp.isLooping = isLooping } }
			updateLoopButton()
		}
		updateLoopButton()
		binding.buttonActions.setOnClickListener {
			currentStreamUrl?.let { url -> showActionDialogFromUrl(url, titleRes = R.string.load_video) }
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			updateSpeedButton()
			binding.buttonSpeed.setOnClickListener {
				speedIndex = (speedIndex + 1) % SPEED_STEPS.size
				mediaPlayer?.let { mp -> applySpeed(mp) }
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
				val mp = mediaPlayer
				if (mp != null && prepared) {
					runCatching { mp.seekTo(seekBar.progress) }
					controlsHandler.post(progressRunnable)
				}
			}
		})
		binding.videoContainer.setOnTouchListener { _, event ->
			gestureDetector.onTouchEvent(event)
			true
		}
	}

	private fun pausePlayback() {
		val mp = mediaPlayer ?: return
		if (!prepared) {
			return
		}
		runCatching { mp.pause() }
		updatePlayPauseIcon()
		showControls(hideLater = false)
	}

	private fun startPlaybackResume() {
		val mp = mediaPlayer ?: return
		if (!prepared) {
			return
		}
		requestAudioFocus()
		runCatching { mp.start() }
		updatePlayPauseIcon()
		controlsHandler.post(progressRunnable)
		showControls(hideLater = true)
	}

	private fun seekBy(deltaMs: Int) {
		val mp = mediaPlayer ?: return
		if (!prepared) {
			return
		}
		val duration = binding.seekVideo.max
		val position = runCatching { mp.currentPosition }.getOrDefault(0)
		val target = (position + deltaMs).coerceIn(0, duration)
		runCatching { mp.seekTo(target) }
		if (!binding.seekVideo.isPressed) {
			binding.seekVideo.progress = target
			binding.textPosition.text = formatTime(target)
		}
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

	private fun applySpeed(mp: MediaPlayer) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			return
		}
		val wasPlaying = isPlayingSafe()
		val params = PlaybackParams().setSpeed(SPEED_STEPS[speedIndex])
		runCatching { mp.playbackParams = params }
		if (wasPlaying) {
			runCatching { mp.start() }
		}
	}

	private fun updateSpeedButton() {
		binding.buttonSpeed.text = SPEED_LABELS[speedIndex]
	}

	// endregion

	// region audio focus

	private fun requestAudioFocus() {
		val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
		am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
	}

	private fun abandonAudioFocus() {
		val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
		am.abandonAudioFocus(audioFocusListener)
	}

	// endregion

	// region lifecycle

	override fun onPause(owner: LifecycleOwner) {
		if (isPlayingSafe()) {
			lifecycleAutoPaused = true
			pausePlayback()
		}
	}

	override fun onResume(owner: LifecycleOwner) {
		if (lifecycleAutoPaused && prepared) {
			lifecycleAutoPaused = false
			startPlaybackResume()
		}
	}

	override fun onDestroy(owner: LifecycleOwner) {
		releasePlayer()
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
				if (!prepared) {
					binding.progressBuffering.isGone = true
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				binding.progressVideo.isGone = true
				if (!prepared) {
					binding.progressBuffering.isGone = true
				}
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

		private const val HEADER_USER_AGENT = "User-Agent"
		private const val HEADER_REFERER = "Referer"

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
