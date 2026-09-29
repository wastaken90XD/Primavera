package org.wastaken.kotatsu.api21.list.ui.adapter

import android.content.res.Resources
import org.wastaken.kotatsu.api21.list.ui.size.StaticItemSizeResolver

/**
 * Square-thumbnail grid adapter for booru sources. Shares every list plumbing delegate
 * (loading/error/empty states, footers, quick filter) with [MangaListAdapter] and only
 * differs by the [ListItemType.BOORU_GRID] tile delegate. Emitted models
 * ([org.wastaken.kotatsu.api21.list.ui.model.BooruGridModel]) are never produced for
 * non-booru sources, so the standard manga grids are unaffected. The column count
 * ([spanCount]) is user-configurable (booru_grid_columns setting, default 3) and is
 * only applied here; standard grids are untouched. Isolated: deleting this class, its
 * delegate and the RemoteListFragment/RemoteListViewModel branches removes the feature
 * completely.
 *
 * [inlineGifRegistry] holds the explicit per-card GIF "Load GIF" state across
 * rebinding; the fragment resolves the file URL and calls
 * [onInlineGifResolved] to swap the still cover for the animated file.
 */
class BooruGridAdapter(
	listener: MangaListListener,
	private val spanCount: Int,
	val inlineGifRegistry: BooruInlineGifRegistry = BooruInlineGifRegistry(),
) : MangaListAdapter(
	listener = listener,
	sizeResolver = StaticItemSizeResolver(0), // unused: MANGA_GRID items are never emitted here
) {

	/** 0 = off; chained via BlurTransformation onto each cover request ("Blur thumbnails"). */
	var blurRadius: Int = 0

	init {
		addDelegate(
			ListItemType.BOORU_GRID,
			booruGridItemAD(resolveThumbnailSize(spanCount), inlineGifRegistry, { blurRadius }, listener),
		)
	}

	fun markInlineGifRequested(mangaId: Long) {
		inlineGifRegistry.request(mangaId)
		notifyDataSetChanged()
	}

	fun onInlineGifResolved(mangaId: Long, fileUrl: String) {
		inlineGifRegistry.resolve(mangaId, fileUrl)
		notifyDataSetChanged()
	}

	private companion object {

		/**
		 * Fixed Coil decode size for a square tile. Uses the raw display width bound:
		 * the tile is guaranteed to be narrower than widthPixels / spanCount, so Coil
		 * never decodes full-resolution thumbnails, just a bounded downsample.
		 */
		fun resolveThumbnailSize(spanCount: Int): Int {
			return Resources.getSystem().displayMetrics.widthPixels / spanCount
		}
	}
}
