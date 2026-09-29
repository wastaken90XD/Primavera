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
 * engine: the URL handed here is the VideoStreamProxy loopback carrying the
 * .part spool, so source headers/cookies ride the OkHttp upstream exactly
 * like before. Direct-URL streaming without the loopback is the ExoPlayer
 * engine's domain (see BooruExoPlayer - SimpleCache spans replace .parts).
 */
class BooruVlcPlayer(context: Context) : BooruEnginePlayer {

	private val libVlc = LibVLC(context.applicationContext, VLC_OPTIONS)
	private val player = MediaPlayer(libVlc)
	private var viewsAttached = false

	override var callback: BooruEnginePlayer.Callback? = null

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
	override fun setRenderTarget(surfaceTexture: SurfaceTexture?, width: Int, height: Int) {
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

	override fun stopPlayback() {
		runCatching { player.stop() }
	}

	/**
	 * Open + start playback. Hardware decoding on with software fallback
	 * (setHWDecoderEnabled(true, false)); custom headers are passed straight
	 * to libVLC as :http-header media options (unused on the proxy loopback).
	 * The instance is reusable: vlc-android keeps ONE MediaPlayer per service,
	 * only the Media changes per item (libvlc_new per item is native-heap churn).
	 */
	override fun play(url: String, headers: Map<String, String> = emptyMap()) {
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

	override fun pause() {
		player.pause()
	}

	/** play() from a paused instance resumes - same call toggles both ways. */
	override fun resume() {
		player.play()
	}

	override fun seekTo(positionMs: Long) {
		// setTime() returns long (not a beans setter) - no Kotlin property write
		player.setTime(positionMs)
	}

	/** Playback rate works on every API level (no MediaPlayer API-23 gate). */
	override fun setSpeed(rate: Float) {
		// setRate() returns int status - call it as a method, not a property
		player.setRate(rate)
	}

	override val time: Long
		get() = player.time

	override val length: Long
		get() = player.length

	override val isPlaying: Boolean
		get() = player.isPlaying

	override fun release() {
		callback = null
		player.setEventListener(null)
		setRenderTarget(null, 0, 0)
		runCatching { player.stop() }
		runCatching { player.release() }
		runCatching { libVlc.release() }
	}

	companion object {

		/**
		 * VLC input cache (ms). Raised from 800 to 2500: the loopback proxy
		 * answers from disk parts at ~zero latency, so deep VLC-side buffering
		 * costs nothing and it is THE documented fix for AudioTrack underrun
		 * ("audio pops, stops, plays fast to catch up" - VLC's own Android
		 * docs recommend raising network caching on slow links, and Android's
		 * AudioTrack reference attributes those glitches to shallow buffers).
		 * Buffered bytes = bitrate x cache time: 2500ms x 4Mbps ~= 1.3MB,
		 * negligible even on this 1.3GB tablet (and the proxy itself only
		 * holds pump/socket scratch in RAM nowadays).
		 */
		const val NETWORK_CACHING_MS = 2500

		/**
		 * vlc-android's network defaults: generous caching so slow booru CDNs
		 * (through the loopback proxy) don't starve the demuxer, reconnect on
		 * flaky links, no late-frame dropping / frame skipping on weak CPUs.
		 * --no-audio-time-stretch: vlcdocs flag time stretching as a known
		 * audio-lag source on slow devices (and it is the code path that makes
		 * audio "speed up to catch up" - skip the whole catch-up mechanism).
		 */
		private val VLC_OPTIONS = arrayListOf(
			"--no-drop-late-frames",
			"--no-skip-frames",
			"--rtsp-tcp",
			"--network-caching=$NETWORK_CACHING_MS",
			"--no-audio-time-stretch",
			"--http-reconnect",
			"--http-continuous",
		)
	}
}
