package org.wastaken.kotatsu.api21.booru.media

import org.koitharu.kotatsu.parsers.model.Manga
import org.wastaken.kotatsu.api21.core.parser.MangaRepository
import org.wastaken.kotatsu.api21.reader.ui.media.looksLikeGif
import org.wastaken.kotatsu.api21.reader.ui.media.looksLikeVideo
import org.wastaken.kotatsu.api21.reader.ui.media.tagsIndicateGif
import org.wastaken.kotatsu.api21.reader.ui.media.tagsIndicateVideo
import org.wastaken.kotatsu.api21.reader.ui.media.titleLooksLikeGif
import org.wastaken.kotatsu.api21.reader.ui.media.isBooruSource
import org.wastaken.kotatsu.api21.reader.ui.media.titleLooksLikeVideo
import org.wastaken.kotatsu.api21.reader.ui.media.urlFileName

/**
 * One network-level resolution step shared by every navigation entry point:
 * given a post (manga), fetch details + first page and build the queue
 * item for the media player. Returns null for static posts and on any failure
 * (callers fall back to the original navigation target).
 *
 * Classification order: resolved file URL (extension, query hint, filename
 * substring) -> post tags (booru parsers expose slugs as tag keys) -> post
 * title. Posts whose CDN URL carries no extension (extension-less/signed
 * links) used to fall through to the reader and never play; the tag/title
 * fallbacks route them to the player instead.
 */
object BooruMediaResolver {

	suspend fun resolve(factory: MangaRepository.Factory, manga: Manga): BooruMediaItem? {
		val repository = factory.create(manga.source)
		val details = if (manga.chapters.isNullOrEmpty()) repository.getDetails(manga) else manga
		val chapter = details.chapters?.firstOrNull() ?: return null
		val pages = repository.getPages(chapter)
		val page = pages.firstOrNull() ?: return null
		val tagKeys = details.tags.map { it.key }
		val postTitle = details.title.ifEmpty { manga.title }
		// tag/title heuristics are only trustworthy on native boorus; an
		// enabled non-booru source classifies strictly by its file URL
		val isBooruNative = manga.source.isBooruSource()
		val type = when {
			page.url.looksLikeGif() -> BooruMediaType.GIF
			page.url.looksLikeVideo() -> BooruMediaType.VIDEO
			isBooruNative && tagKeys.tagsIndicateVideo() -> BooruMediaType.VIDEO
			isBooruNative && tagKeys.tagsIndicateGif() -> BooruMediaType.GIF
			isBooruNative && postTitle.titleLooksLikeVideo() -> BooruMediaType.VIDEO
			isBooruNative && postTitle.titleLooksLikeGif() -> BooruMediaType.GIF
			else -> return null
		}
		return BooruMediaItem(
			id = BooruMediaItem.uidOf(page.url),
			url = page.url,
			source = manga.source,
			title = manga.title.ifEmpty { page.url.urlFileName() },
			thumbnailUrl = manga.publicUrl.ifEmpty { null },
			mediaType = type,
		)
	}
}
