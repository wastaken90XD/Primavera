package org.wastaken.kotatsu.api21.booru.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.graphics.SurfaceTexture
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import okhttp3.OkHttpClient
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import java.io.File

/**
 * ExoPlayer-backed video engine for the booru media player, replacing the
 * hand-rolled loopback proxy + spool design. Why this beats patching that
 * design further (all researched first):
 *
 *  - Data path: `CacheDataSource` (androidx.media3) writes every streamed
 *    byte into `SimpleCache`'s span files on disk while serving the player -
 *    exactly the "store the stream in parts on storage" requirement, but
 *    with Google's index/locking logic (span key + offset in a SQLite index)
 *    instead of our byte-arithmetic. Memories of the repeating-audio-snippet
 *    bug class can't re-appear: spans are immutable, keyed, refcounted.
 *  - Network: `OkHttpDataSource` over the APP's OkHttpClient - cookies,
 *    Cloudflare clearance, proxy, timeouts come for free, no manual header
 *    plumbing through a proxy. Per-media headers are set on the factory;
 *    media3 documents they apply to future requests of created sources.
 *  - Buffering: DefaultLoadControl (ExoPlayer's decades-tuned
 *    bufferForPlayback/min/max thresholds; Akamai's study of its buffer
 *    strategy guided NOT hand-tuning it on low-end devices). Audio underruns
 *    at the output stage are ExoPlayer's own battle-tested domain.
 *  - Memory: ExoPlayer buffers are bounded (DefaultLoadControl defaults) and
 *    the byte content lives in the disk cache, LRU-evicted at the user's
 *    part-size x part-count budget from Settings.
 *
 * Hygiene contract (unchanged from the .part design): cached spans of the
 * previous item are removed on item switch; the WHOLE cache is released and
 * deleted (SimpleCache.delete, worker thread per its @WorkerThread docs, and
 * never while an instance holds the dir) when the engine is released, so no
 * cache byte survives a real player stop.
 *
 * Public surface mirrors [BooruVlcPlayer] one-to-one so the service can hold
 * either engine behind the same call sites.
 */
