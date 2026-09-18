package org.wastaken.kotatsu.api21.list.ui.adapter

import android.graphics.drawable.ColorDrawable
import coil3.size.Size
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.booru.media.BooruMediaType
import org.wastaken.kotatsu.api21.booru.media.BlurTransformation
import org.wastaken.kotatsu.api21.core.ui.list.AdapterDelegateClickListenerAdapter
import org.wastaken.kotatsu.api21.core.ui.list.OnListItemClickListener
import org.wastaken.kotatsu.api21.core.util.ext.getThemeColor
import org.wastaken.kotatsu.api21.core.util.ext.setTooltipCompat
import org.wastaken.kotatsu.api21.databinding.ItemBooruGridBinding
import org.wastaken.kotatsu.api21.list.ui.model.BooruGridModel
import org.wastaken.kotatsu.api21.list.ui.model.ListModel
import org.wastaken.kotatsu.api21.list.ui.model.MangaListModel
import com.google.android.material.R as materialR

/**
 * Tile delegate for the booru square grid (see BooruGridAdapter).
 * - [imageSize] is a fixed pixel decode size: Coil never decodes full-resolution
 *   thumbnails into memory (critical on low-RAM devices).
 * - Media indicators are bound from the model's pure [BooruGridModel.mediaType]
 *   classification: a "GIF" chip, a centered play button, or nothing.
 * - Inline GIF playback ("GIF tap action" = INLINE) is an adapter-local state
 *   machine resolved by the fragment ([BooruGridAdapter.inlineGifUrl]/
 *   [BooruGridAdapter.markInlineGifResolved]): the card shows "Load GIF",
 *   waits for the explicit tap, then swaps the still cover for the animated file.
 * - Blur ([BlurTransformation]) is chained onto the same Coil request when the
 *   setting is on; no second image load happens.
 */
class BooruInlineGifRegistry {

	/** manga ids whose GIF the user explicitly asked to play inline. */
	private val requested = HashSet<Long>()

	/** resolved file url per manga id (null while a resolve is in flight). */
	private val fileUrls = HashMap<Long, String>()

	fun isRequested(mangaId: Long) = mangaId in requested

	fun isResolved(mangaId: Long) = fileUrls.containsKey(mangaId)

	fun fileUrl(mangaId: Long): String? = fileUrls[mangaId]

	fun request(mangaId: Long) {
		requested.add(mangaId)
	}

	fun resolve(mangaId: Long, url: String) {
		fileUrls[mangaId] = url
	}

	fun clear() {
		requested.clear()
		fileUrls.clear()
	}
}

fun booruGridItemAD(
	imageSize: Int,
	inlineRegistry: BooruInlineGifRegistry,
	blurRadiusProvider: () -> Int,
	clickListener: OnListItemClickListener<MangaListModel>,
) = adapterDelegateViewBinding<BooruGridModel, ListModel, ItemBooruGridBinding>(
	{ inflater, parent -> ItemBooruGridBinding.inflate(inflater, parent, false) },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)

	val placeholder = ColorDrawable(context.getThemeColor(materialR.attr.colorSurfaceContainer))
	binding.imageViewCover.let { iv ->
		iv.exactImageSize = Size(imageSize, imageSize)
		iv.placeholderDrawable = placeholder
		iv.errorDrawable = placeholder
		iv.fallbackDrawable = placeholder
	}

	bind { _ ->
		itemView.setTooltipCompat(item.getSummary(context))
		val inlineUrl = inlineRegistry.fileUrl(item.manga.id)
		val view = binding.imageViewCover
		when {
			inlineUrl != null -> {
				// explicit-load satisfied: swap the still for the animated file
				view.blurRadius = 0
				view.setImageAsync(inlineUrl, item.manga)
			}
			else -> {
				view.blurRadius = blurRadiusProvider()
				view.setImageAsync(item.coverUrl, item.manga)
			}
		}
		when (item.mediaType) {
			BooruMediaType.GIF -> {
				binding.gifBadge.visibility = android.view.View.VISIBLE
				binding.videoIndicator.visibility = android.view.View.GONE
				val showLoadButton = inlineRegistry.isRequested(item.manga.id) && inlineUrl == null
				binding.loadGifButton.visibility = android.view.View.GONE
				binding.gifProgress.visibility = if (showLoadButton) android.view.View.VISIBLE else android.view.View.GONE
			}
			BooruMediaType.VIDEO -> {
				binding.gifBadge.visibility = android.view.View.GONE
				binding.videoIndicator.visibility = android.view.View.VISIBLE
				binding.gifProgress.visibility = android.view.View.GONE
				binding.loadGifButton.visibility = android.view.View.GONE
			}
			else -> {
				binding.gifBadge.visibility = android.view.View.GONE
				binding.videoIndicator.visibility = android.view.View.GONE
				binding.gifProgress.visibility = android.view.View.GONE
				binding.loadGifButton.visibility = android.view.View.GONE
			}
		}
	}
}
