package org.wastaken.kotatsu.api21.booru.media

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.wastaken.kotatsu.api21.R
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.wastaken.kotatsu.api21.booru.media.ui.BooruPlayerActivity
import org.wastaken.kotatsu.api21.core.network.MangaHttpClient
import org.wastaken.kotatsu.api21.core.parser.MangaRepository
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.util.ext.checkNotificationPermission
import org.wastaken.kotatsu.api21.reader.ui.media.StreamPartCache
import org.wastaken.kotatsu.api21.reader.ui.media.VideoStreamProxy
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The single owner of all booru media playback (NewPipe PlayerHolder model):
 * one service, one queue, one player state, no matter which screen triggered it.
 *
 * - Bound + started: UI surfaces (BooruPlayerActivity, floating window) bind and
 *   submit their Surface; the service survives their lifecycle and never restarts
 *   from scratch.
 * - Foreground with a persistent notification while anything is playing or queued,
 *   so API-21 low-memory killer does not interrupt playback.
 * - The queue is restored from SharedPreferences on start (if the persist setting
 *   is on) and saved on every change; repeat mode rides along.
 *
 * Video plays through the stock [MediaPlayer] fed by the memory-only
 * [VideoStreamProxy] (streamed via the manga OkHttp stack with full source
 * headers). GIFs are fetched once into memory and handed to the UI layer to
 * step/loop (frame stepping is a view-level concern on the Movie timeline).
 */
@AndroidEntryPoint
class BooruMediaService : Service(), BooruMediaQueue.Listener {

	enum class PlaybackState { IDLE, PREPARING, PLAYING, PAUSED }

	interface Listener {
		fun onQueueChanged()
		fun onPlaybackStateChanged(state: PlaybackState, item: BooruMediaItem?)
		fun onPlaybackError(item: BooruMediaItem?, what: Int, extra: Int)
	}

	inner class LocalBinder : Binder() {
		val service: BooruMediaService get() = this@BooruMediaService
	}

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var queueStore: BooruMediaQueueStore

	@MangaHttpClient
	@Inject
	lateinit var okHttpClient: OkHttpClient

	@Inject
	lateinit var mangaRepositoryFactory: MangaRepository.Factory

	@Inject
	lateinit var mangaLoaderContext: MangaLoaderContext

	val queue = BooruMediaQueue()

	var playbackState = PlaybackState.IDLE
		private set

	val currentItem: BooruMediaItem?
		get() = queue.current()

	private val binder = LocalBinder()
	private val listeners = ArrayList<Listener>()
	private var mediaPlayer: MediaPlayer? = null
	private var vlcPlayer: BooruVlcPlayer? = null
	private var proxy: VideoStreamProxy? = null
	private var boundSurface: Surface? = null
	private var boundSurfaceTexture: SurfaceTexture? = null
	private var boundTargetWidth = 0
	private var boundTargetHeight = 0
	private var videoLooping = false
	private var pendingResumeMs = 0
	private var hibernatedItemId: Long? = null
	private var hibernatedResumeMs = 0
	private var hibernateTask: Runnable? = null
	private val handler = android.os.Handler(android.os.Looper.getMainLooper())
	private var prepareWatchdog: Runnable? = null
	private var gifBytesCache: Pair<String, ByteArray>? = null
	private var audioManager: AudioManager? = null
	private var speed = 1f
	private var floatingController: FloatingPlayerController? = null

	/** Bridges for the active player UI (activity / floating window). */
	var onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null

	var onInfo: ((MediaPlayer, what: Int, extra: Int) -> Boolean)? = null

	/** Engine-neutral buffering signal: system MEDIA_INFO_* and libVLC Buffering both mapped here. */
	var onBufferingChange: ((buffering: Boolean) -> Unit)? = null

	/** libVLC TimeChanged events drive the seekbar; the system engine is polled by the UI instead. */
	var onPlaybackProgress: ((positionMs: Int, durationMs: Int) -> Unit)? = null

	/** The engine actually powering the current playback (settings value taken at open time). */
	val activeVideoEngine: BooruVideoEngine
		get() = if (vlcPlayer != null) BooruVideoEngine.LIBVLC else BooruVideoEngine.SYSTEM

	var videoWidth = 0
		private set

	var videoHeight = 0
		private set

	val isFloating: Boolean
		get() = floatingController?.isShowing == true

