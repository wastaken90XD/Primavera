package org.wastaken.kotatsu.api21.booru.media.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Container that sizes itself to a fixed aspect ratio when one is set
 * (16:9 / 4:3 cycle of the player), or fills available space otherwise
 * (fit/fill/crop are handled by the child's own scale behaviour).
 */
class MediaSurfaceFrame @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

	/** 0 = free-form fill; otherwise width/height ratio to enforce. */
	var fixedAspectRatio: Float = 0f
		set(value) {
			field = value
			requestLayout()
		}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val ratio = fixedAspectRatio
		if (ratio <= 0f) {
			super.onMeasure(widthMeasureSpec, heightMeasureSpec)
			return
		}
		val w = MeasureSpec.getSize(widthMeasureSpec)
		val h = MeasureSpec.getSize(heightMeasureSpec)
		var targetW = w
		var targetH = (w / ratio).toInt()
		if (targetH > h) {
			targetH = h
			targetW = (h * ratio).toInt()
		}
		super.onMeasure(
			MeasureSpec.makeMeasureSpec(targetW, MeasureSpec.EXACTLY),
			MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY),
		)
	}
}
