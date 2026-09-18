package org.wastaken.kotatsu.api21.list.ui.model

import org.koitharu.kotatsu.parsers.model.Manga
import org.wastaken.kotatsu.api21.booru.media.BooruMediaClassifier
import org.wastaken.kotatsu.api21.booru.media.BooruMediaType
import org.wastaken.kotatsu.api21.core.ui.model.MangaOverride

/**
 * List model for booru sources grid tiles (see BooruGridAdapter).
 * Intentionally a direct subclass of [MangaListModel] (not of MangaGridModel):
 * adapter delegates are matched by class, and this class must never be handled
 * by the standard manga tile delegates.
 *
 * [mediaType] is a pure on-device heuristic (BooruMediaClassifier: extension
 * first, well-known booru tags second); null = static image / unknown. It only
 * drives the badge + tap routing; the resolved page URL stays authoritative.
 */
data class BooruGridModel(
	override val manga: Manga,
	override val override: MangaOverride?,
	val mediaType: BooruMediaType? = BooruMediaClassifier.classify(manga),
) : MangaListModel() {

	override val counter: Int
		get() = 0
}
