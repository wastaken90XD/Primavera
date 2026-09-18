package org.wastaken.kotatsu.api21.booru.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * libVLC-backed video engine for the booru media player (BooruVideoEngine.LIBVLC).
 *
 * One instance == one LibVLC + one MediaPlayer; fully owned by BooruMediaService,
 * which creates/releases it per openVideo() run. Everything is main-thread:
 * libVLC dispatches MediaPlayer events on the main looper, as does the service.
 *
 * Render path: raw SurfaceTexture via IVLCVout.setVideoSurface(SurfaceTexture) +
 * setWindowSize() + attachViews(). Deliberately NOT setVideoView(TextureView):
 * that helper installs its own SurfaceTextureListener and clobbers the owning
 * UI's listener (the documented "swallowed callbacks" failure), which would
 * break the API-21 surface-race handling in BooruPlayerActivity and the
 * floating window. With the raw-surface path the existing bindSurface()
 * race fix keeps governing the lifecycle, and swapping the texture (floating
 * window <-> full screen) is a detach/attach cycle that never restarts playback.
 *
 * Network policy mirrors what the service already guarantees for the system
 * engine: the URL handed here is the memory-only VideoStreamProxy loopback, so
 * source headers/cookies ride the OkHttp upstream exactly like before; the
 * [play] headers parameter exists for future direct-URL playback.
 */
class BooruVlcPlayer(context: Context) {

	/**
	 * Engine callbacks mapped from MediaPlayer.Event. libVLC errors are
	 * deterministic: EncounteredError always terminates a broken stream, so no
	 * prepare watchdog is needed (that guard is a system-MediaPlayer quirk).
	 */
	interface Callback {
		fun onPlaying(videoWidth: Int, videoHeight: Int)
		fun onBuffering(percent: Float)
		fun onEncounteredError()
		fun onEndReached()
		fun onTimeChanged(positionMs: Long)
	}

	private val libVlc = LibVLC(context.applicationContext, VLC_OPTIONS)
	private val player = MediaPlayer(libVlc)
	private var viewsAttached = false

	var callback: Callback? = null

	init {
		player.setEventListener { event ->
			val cb = callback ?: return@setEventListener
			when (event.type) {
				MediaPlayer.Event.Playing -> {
					val track = player.currentVideoTrack
					cb.onPlaying(track?.width ?: 0, track?.height ?: 0)
				}
				MediaPlayer.Event.Buffering -> cb.onBuffering(event.buffering)
				MediaPlayer.Event.EncounteredError -> cb.onEncounteredError()
				MediaPlayer.Event.EndReached -> cb.onEndReached()
				MediaPlayer.Event.TimeChanged -> cb.onTimeChanged(event.timeChanged)
				else -> Unit
			}
		}
	}

	/**
	 * Attach (or hot-swap) the render target; null detaches. Swapping targets
	 * while playing continues the stream without restart - this is the
	 * floating <-> full-screen handoff.
	 */
	fun setRenderTarget(surfaceTexture: SurfaceTexture?, width: Int, height: Int) {
		if (viewsAttached) {
			runCatching { player.detachViews() }
			viewsAttached = false
		}
		if (surfaceTexture != null && width > 0 && height > 0) {
			runCatching {
				// explicit method call: Kotlin's property synthesis mangles
				// getVLCVout() to "vlcVout", plain "vout" does not resolve
				val vout = player.getVLCVout()
				vout.setVideoSurface(surfaceTexture)
				vout.setWindowSize(width, height)
				vout.attachViews()
				viewsAttached = true
			}
		}
	}

	fun stopPlayback() {
		runCatching { player.stop() }
	}

	/**
	 * Open + start playback. Hardware decoding on with software fallback
	 * (setHWDecoderEnabled(true, false)); custom headers are passed straight
	 * to libVLC as :http-header media options (unused on the proxy loopback).
	 * The instance is reusable: vlc-android keeps ONE MediaPlayer per service,
	 * only the Media changes per item (libvlc_new per item is native-heap churn).
	 */
	fun play(url: String, headers: Map<String, String> = emptyMap()) {
		// stop() first: deterministic demuxer teardown before reusing the instance
		runCatching { player.stop() }
		val media = Media(libVlc, Uri.parse(url))
		media.setHWDecoderEnabled(true, false)
		media.addOption(":network-caching=$NETWORK_CACHING_MS")
		for ((key, value) in headers) {
			media.addOption(":http-header=$key: $value")
		}
		player.media = media
		// MediaPlayer retains its own reference (documented libVLC contract,
		// same pattern vlc-android uses)
		media.release()
		player.play()
	}

	fun pause() {
		player.pause()
	}

	/** play() from a paused instance resumes - same call toggles both ways. */
	fun resume() {
		player.play()
	}

	fun seekTo(positionMs: Long) {
		// setTime() returns long (not a beans setter) - no Kotlin property write
		player.setTime(positionMs)
	}

	/** Playback rate works on every API level (no MediaPlayer API-23 gate). */
	fun setSpeed(rate: Float) {
		// setRate() returns int status - call it as a method, not a property
		player.setRate(rate)
	}

	val time: Long
		get() = player.time

	val length: Long
		get() = player.length

	val isPlaying: Boolean
		get() = player.isPlaying

	fun release() {
		callback = null
		player.setEventListener(null)
		setRenderTarget(null, 0, 0)
		runCatching { player.stop() }
		runCatching { player.release() }
		runCatching { libVlc.release() }
	}

	companion object {

		/**
		 * vlc-android's network defaults: generous caching so slow booru CDNs
		 * (through the loopback proxy) don't starve the demuxer, reconnect on
		 * flaky links, no late-frame dropping / frame skipping on weak CPUs.
		 */
		private val VLC_OPTIONS = arrayListOf(
			"--no-drop-late-frames",
			"--no-skip-frames",
			"--rtsp-tcp",
			"--network-caching=800",
			"--http-reconnect",
			"--http-continuous",
		)

		/**
		 * 800ms keeps the network buffer at roughly half the 1500ms default:
		 * buffered bytes = bitrate x cache time, and this build targets a
		 * low-RAM 2014 tablet where that reserve is pure OOM margin.
		 */
		const val NETWORK_CACHING_MS = 800
	}
}
