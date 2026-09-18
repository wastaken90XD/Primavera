package org.wastaken.kotatsu.api21.booru.media.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * GIF renderer with a manually driven Movie timeline (API 21-safe):
 * continuous playback is a choreographed time progression, frame-step buttons
 * walk the timeline by a fixed quantum ([FRAME_STEP_MS]), and "save frame"
 * re-renders the current timeline point into a Bitmap.
 *
 * Movie exposes no frame index on this API level, only duration()/setTime(),
 * so stepping is time-based; the quantum is chosen below the typical GIF
 * frame delay so no visual frame is ever skipped entirely.
 */
class StepMovieView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

	interface Listener {
		fun onTimelineChanged(positionMs: Int, durationMs: Int)
		fun onLoopCompleted()
	}

	var listener: Listener? = null

	private var movie: Movie? = null
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private var positionMs = 0
	private var durationMs = 0
	private var loopEnabled = true
	private var playing = false
	private var lastTick = 0L

	private val tickRunnable = object : Runnable {
		override fun run() {
			val m = movie
			if (!playing || m == null) return
			val now = android.os.SystemClock.uptimeMillis()
			val delta = (now - lastTick).toInt().coerceAtLeast(0)
			lastTick = now
			positionMs += delta
			if (durationMs > 0 && positionMs >= durationMs) {
				if (loopEnabled) {
					positionMs %= durationMs
					listener?.onLoopCompleted()
				} else {
					positionMs = durationMs
					playing = false
					m.setTime(positionMs)
					invalidate()
					listener?.onTimelineChanged(positionMs, durationMs)
					return
				}
			}
			m.setTime(positionMs)
			invalidate()
			listener?.onTimelineChanged(positionMs, durationMs)
			postOnAnimation(this)
		}
	}

	fun setMovie(newMovie: Movie?, loop: Boolean) {
		stop()
		movie = newMovie
		durationMs = (newMovie?.duration() ?: 0).coerceAtLeast(1)
		positionMs = 0
		loopEnabled = loop
		newMovie?.setTime(0)
		requestLayout()
		invalidate()
		listener?.onTimelineChanged(0, durationMs)
	}

	fun release() {
		stop()
		movie = null
		invalidate()
	}

	fun isPlaying() = playing

	fun hasMovie() = movie != null

	fun play() {
		if (movie == null || playing) return
		if (durationMs > 0 && positionMs >= durationMs) {
			positionMs = 0 // replay-from-end
		}
		playing = true
		lastTick = android.os.SystemClock.uptimeMillis()
		postOnAnimation(tickRunnable)
	}

	fun stop() {
		playing = false
		removeCallbacks(tickRunnable)
	}

	fun setLoop(loop: Boolean) {
		loopEnabled = loop
	}

	/** Steps the timeline while paused (pauses playback if it was running). */
	fun step(deltaMs: Int) {
		val m = movie ?: return
		stop()
		positionMs = if (durationMs > 0) {
			(positionMs + deltaMs).coerceIn(0, durationMs)
		} else {
			(positionMs + deltaMs).coerceAtLeast(0)
		}
		m.setTime(positionMs)
		invalidate()
		listener?.onTimelineChanged(positionMs, durationMs)
	}

	fun stepForwardFrame() = step(FRAME_STEP_MS)

	fun stepBackFrame() = step(-FRAME_STEP_MS)

	/** Current timeline point rendered offscreen for "save frame as PNG". */
	fun renderToBitmap(): Bitmap? {
		val m = movie ?: return null
		val w = if (m.width() > 0) m.width() else width
		val h = if (m.height() > 0) m.height() else height
		if (w <= 0 || h <= 0) return null
		val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		m.setTime(positionMs)
		m.draw(canvas, 0f, 0f, paint)
		return bitmap
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val m = movie
		if (m == null) {
			super.onMeasure(widthMeasureSpec, heightMeasureSpec)
			return
		}
		val mw = m.width().coerceAtLeast(1)
		val mh = m.height().coerceAtLeast(1)
		val w = resolveSize(mw, widthMeasureSpec)
		val h = resolveSize(mh, heightMeasureSpec)
		setMeasuredDimension(w, h)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val m = movie ?: return
		val mw = m.width().coerceAtLeast(1)
		val mh = m.height().coerceAtLeast(1)
		val scale = kotlin.math.min(width / mw.toFloat(), height / mh.toFloat())
		canvas.save()
		canvas.translate((width - mw * scale) / 2f, (height - mh * scale) / 2f)
		canvas.scale(scale, scale)
		m.draw(canvas, 0f, 0f, paint)
		canvas.restore()
	}

	override fun onDetachedFromWindow() {
		stop()
		super.onDetachedFromWindow()
	}

	companion object {
		/** One timeline quantum per step; below the common 50...100 ms GIF delays. */
		const val FRAME_STEP_MS = 33
	}
}