	/**
	 * Opens the system-overlay floating player (Task 3). Returns false when the
	 * overlay permission is missing and the caller must route the user to
	 * [FloatingPlayerController.overlaySettingsIntent]. GIF items render only in
	 * the full-screen activity (Movie drawing is view-bound), so a GIF current
	 * item returns true but keeps the window closed.
	 */
	fun openFloating(): Boolean {
		if (floatingController == null) floatingController = FloatingPlayerController(this, this)
		val controller = floatingController ?: return false
		if (!controller.canShowOverlay()) return false
		val item = currentItem
		if (item == null || item.mediaType == BooruMediaType.GIF) return true
		controller.show()
		return true
	}

	fun closeFloating() {
		floatingController?.hide()
	}

	fun overlaySettingsIntent() = FloatingPlayerController(this, this).overlaySettingsIntent()

	override fun onCreate() {
		super.onCreate()
		audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
		createNotificationChannel()
		queue.addListener(this)
		if (settings.isMediaQueuePersist) {
			queueStore.restoreInto(queue)
		}
		speed = settings.mediaDefaultSpeed
		ensureForeground()
	}

	override fun onBind(intent: Intent?): IBinder = binder

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			ACTION_TOGGLE -> togglePlayPause()
			ACTION_NEXT -> playNext()
			ACTION_PREV -> playPrevious()
			ACTION_STOP -> {
				releaseVideo()
				stopSelf()
			}
			else -> Unit
		}
		ensureForeground()
		// NOT_STICKY (NewPipe PlayerService parity): the queue is persisted, so
		// after LMK kills us an empty resurrection just wastes RAM on this device
		return START_NOT_STICKY
	}

	/**
	 * NewPipe teardown parity: when the app task is swiped away there is no
	 * reader screen left for a video engine to serve. The one exception is the
	 * floating window - it IS a visible surface the user deliberately keeps,
	 * so "reader + player active at the same time" stays alive.
	 */
	override fun onTaskRemoved(rootIntent: Intent?) {
		if (!isFloating) {
			hibernateEngine()
			ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
			stopSelf()
		}
		super.onTaskRemoved(rootIntent)
	}

	override fun onDestroy() {
		releaseVideo()
		queue.removeListener(this)
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		super.onDestroy()
	}

	// region listeners

	fun addListener(listener: Listener) {
		if (listener !in listeners) listeners.add(listener)
	}

	fun removeListener(listener: Listener) {
		listeners.remove(listener)
	}

	override fun onQueueChanged() {
		if (settings.isMediaQueuePersist) {
			queueStore.persist(queue)
		} else {
			queueStore.clear()
		}
		notifyListeners { onQueueChanged() }
		updateNotification()
	}

	private fun setState(state: PlaybackState) {
		if (playbackState == state) return
		playbackState = state
		notifyListeners { onPlaybackStateChanged(state, currentItem) }
		// the floating window icon tracks every source, not just its own tap
		floatingController?.syncPlayButton()
		updateNotification()
	}

	private fun notifyError(item: BooruMediaItem?, what: Int, extra: Int) {
		notifyListeners { onPlaybackError(item, what, extra) }
	}

	private inline fun notifyListeners(block: Listener.() -> Unit) {
		for (listener in listeners.toList()) listener.block()
	}

	// endregion

	// region public playback API

	fun playNow(item: BooruMediaItem) {
		val existing = queue.items.indexOfFirst { it.id == item.id }
		val position = if (existing >= 0) existing else queue.add(item)
		playIndex(position)
	}

	fun playIndex(position: Int) {
		val item = queue.jumpTo(position) ?: return
		if (item.mediaType == BooruMediaType.VIDEO) {
			openVideo(item)
		} else {
			releaseVideo() // GIFs render in the view layer; state only tracks "current"
			setState(PlaybackState.PLAYING)
		}
	}

	fun playNext() {
		val next = queue.nextIndex()
		if (next >= 0) playIndex(next) else setState(PlaybackState.IDLE)
	}

	fun playPrevious() {
		val prev = queue.previousIndex()
		if (prev >= 0) playIndex(prev)
	}

	fun togglePlayPause() {
		val vlc = vlcPlayer
		if (vlc != null) {
			if (vlc.isPlaying) {
				vlc.pause()
				setState(PlaybackState.PAUSED)
				abandonAudioFocus()
				scheduleHibernateIfIdle()
			} else {
				requestAudioFocus()
				cancelHibernate() // a resume invalidates the pause-armed countdown
				vlc.resume()
				setState(PlaybackState.PLAYING)
			}
			return
		}
		if (mediaPlayer == null) {
			// hibernated engine (NewPipe lazy-player pattern): the touch that
			// brings the UI back resurrects playback from the saved position
			val item = currentItem
			if (item != null && item.mediaType == BooruMediaType.VIDEO && item.id == hibernatedItemId) {
				playIndex(queue.index)
			}
			return
		}
		val player = mediaPlayer ?: return
		if (player.isPlaying) {
			player.pause()
			setState(PlaybackState.PAUSED)
			abandonAudioFocus()
			scheduleHibernateIfIdle()
		} else {
			requestAudioFocus()
			cancelHibernate() // a resume invalidates the pause-armed countdown
			player.start()
			setState(PlaybackState.PLAYING)
		}
	}

	fun seekTo(positionMs: Int) {
		val vlc = vlcPlayer
		if (vlc != null) {
			runCatching { vlc.seekTo(positionMs.toLong().coerceAtLeast(0L)) }
			return
		}
		mediaPlayer?.let { player ->
			runCatching { player.seekTo(positionMs.coerceIn(0, player.duration)) }
		}
	}

	fun seekBy(deltaMs: Int) {
		seekTo(videoPosition() + deltaMs)
	}

	fun isVideoPlaying(): Boolean = runCatching {
		vlcPlayer?.isPlaying ?: (mediaPlayer?.isPlaying == true)
	}.getOrDefault(false)

	fun videoDuration(): Int = runCatching {
		vlcPlayer?.length?.toInt() ?: mediaPlayer?.duration ?: 0
	}.getOrDefault(0)

	fun videoPosition(): Int = runCatching {
		vlcPlayer?.time?.toInt() ?: mediaPlayer?.currentPosition ?: 0
	}.getOrDefault(0)

	fun setSpeed(newSpeed: Float) {
		speed = newSpeed
		val vlc = vlcPlayer
		if (vlc != null) {
			// libVLC rate control works on every API level (no API-23 gate)
			runCatching { vlc.setSpeed(newSpeed) }
			return
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			runCatching {
				val player = mediaPlayer ?: return@runCatching
				val params = player.playbackParams
				if (params != null) player.playbackParams = params.setSpeed(newSpeed)
			}
		}
	}

	fun setVideoLooping(looping: Boolean) {
		videoLooping = looping
		runCatching { mediaPlayer?.isLooping = looping }
	}

	/**
	 * Hand the active render surface to the player (or null to detach).
	 * Mirrors the old reader overlay's mp.setDisplay(holder) semantics: the
	 * surface is REMEMBERED so openVideo can attach it before setDataSource;
	 * previously it was dropped whenever the player did not exist yet
	 * (texture-available raced ahead of service bind), which left the player
	 * surface-less forever - black + silent on Samsung API 21.
	 */
	fun bindSurface(surface: Surface?, width: Int = 0, height: Int = 0) {
		boundSurface = surface
		boundTargetWidth = width
		boundTargetHeight = height
		runCatching { mediaPlayer?.setSurface(surface) }
		if (surface != null) cancelHibernate() else scheduleHibernateIfIdle()
	}

	/**
	 * libVLC render target: the RAW SurfaceTexture (never the TextureView, so
	 * libVLC cannot clobber the UI's own surface listener). Remembers the pair
	 * like [bindSurface] does, so a texture that races in before openVideo is
	 * replayed onto the fresh engine; a swap while playing keeps the stream
	 * running (floating <-> full-screen handoff).
	 */
	fun bindSurfaceTexture(surfaceTexture: SurfaceTexture?, width: Int = 0, height: Int = 0) {
		boundSurfaceTexture = surfaceTexture
		boundTargetWidth = width
		boundTargetHeight = height
		vlcPlayer?.setRenderTarget(surfaceTexture, width, height)
		if (surfaceTexture != null) cancelHibernate() else scheduleHibernateIfIdle()
	}

	/** GIF bytes for the view layer, cached for one URL; full headers, explicit load. */
	suspend fun loadGifBytes(item: BooruMediaItem): ByteArray {
		gifBytesCache?.takeIf { it.first == item.url }?.let { return it.second }
		val headers = BooruStreamHeaders.forStream(mangaRepositoryFactory, mangaLoaderContext, item.source, item.url)
		val request = Request.Builder().url(item.url)
		val builder = okhttp3.Headers.Builder()
		for ((k, v) in headers) builder.add(k, v)
		val bytes = okHttpClient.newCall(request.headers(builder.build()).build()).await().use { response ->
			if (!response.isSuccessful) throw IOException("HTTP ${response.code} loading GIF")
			response.body?.bytes() ?: throw IOException("Empty GIF body")
		}
		// capped: the cache exists for rotation survival, not for holding large
		// animations resident on a low-RAM device (Movie keeps its own copy)
		gifBytesCache = if (bytes.size <= GIF_CACHE_MAX_BYTES) item.url to bytes else null
		return bytes
	}

	fun stopPlaybackAndQueueClear() {
		queue.clear()
		releaseVideo()
		setState(PlaybackState.IDLE)
		stopSelf()
	}

	// endregion

	// region video engine

	/**
	 * Streaming cache from Settings: part size x part count is the disk budget,
	 * the proxy keeps a quarter of it as the in-memory read-ahead ring. Parts
	 * are spooled while the stream is consumed and purged on stop/new video -
	 * StreamPartCache owns the hygiene contract.
	 */
	private fun createStreamPartCache(): StreamPartCache = StreamPartCache(
		directory = File(cacheDir, DIR_STREAM_PARTS),
		partSizeBytes = settings.booruStreamPartSizeMb.toLong() * 1024L * 1024L,
		maxParts = settings.booruStreamPartCount,
	)

	private fun openVideo(item: BooruMediaItem) {
		val engine = settings.booruVideoEngine
		// libVLC: stop-only so the instance survives across queue items;
		// system engine / explicit teardowns always get the full release
		releaseVideo(releaseVlc = engine != BooruVideoEngine.LIBVLC)
		setState(PlaybackState.PREPARING)
		// consume a hibernation resume point before it can leak across items
		pendingResumeMs = if (hibernatedItemId == item.id) hibernatedResumeMs else 0
		hibernatedItemId = null
		hibernatedResumeMs = 0
		val headers = BooruStreamHeaders.forStream(mangaRepositoryFactory, mangaLoaderContext, item.source, item.url)
		val streamProxy = VideoStreamProxy(okHttpClient, createStreamPartCache())
		val localUrl = try {
			streamProxy.start(item.url, item.source, headers)
		} catch (e: Exception) {
			streamProxy.stop()
			setState(PlaybackState.IDLE)
			notifyError(item, 0, 0)
			return
		}
		proxy = streamProxy
		videoLooping = settings.isMediaVideoLoop || queue.repeatMode == RepeatMode.ONE
		when (engine) {
			BooruVideoEngine.LIBVLC -> openVideoVlc(item, localUrl)
			BooruVideoEngine.SYSTEM -> openVideoSystem(item, localUrl)
		}
	}

	/**
	 * libVLC engine (BooruVlcPlayer). No prepare watchdog: EncounteredError is
	 * deterministic, unlike the system MediaPlayer's silent-hang bug that guard
	 * works around. EndReached replaces both isLooping and onCompletion, so the
	 * loop/repeat logic lives here instead of in the platform player.
	 */
	private fun openVideoVlc(item: BooruMediaItem, localUrl: String) {
		// vlc-android VLCInstance pattern: ONE engine per service lifetime,
		// reused across items; created lazily on the first play touch only
		val existing = vlcPlayer
		val vlc = if (existing != null) {
			existing
		} else {
			try {
				BooruVlcPlayer(this)
			} catch (e: Exception) {
				// native libVLC init failed (e.g. OOM unpacking native libs on a
				// low-RAM device): fall back to the legacy system engine for this run
				openVideoSystem(item, localUrl)
				return
			}
		}
		if (existing == null) {
			vlcPlayer = vlc
			vlc.callback = object : BooruVlcPlayer.Callback {
				// every handler reads service state at fire time, never a
				// captured item: one callback now outlives many play() calls

				override fun onPlaying(videoWidth: Int, videoHeight: Int) {
					if (vlcPlayer !== vlc) return
					if (videoWidth > 0 && videoHeight > 0) {
						this@BooruMediaService.videoWidth = videoWidth
						this@BooruMediaService.videoHeight = videoHeight
						onVideoSizeChanged?.invoke(videoWidth, videoHeight)
					}
					runCatching { vlc.setSpeed(speed) }
					val resumeMs = pendingResumeMs
					pendingResumeMs = 0
					if (resumeMs > 0) runCatching { vlc.seekTo(resumeMs.toLong()) }
					requestAudioFocus()
					setState(PlaybackState.PLAYING)
				}

				override fun onBuffering(percent: Float) {
					if (vlcPlayer !== vlc) return
					onBufferingChange?.invoke(percent < 100f)
				}

				override fun onEncounteredError() {
					if (vlcPlayer !== vlc) return
					val failedItem = currentItem
					releaseVideo()
					setState(PlaybackState.IDLE)
					notifyError(failedItem, 0, 0)
				}

				override fun onEndReached() {
					if (vlcPlayer !== vlc) return
					if (videoLooping) {
						runCatching {
							vlc.seekTo(0)
							vlc.resume()
						}
					} else {
						val next = queue.nextIndex()
						if (next >= 0) playIndex(next) else setState(PlaybackState.IDLE)
					}
				}

				override fun onTimeChanged(positionMs: Long) {
					if (vlcPlayer !== vlc) return
					onPlaybackProgress?.invoke(positionMs.toInt(), vlc.length.toInt())
				}
			}
		}
		// a texture that raced in during proxy start is remembered; replay it so
		// the fresh engine never starts surface-less (same contract as bindSurface)
		boundSurfaceTexture?.let { vlc.setRenderTarget(it, boundTargetWidth, boundTargetHeight) }
		try {
			vlc.play(localUrl)
		} catch (e: Exception) {
			releaseVideo()
			setState(PlaybackState.IDLE)
			notifyError(item, 0, 0)
		}
	}

	/** Legacy system MediaPlayer engine (BooruVideoEngine.SYSTEM), pre-libVLC behavior verbatim. */
	private fun openVideoSystem(item: BooruMediaItem, localUrl: String) {
		val player = MediaPlayer()
		mediaPlayer = player
		player.setOnPreparedListener { mp ->
			if (mediaPlayer !== mp) return@setOnPreparedListener
			cancelPrepareWatchdog()
			// a surface that raced in during prepareAsync can be dropped by the
			// platform's state restriction on setSurface; re-attach on prepared
			runCatching { mp.setSurface(boundSurface) }
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
			}
			runCatching { mp.isLooping = videoLooping }
			val resumeMs = pendingResumeMs
			pendingResumeMs = 0
			if (resumeMs > 0) runCatching { mp.seekTo(resumeMs) }
			requestAudioFocus()
			mp.start()
			setState(PlaybackState.PLAYING)
		}
		player.setOnCompletionListener { mp ->
			if (mediaPlayer !== mp) return@setOnCompletionListener
			autoAdvance()
		}
		player.setOnErrorListener { mp, what, extra ->
			if (mediaPlayer === mp) {
				releaseVideo()
				setState(PlaybackState.IDLE)
				notifyError(item, what, extra)
			}
			true
		}
		player.setOnVideoSizeChangedListener { mp, w, h ->
			if (mediaPlayer === mp) {
				videoWidth = w
				videoHeight = h
				onVideoSizeChanged?.invoke(w, h)
			}
		}
		player.setOnInfoListener { mp, what, extra ->
			when (what) {
				MediaPlayer.MEDIA_INFO_BUFFERING_START -> onBufferingChange?.invoke(true)
				MediaPlayer.MEDIA_INFO_BUFFERING_END -> onBufferingChange?.invoke(false)
			}
			onInfo?.invoke(mp, what, extra) == true
		}
		try {
			// attach the remembered surface BEFORE setDataSource/prepare, exactly
			// like the old overlay's mp.setDisplay(holder) ahead of prepareAsync;
			// a surface released meanwhile just renders audio-only until rebinding
			runCatching { player.setSurface(boundSurface) }
			player.setDataSource(localUrl)
		} catch (e: Exception) {
			releaseVideo()
			setState(PlaybackState.IDLE)
			notifyError(item, 0, 0)
			return
		}
		player.prepareAsync()
		// silent-hang guard: if prepare neither completes nor errors within the
		// window, surface it through the same error path so the user gets the
		// external-playback dialog instead of an eternal black screen
		val token = player
		val watchdog = Runnable {
			if (mediaPlayer === token && playbackState == PlaybackState.PREPARING) {
				releaseVideo()
				setState(PlaybackState.IDLE)
				notifyError(item, -1, 0)
			}
		}
		prepareWatchdog = watchdog
		handler.postDelayed(watchdog, PREPARE_TIMEOUT_MS)
	}

	private fun cancelPrepareWatchdog() {
		prepareWatchdog?.let { handler.removeCallbacks(it) }
		prepareWatchdog = null
	}

	// region engine hibernation
	//
	// NewPipe PlayerService model: the service shell (queue + notification) is
	// cheap, the video engine is not. While the user is only browsing/queueing
	// there must be no decoder, demuxer or proxy resident in RAM; the engine
	// materializes again on the next play touch. This is now strictly opt-in:
	// the pause grace comes from Settings (BooruHibernateMode, default NEVER)
	// because unrequested timers kept firing into live playback on this device,
	// while a real STOP always releases immediately, event-driven - no timer.
	// The graceful cover for rotation and the floating <-> full-screen blink
	// only matters when the user has actually armed a timed mode.

	private fun scheduleHibernateIfIdle() {
		cancelHibernate()
		// user-controlled (Settings -> Media player -> Player hibernation),
		// default NEVER: timers kept killing live playback on this device, so
		// releasing a paused engine is now an explicit choice, not a surprise
		val mode = settings.booruHibernateMode
		if (mode == BooruHibernateMode.NEVER) return
		// PLAYING is tracked app-side: the guarded RELEASED-before-check race is
		// native isPlaying lagging a just-issued resume/start by a few frames,
		// which is exactly the window an outdated pause timer fires in
		if (isVideoPlaying() || playbackState == PlaybackState.PLAYING ||
			playbackState == PlaybackState.PREPARING) return
		if (boundSurface != null || boundSurfaceTexture != null) return
		if (vlcPlayer == null && mediaPlayer == null) return
		val task = Runnable { hibernateEngine() }
		hibernateTask = task
		handler.postDelayed(task, mode.delayMs)
	}

	private fun cancelHibernate() {
		hibernateTask?.let { handler.removeCallbacks(it) }
		hibernateTask = null
	}

	private fun hibernateEngine(allowSurfaceBound: Boolean = false) {
		hibernateTask = null
		// the guards were true when the timer POSTED, not necessarily when it
		// FIRES: a background resume (notification toggle rebinds no surface)
		// can restart playback inside the grace window, and releasing the
		// engine mid-decode is exactly the process-death path on API 21
		if (isVideoPlaying() || playbackState == PlaybackState.PLAYING ||
			playbackState == PlaybackState.PREPARING) return
		if (!allowSurfaceBound && (boundSurface != null || boundSurfaceTexture != null)) return
		if (vlcPlayer == null && mediaPlayer == null) return
		val item = currentItem
		if (item != null && item.mediaType == BooruMediaType.VIDEO) {
			hibernatedItemId = item.id
			hibernatedResumeMs = videoPosition()
		}
		releaseVideo(releaseVlc = true)
		setState(PlaybackState.IDLE)
	}

	// endregion

	private fun autoAdvance() {
		when (queue.repeatMode) {
			RepeatMode.ONE -> {
				mediaPlayer?.let { runCatching { it.seekTo(0); it.start() } } ?: playIndex(queue.index)
			}
			else -> {
				val next = queue.nextIndex()
				if (next >= 0) {
					playIndex(next)
				} else {
					setState(PlaybackState.IDLE)
					// the video really STOPPED (finished, not paused): release
					// immediately, event-driven - no timer involved, no grace
					// window to preserve because nothing is playing; the replay
					// touch re-materializes the engine from the ground up
					hibernateEngine(allowSurfaceBound = true)
				}
			}
		}
	}

	/**
	 * releaseVlc=false only stops the libVLC instance (it is reused across
	 * queue items, vlc-android single-MediaPlayer style); true tears it down
	 * fully - used by hibernation, errors, engine switches and service death.
	 */
	private fun releaseVideo(releaseVlc: Boolean = true) {
		cancelPrepareWatchdog()
		cancelHibernate()
		if (releaseVlc) {
			runCatching { vlcPlayer?.release() }
			vlcPlayer = null
		} else {
			runCatching { vlcPlayer?.stopPlayback() }
		}
		runCatching {
			mediaPlayer?.setOnPreparedListener(null)
			mediaPlayer?.setOnErrorListener(null)
			mediaPlayer?.setOnCompletionListener(null)
			mediaPlayer?.stop()
			mediaPlayer?.reset()
			mediaPlayer?.release()
		}
		mediaPlayer = null
		videoLooping = false
		// GIF bytes are the biggest Java-heap object this service ever holds;
		// never carry them across a media switch
		gifBytesCache = null
		proxy?.stop()
		proxy = null
		abandonAudioFocus()
	}

	// endregion

	// region audio focus

	private fun requestAudioFocus() {
		audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
	}

	private fun abandonAudioFocus() {
		audioManager?.abandonAudioFocus(null)
	}

	// endregion

	// region notification

	private fun ensureForeground() {
		val notification = buildNotification()
		try {
			ServiceCompat.startForeground(
				this, NOTIFICATION_ID, notification,
				ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
			)
		} catch (e: Exception) {
			// notification permission denied on 33+ or a restricted-start edge:
			// playback must continue rather than crash the app
		}
	}

	private fun updateNotification() {
		if (!checkNotificationPermission(CHANNEL_ID)) return
		NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
	}

	@SuppressLint("MissingPermission")
	private fun buildNotification(): Notification {
		val item = currentItem
		val builder = NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_play)
			.setContentTitle(item?.title ?: getString(R.string.media_player_settings))
			.setContentText(item?.source?.name)
			.setOngoing(playbackState == PlaybackState.PLAYING)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
		val toggleAction = NotificationCompat.Action(
			if (isVideoPlaying()) R.drawable.ic_action_pause else R.drawable.ic_play,
			getString(R.string.play),
			pendingServiceAction(ACTION_TOGGLE),
		)
		builder.addAction(toggleAction)
		builder.addAction(
			NotificationCompat.Action(R.drawable.ic_clear_all, getString(R.string.clear), pendingServiceAction(ACTION_STOP)),
		)
		item?.let {
			val contentIntent = BooruPlayerActivity.newIntent(this, it)
			builder.setContentIntent(
				PendingIntent.getActivity(
					this, 0, contentIntent,
					PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
				),
			)
		}
		return builder.build()
	}

	private fun pendingServiceAction(action: String): PendingIntent {
		val intent = Intent(this, BooruMediaService::class.java).setAction(action)
		return PendingIntent.getService(
			this, action.hashCode(), intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
	}

	private fun createNotificationChannel() {
		NotificationManagerCompat.from(this).createNotificationChannel(
			NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(getString(R.string.media_player_settings))
				.setShowBadge(false)
				.build(),
		)
	}

	// endregion

	companion object {

		const val CHANNEL_ID = "booru_media"
		private const val NOTIFICATION_ID = 7401
		const val ACTION_TOGGLE = "org.wastaken.kotatsu.api21.booru.media.TOGGLE"
		const val ACTION_NEXT = "org.wastaken.kotatsu.api21.booru.media.NEXT"
		const val ACTION_PREV = "org.wastaken.kotatsu.api21.booru.media.PREV"
		const val ACTION_STOP = "org.wastaken.kotatsu.api21.booru.media.STOP"

		/** Covers slow booru CDNs + moov-at-end MP4 probes through the proxy. */
		private const val PREPARE_TIMEOUT_MS = 30_000L

		/** Directory (inside cacheDir) holding the transient stream .part files. */
		private const val DIR_STREAM_PARTS = "stream_parts"

		/** Rotation-survival cache ceiling for GIF payloads (Movie owns its own copy). */
		private const val GIF_CACHE_MAX_BYTES = 8 * 1024 * 1024
		fun start(context: Context) {
			ContextCompat.startForegroundService(context, Intent(context, BooruMediaService::class.java))
		}
	}

	private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
		enqueue(object : Callback {
			override fun onResponse(call: Call, response: Response) {
				cont.resume(response)
			}

			override fun onFailure(call: Call, e: IOException) {
				if (cont.isCancelled) return
				cont.resumeWithException(e)
			}
		})
		cont.invokeOnCancellation { cancel() }
	}
}
