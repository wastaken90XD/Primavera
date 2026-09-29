package org.wastaken.kotatsu.api21.booru.media

import android.view.SurfaceTexture

/**
 * Common minimal surface of the wrapper video engines ([BooruVlcPlayer],
 * [BooruExoPlayer]) so [BooruMediaService] can hold either behind one field
 * and one call-site set. The system MediaPlayer engine is NOT part of this
 * interface - it stays on its own legacy code path.
 *
 * Contract shared by both engines:
 *  - one instance is reused across queue items (re-sourced per [play]);
 *  - a remembered render target is replayed onto the engine before [play];
 *  - [release] fully tears the engine down (hibernation, error, engine
 *    switch, service death); [stopPlayback] keeps the instance alive.
 */
interface BooruEnginePlayer {

	var callback: Callback?

	fun play(url: String, headers: Map<String, String> = emptyMap())

	fun pause()

	fun resume()

	fun seekTo(positionMs: Long)

	fun setSpeed(rate: Float)

	val time: Long

	val length: Long

	val isPlaying: Boolean

	fun stopPlayback()

	fun release()

	fun setRenderTarget(surfaceTexture: SurfaceTexture?, width: Int, height: Int)

	/**
	 * Engine callbacks (event-style, like libVLC's native events vs the
	 * system player's listener callbacks). Errors are terminal.
	 */
	interface Callback {
		fun onPlaying(videoWidth: Int, videoHeight: Int)
		fun onBuffering(percent: Float)
		fun onEncounteredError()
		fun onEndReached()
		fun onTimeChanged(positionMs: Long)
	}
}
