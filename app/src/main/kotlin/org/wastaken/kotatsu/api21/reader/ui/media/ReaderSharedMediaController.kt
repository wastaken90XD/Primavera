package org.wastaken.kotatsu.api21.reader.ui.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import org.wastaken.kotatsu.api21.booru.media.BooruMediaItem
import org.wastaken.kotatsu.api21.booru.media.BooruMediaService

/**
 * The reader-side half of the single-engine policy (TSD-BOORU-MMBD-001 §3.3):
 * every reader video page - paged or webtoon - plays through the ONE
 * [BooruMediaService] engine, so at most one hardware codec instance exists
 * on API 21 regardless of how many video pages are bound.
 *
 * Why a process-scoped singleton instead of constructor plumbing: reader page
 * holders are created by five adapter classes (standard/reversed/vertical/
 * double-page/webtoon) that are not injectable; the service is process-global
 * anyway, so the facade is too. [release] is called from the reader
 * fragments' onDestroyView, which also ends the ad-hoc service session.
 *
 * Ownership discipline: [attach] takes an owner token (the calling
 * VideoPageOverlay). Attaching a new owner pauses + unbinds the previous one
 * (webtoon center-scroll swap, page turn), so playback truly is single. A
 * detach remembers the resume position through the service's hibernation
 * channel, so scrolling back continues where the video left off.
 *
 * Audio: the first attach of a session forces the service muted (TSD §3.4);
 * only the HUD unmute tap lifts it, and muted playback never takes audio
 * focus. Reader videos pause on backgrounding (non-goal §1.3): the overlay's
 * lifecycle observer detaches on onPause.
 */
