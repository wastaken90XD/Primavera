package org.wastaken.kotatsu.api21.booru.media

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.max

/**
 * Cheap dependency-free blur for booru grid thumbnails ("Blur thumbnails"
 * setting, chained onto the EXISTING Coil request — no second image load).
 *
 * Implementation note: iterative downscale pairs smooth high frequencies;
 * on the small cell-size bitmaps used in the grid this looks like a proper
 * Gaussian blur without RenderScript (unavailable/deprecated) or a native lib.
 *
 * @param intensity 1..25 (settings slider), mapped to downscale passes.
 */
class BlurTransformation(
	private val intensity: Int = 10,
) : Transformation() {

	override val cacheKey: String = "${javaClass.name}-$intensity"

	override suspend fun transform(input: Bitmap, size: Size): Bitmap {
		val factor = (intensity.coerceIn(1, 25) / 8f) + 1f // 1.125 .. 4.125
		var result = input
		var w = input.width
		var h = input.height
		// each pass quarters the work; small thumbs end tiny, big ones get
		// more passes, so 25 feels strong on every tile size
		repeat(2) {
			w = max(1, (w / factor).toInt())
			h = max(1, (h / factor).toInt())
			val small = Bitmap.createScaledBitmap(result, w, h, true)
			if (result !== input) result.recycle()
			result = small
		}
		val upscaled = Bitmap.createScaledBitmap(result, input.width, input.height, true)
		if (result !== input) result.recycle()
		return upscaled
	}
}
