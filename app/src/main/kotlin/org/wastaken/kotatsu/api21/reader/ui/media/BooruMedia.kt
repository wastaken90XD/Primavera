package org.wastaken.kotatsu.api21.reader.ui.media

import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.wastaken.kotatsu.api21.core.model.unwrap
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.reader.ui.pager.ReaderPage

/**
 * Shared media-URL detection for the booru explicit-load features
 * (GIF overlay, video player). Pages from booru sources may point to .gif
 * animations or video files instead of still images.
 */

private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "gifv")
private const val GIF_EXTENSION = "gif"

// booru tag slugs that mark animated/video posts when the file URL carries
// no extension signal (extension-less CDN links, signed URLs, ...)
private val VIDEO_TAGS = setOf("video", "webm", "mp4", "gifv", "animated_gif", "flash")
private val GIF_TAGS = setOf("gif", "animated", "animated_gif")

internal fun String.urlExtension(): String {
	return substringBefore('#').substringBefore('?').substringAfterLast('.', "").lowercase()
}

/** Secondary signal: explicit format hints carried in the query ("?ext=", "?format="). */
internal fun String.queryExtension(): String {
	val query = substringAfter('?', "").substringBefore('#')
	return query.split('&')
		.firstOrNull { it.startsWith("ext=") || it.startsWith("format=") }
		?.substringAfter('=')
		?.lowercase()
		.orEmpty()
}

internal fun String.looksLikeGif(): Boolean {
	if (urlExtension() == GIF_EXTENSION) return true
	if (queryExtension() == GIF_EXTENSION) return true
	// filename substring fallback: extension mid-name ("abc.gif?dl=1" styles)
	return urlFileName().lowercase().contains(".$GIF_EXTENSION")
}

internal fun String.looksLikeVideo(): Boolean {
	if (urlExtension() in VIDEO_EXTENSIONS) return true
	if (queryExtension() in VIDEO_EXTENSIONS) return true
	val filename = urlFileName().lowercase()
	return VIDEO_EXTENSIONS.any { filename.contains(".$it") }
}

internal fun String.looksLikeMedia(): Boolean {
	return looksLikeGif() || looksLikeVideo()
}

/** Tag-based detection — the fallback when the URL carries no signal at all. */
internal fun Collection<String>.tagsIndicateVideo(): Boolean =
	any { it.lowercase() in VIDEO_TAGS }

internal fun Collection<String>.tagsIndicateGif(): Boolean =
	any { it.lowercase() in GIF_TAGS }

/** Title/free-text fallback: any "file.ext" mention in the post title decides. */
internal fun String.titleLooksLikeVideo(): Boolean {
	val text = lowercase()
	return VIDEO_EXTENSIONS.any { text.contains(".$it") }
}

internal fun String.titleLooksLikeGif(): Boolean =
	lowercase().contains(".$GIF_EXTENSION")

/** Best-effort mime type for a video page URL, used for ACTION_VIEW intents. */
internal fun String.videoMimeType(): String {
	return when (urlExtension().lowercase()) {
		"webm" -> "video/webm"
		else -> "video/mp4" // mp4, gifv and unknown extensions
	}
}

/** A playable stream variant (quality level) for a booru video page. */
internal data class StreamVariant(
	val label: String,
	val url: String,
)

/** Display name of the file a page URL points at ("abc123.mp4"), without query/fragment. */
internal fun String.urlFileName(): String {
	return substringBefore('#').substringBefore('?').substringAfterLast('/')
}

/**
 * Stream-quality variants for a booru video page URL.
 *
 * The parser model only exposes the original file URL, but several booru CDN
 * patterns are deterministic enough to derive the quality siblings the source
 * itself publishes; those providers live below. The video overlay automatically
 * shows a quality picker as soon as more than one variant is returned.
 */
internal fun String.videoStreamVariants(): List<StreamVariant> {
	danbooruVideoVariants()?.let { return it }
	return listOf(StreamVariant("Original (auto)", this))
}

private const val DANBOORU_CDN_HOST = "cdn.donmai.us"
private const val DANBOORU_ORIGINAL_SEGMENT = "/original/"
private val DANBOORU_VIDEO_VARIANT_TAGS = listOf("720p", "480p", "360p")

/**
 * Danbooru media assets are published as
 *   https://cdn.donmai.us/original/{h1}/{h2}/{file}.{ext}
 * with server-generated video variants (always H.264 mp4) at
 *   https://cdn.donmai.us/{720p|480p|360p}/{h1}/{h2}/{file}.mp4
 */
private fun String.danbooruVideoVariants(): List<StreamVariant>? {
	val clean = substringBefore('#').substringBefore('?')
	val schemeSep = clean.indexOf("://").takeIf { it > 0 } ?: return null
	val hostEnd = clean.indexOf('/', startIndex = schemeSep + 3).takeIf { it > 0 } ?: return null
	val host = clean.substring(schemeSep + 3, hostEnd)
	if (!host.equals(DANBOORU_CDN_HOST, ignoreCase = true)) {
		return null
	}
	val path = clean.substring(hostEnd) // "/original/xx/yy/file.mp4"
	if (!path.startsWith(DANBOORU_ORIGINAL_SEGMENT) || !looksLikeVideo()) {
		return null
	}
	val baseOrigin = clean.substring(0, hostEnd)
	val suffix = path.substring(DANBOORU_ORIGINAL_SEGMENT.length) // "xx/yy/file.mp4"
	val baseName = suffix.substringBeforeLast('.')
	return buildList {
		add(StreamVariant("Original", clean))
		for (tag in DANBOORU_VIDEO_VARIANT_TAGS) {
			add(StreamVariant(tag, "$baseOrigin/$tag/$baseName.mp4"))
		}
	}
}

/** True only for booru parser sources (native-booru eligibility: tags, defaults). */
internal fun MangaSource.isBooruSource(): Boolean {
	return (unwrap() as? MangaParserSource)?.contentType == ContentType.BOORU
}

/**
 * Whether the built-in media player is switched on for this source.
 * Settings-driven (per-source toggle), replaces the source-type-only gate.
 */
internal fun MangaSource.isMediaPlayerEnabled(settings: AppSettings): Boolean =
	settings.isMediaPlayerEnabledForSource(this)

/** Default value per source: ON for native booru, OFF otherwise. */
internal fun MangaSource.mediaPlayerDefault(): Boolean = isBooruSource()

internal fun MangaPage.isBooruMedia(settings: AppSettings): Boolean {
	if (!source.isMediaPlayerEnabled(settings)) return false
	if (url.looksLikeMedia()) return true
	// no tag fallback at this level: MangaPage carries no tags; the
	// tag/title chain lives in BooruMediaResolver where the post is known
	return false
}

internal fun ReaderPage.isBooruMedia(settings: AppSettings): Boolean {
	if (!source.isMediaPlayerEnabled(settings)) return false
	return url.looksLikeMedia()
}