class ReaderSharedMediaController private constructor(
	private val appContext: Context,
) {

	/** PAGED: overlays self-start on holder resume. WEBTOON: only the center-scroll path attaches. */
	enum class Mode { PAGED, WEBTOON }

	@Volatile
	var mode: Mode = Mode.PAGED

	/** Per-attach callback bundle; the overlay implements it. */
	interface Host {

		fun onReaderPlaybackStateChanged(state: BooruMediaService.PlaybackState)

		fun onReaderBufferingChanged(buffering: Boolean)

		fun onReaderVideoSizeChanged(width: Int, height: Int)

		fun onReaderPlaybackError()

		/**
		 * Fired when THIS host loses the shared engine (page turn, webtoon
		 * center swap, lifecycle pause): [wasPlaying] tells the host whether a
		 * later re-attach should resume on its own.
		 */
		fun onReaderDetached(wasPlaying: Boolean)
	}

	private val mainHandler = Handler(Looper.getMainLooper())

	private var service: BooruMediaService? = null
	private var serviceBound = false
	private var binding = false
	private val pendingOps = ArrayList<(BooruMediaService) -> Unit>()

	private var attachedHost: Host? = null
	private var attachedTexture: TextureView? = null
	private var attachedSurface: Surface? = null
	private var attachedItem: BooruMediaItem? = null
	private var sessionMuteInitialized = false

	private val serviceListener = object : BooruMediaService.Listener {

		override fun onQueueChanged() = Unit

		override fun onPlaybackStateChanged(state: BooruMediaService.PlaybackState, item: BooruMediaItem?) {
			if (item?.id == attachedItem?.id) {
				attachedHost?.onReaderPlaybackStateChanged(state)
			}
		}

		override fun onPlaybackError(item: BooruMediaItem?, what: Int, extra: Int) {
			if (item == null || item.id == attachedItem?.id) {
				attachedHost?.onReaderPlaybackError()
			}
		}
	}

	private val connection = object : ServiceConnection {

		override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
			val svc = (binder as? BooruMediaService.LocalBinder)?.service ?: return
			service = svc
			serviceBound = true
			binding = false
			svc.addListener(serviceListener)
			val ops = pendingOps.toList()
			pendingOps.clear()
			ops.forEach { it(svc) }
		}

		override fun onServiceDisconnected(name: ComponentName?) {
			service = null
			serviceBound = false
		}
	}

	val isAttached: Boolean
		get() = attachedHost != null

	val isMuted: Boolean
		get() = service?.readerMuted ?: true

	fun isAttachedHost(host: Host): Boolean = attachedHost === host

	fun isPlaying(): Boolean = service?.isVideoPlaying() == true

	fun positionMs(): Int = service?.videoPosition() ?: 0

	fun durationMs(): Int = service?.videoDuration() ?: 0

	/**
	 * Attach the host's TextureView and start (or restart) playback of [item]
	 * through the shared engine. Idempotent for the same host+item.
	 */
	fun attach(host: Host, textureView: TextureView, item: BooruMediaItem) {
		if (attachedHost === host && attachedItem?.id == item.id) return
		detachInternal(pause = true)
		attachedHost = host
		attachedTexture = textureView
		attachedItem = item
		withService { svc ->
			if (!sessionMuteInitialized) {
				svc.setReaderMuted(true)
				sessionMuteInitialized = true
			}
			wireBridges(svc, host)
			// surface may race the engine start; the service remembers and
			// replays it, and a late texture swaps in without a restart
			textureView.surfaceTexture?.let { bindSurface(svc, it, textureView.width, textureView.height) }
			svc.playReaderVideo(item)
		}
	}

	/** The host's TextureView produced (or replaced) its SurfaceTexture. */
	fun onSurfaceReady(host: Host, surfaceTexture: SurfaceTexture, width: Int, height: Int) {
		if (attachedHost !== host) return
		withService { svc -> bindSurface(svc, surfaceTexture, width, height) }
	}

	/** The host's surface died (recycle race, TSD §6): unbind BEFORE it releases. */
	fun onSurfaceDestroyed(host: Host) {
		if (attachedHost !== host) return
		withService { svc ->
			svc.bindSurface(null)
			svc.bindSurfaceTexture(null)
		}
		attachedSurface?.release()
		attachedSurface = null
	}

	/** Pause + unbind, remembering the resume point; the session stays alive. */
	fun detach(host: Host) {
		if (attachedHost !== host) return
		detachInternal(pause = true)
	}

	/** Webtoon center-scroll: the new center is not a video page. */
	fun detachAny() {
		if (attachedHost != null) detachInternal(pause = true)
	}

	fun togglePlayPause(host: Host) {
		if (attachedHost !== host) return
		withService { it.togglePlayPause() }
	}

	fun seekTo(host: Host, positionMs: Int) {
		if (attachedHost !== host) return
		withService { it.seekTo(positionMs) }
	}

	fun seekBy(host: Host, deltaMs: Int) {
		if (attachedHost !== host) return
		withService { it.seekBy(deltaMs) }
	}

	fun setSpeed(host: Host, speed: Float) {
		if (attachedHost !== host) return
		withService { it.setSpeed(speed) }
	}

	fun setLooping(host: Host, looping: Boolean) {
		if (attachedHost !== host) return
		withService { it.setVideoLooping(looping) }
	}

	fun setMuted(muted: Boolean) {
		withService { it.setReaderMuted(muted) }
	}

	/**
	 * Fullscreen handoff (TSD §3.4): the ad-hoc item joins the service queue
	 * unmuted WITHOUT re-sourcing; the caller then opens BooruPlayerActivity,
	 * whose playNow() finds the item already current (warm reuse + the
	 * hibernation resume channel carry the position).
	 */
	fun promoteToQueue(host: Host): BooruMediaItem? {
		if (attachedHost !== host) return null
		val svc = service ?: return null
		val item = svc.promoteReaderVideoToQueue() ?: return null
		detachInternal(pause = false)
		return item
	}

	/** Reader teardown: detach everything, end the ad-hoc session, unbind. */
	fun release() {
		detachInternal(pause = false)
		service?.stopReaderVideo()
		service?.removeListener(serviceListener)
		if (serviceBound) {
			runCatching { appContext.unbindService(connection) }
		}
		serviceBound = false
		binding = false
		service = null
		sessionMuteInitialized = false
	}

	private fun bindSurface(svc: BooruMediaService, st: SurfaceTexture, width: Int, height: Int) {
		attachedSurface?.release()
		val surface = Surface(st)
		attachedSurface = surface
		svc.bindSurface(surface, width, height)
		svc.bindSurfaceTexture(st, width, height)
	}

	private fun detachInternal(pause: Boolean) {
		val svc = service
		val host = attachedHost
		val wasPlaying = svc?.isVideoPlaying() == true
		attachedHost = null
		attachedTexture = null
		attachedItem = null
		attachedSurface?.release()
		attachedSurface = null
		if (svc != null) {
			svc.onBufferingChange = null
			svc.onVideoSizeChanged = null
			svc.markAdHocResumePoint()
			svc.bindSurface(null)
			svc.bindSurfaceTexture(null)
			if (pause) svc.pauseReaderVideo()
		}
		host?.onReaderDetached(wasPlaying)
	}

	private fun wireBridges(svc: BooruMediaService, host: Host) {
		svc.onBufferingChange = { buffering ->
			if (attachedHost === host) host.onReaderBufferingChanged(buffering)
		}
		svc.onVideoSizeChanged = { w, h ->
			if (attachedHost === host) host.onReaderVideoSizeChanged(w, h)
		}
	}

	private fun withService(op: (BooruMediaService) -> Unit) {
		val svc = service
		if (svc != null) {
			op(svc)
			return
		}
		pendingOps.add(op)
		if (!binding && !serviceBound) {
			binding = true
			val intent = Intent(appContext, BooruMediaService::class.java)
			runCatching { appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
		}
	}

	companion object {

		@Volatile
		private var instance: ReaderSharedMediaController? = null

		fun get(context: Context): ReaderSharedMediaController {
			instance?.let { return it }
			return synchronized(this) {
				instance ?: ReaderSharedMediaController(context.applicationContext).also { instance = it }
			}
		}

		/** Called by reader fragments in onDestroyView. */
		fun releaseInstance() {
			synchronized(this) {
				instance?.release()
				instance = null
			}
		}
	}
}