@OptIn(UnstableApi::class)
class BooruExoPlayer(
	private val context: Context,
	okHttpClient: OkHttpClient,
	settings: AppSettings,
) : BooruEnginePlayer {

	override var callback: BooruEnginePlayer.Callback? = null

	private val httpFactory = OkHttpDataSource.Factory(okHttpClient)
	private var cache: SimpleCache? = null
	private val cacheLock = Any()
	private var currentCacheKey: String? = null

	private val cacheBudgetBytes = settings.booruStreamPartSizeMb.toLong() * settings.booruStreamPartCount * 1024L * 1024L

	private var player: ExoPlayer? = null
	private var renderSurface: Surface? = null
	private var renderTexture: SurfaceTexture? = null
	private var speed: Float = 1f

	private fun buildPlayer(): ExoPlayer {
		val p = ExoPlayer.Builder(context).build()
		p.addListener(playerListener)
		p.playWhenReady = false
		return p
	}

	private fun cacheFactory(): DataSource.Factory {
		val c = synchronized(cacheLock) {
			cache ?: run {
				val budget = cacheBudgetBytes
				SimpleCache(
					File(context.cacheDir, CACHE_DIR),
					LeastRecentlyUsedCacheEvictor(budget),
					StandaloneDatabaseProvider(context),
				).also { cache = it }
			}
		}
		return CacheDataSource.Factory()
			.setCache(c)
			.setUpstreamDataSourceFactory(httpFactory)
			.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)
	}

	override fun play(url: String, headers: Map<String, String>) {
		// documented behavior: properties set on the factory apply to requests
		// of sources created from now on (ExoPlayer issue #10163)
		httpFactory.setDefaultRequestProperties(headers)
		val previous = currentCacheKey
		if (previous != null && previous != url) {
			// hygiene: the outgoing item's spans go at the switch, not at LRU time
			synchronized(cacheLock) {
				runCatching { cache?.removeResource(previous) }
			}
		}
		currentCacheKey = url
		val p = player ?: buildPlayer().also { player = it }
		renderSurface?.let { surface -> p.setVideoSurface(surface) }
		val source = DefaultMediaSourceFactory(cacheFactory())
			.createMediaSource(MediaItem.fromUri(Uri.parse(url)))
		p.setMediaSource(source)
		p.prepare()
		p.setPlaybackSpeed(speed)
		p.playWhenReady = true
		startTicker()
	}

	override fun pause() {
		player?.pause()
	}

	override fun resume() {
		player?.play()
	}

	override fun seekTo(positionMs: Long) {
		player?.seekTo(positionMs.coerceAtLeast(0L))
	}

	override fun setSpeed(rate: Float) {
		speed = rate
		player?.setPlaybackSpeed(rate)
	}

	override val time: Long
		get() = player?.currentPosition ?: 0L

	override val length: Long
		get() = player?.duration?.takeIf { it >= 0L } ?: 0L

	override val isPlaying: Boolean
		get() = player?.isPlaying == true

	override fun stopPlayback() {
		stopTicker()
		runCatching { player?.stop() }
		runCatching { player?.clearMediaItems() }
	}

	override fun release() {
		stopTicker()
		setRenderTarget(null, 0, 0)
		runCatching { player?.release() }
		player = null
		synchronized(cacheLock) {
			val c = cache ?: return
			cache = null
			runCatching { c.release() }
			// SimpleCache.delete is @WorkerThread and only safe once no
			// instance uses the directory: run it off the main thread
			val dir = File(context.cacheDir, CACHE_DIR)
			val db = StandaloneDatabaseProvider(context)
			Thread {
				runCatching { SimpleCache.delete(dir, db) }
			}.apply { isDaemon = true }.start()
		}
		currentCacheKey = null
	}

	override fun setRenderTarget(surfaceTexture: SurfaceTexture?, width: Int, height: Int) {
		if (renderTexture === surfaceTexture) {
			return
		}
		val old = renderSurface
		renderSurface = null
		renderTexture = surfaceTexture
		if (surfaceTexture != null) {
			renderSurface = Surface(surfaceTexture)
			player?.setVideoSurface(renderSurface)
		} else {
			player?.clearVideoSurface()
		}
		old?.release()
	}

	private val playerListener = object : Player.Listener {

		override fun onPlaybackStateChanged(playbackState: Int) {
			val cb = callback ?: return
			when (playbackState) {
				Player.STATE_READY -> {
					val size: VideoSize = player?.videoSize ?: VideoSize.UNKNOWN
					cb.onPlaying(size.width, size.height)
				}
				Player.STATE_BUFFERING -> {
					cb.onBuffering((player?.bufferedPercentage ?: 0) / 100f)
				}
				Player.STATE_ENDED -> {
					cb.onEndReached()
				}
				Player.STATE_IDLE -> Unit
			}
		}

		override fun onPlayerError(error: PlaybackException) {
			callback?.onEncounteredError()
		}
	}

	// position ticker: the service polls position itself, the callback hook
	// only exists for VLC parity (time updates while playing)
	private var tickerHandler: Handler? = null
	private var ticker: Runnable? = null

	private fun startTicker() {
		if (ticker != null) {
			return
		}
		val handler = tickerHandler ?: Handler(Looper.getMainLooper()).also { tickerHandler = it }
		val tick = object : Runnable {
			override fun run() {
				callback?.onTimeChanged(time)
				handler.postDelayed(this, TICK_MS)
			}
		}
		ticker = tick
		handler.postDelayed(tick, TICK_MS)
	}

	private fun stopTicker() {
		ticker?.let { tickerHandler?.removeCallbacks(it) }
		ticker = null
	}

	private companion object {

		private const val CACHE_DIR = "exo_stream"
		private const val TICK_MS = 500L
	}
}
