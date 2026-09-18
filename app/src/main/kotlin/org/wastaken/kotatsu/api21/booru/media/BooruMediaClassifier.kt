package org.wastaken.kotatsu.api21.booru.media

import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Pure, network-free classification of a booru grid tile:
 * (1) URL-file extension of the post file/cover wins when present
 * (.gif, .mp4/.webm/.gifv), (2) otherwise well-known booru tags
 * ("video", "webm", "mp4", "animated") mark the tile.
 *
 * The authoritative check is still the resolved page URL at play time —
 * this only drives the grid badge and the tap-action routing.
 */
object BooruMediaClassifier {

	private val VIDEO_TAGS = setOf("video", "webm", "mp4", "sound", "has_audio")

	fun classify(coverUrl: String?, tags: Collection<String>): BooruMediaType? {
		val ext = coverUrl.fileExtension()
		return when {
			ext == "gif" -> BooruMediaType.GIF
			ext == "mp4" || ext == "webm" || ext == "gifv" -> BooruMediaType.VIDEO
			ext.isNotEmpty() -> null // jpg/png/... strong static signal
			tags.any { it.lowercase() in VIDEO_TAGS } -> BooruMediaType.VIDEO
			tags.any { it.equals("animated", ignoreCase = true) } -> BooruMediaType.GIF
			else -> null
		}
	}

	fun classify(manga: Manga): BooruMediaType? {
		return classify(manga.publicUrl.ifEmpty { manga.url }, manga.tags.map { it.title })
	}

	private fun String?.fileExtension(): String {
		this ?: return ""
		val clean = substringBefore('#').substringBefore('?')
		return clean.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: ""
	}
}
