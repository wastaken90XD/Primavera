package org.wastaken.kotatsu.api21.booru.media

import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.wastaken.kotatsu.api21.core.parser.MangaRepository
import org.wastaken.kotatsu.api21.core.parser.ParserMangaRepository
import java.net.IDN

/**
 * Header set for booru media streams, used by BOTH the service-side
 * VideoStreamProxy and the GIF byte loader: source-specific request headers
 * first (cookie per source), a default User-Agent, and a Referer derived
 * from the parser domain. Same recipe the reader video overlay uses.
 */
object BooruStreamHeaders {

	private const val HEADER_USER_AGENT = "User-Agent"
	private const val HEADER_REFERER = "Referer"

	fun forStream(
		repositoryFactory: MangaRepository.Factory,
		loaderContext: MangaLoaderContext,
		source: MangaSource,
		url: String,
	): Map<String, String> {
		val result = LinkedHashMap<String, String>()
		val repository = runCatching {
			repositoryFactory.create(source) as? ParserMangaRepository
		}.getOrNull()
		if (repository != null) {
			val headers = repository.getRequestHeaders()
			for (name in headers.names()) {
				headers[name]?.let { value -> result.putIfAbsent(name, value) }
			}
		}
		if (result.none { it.key.equals(HEADER_USER_AGENT, ignoreCase = true) }) {
			result[HEADER_USER_AGENT] = loaderContext.getDefaultUserAgent()
		}
		if (result.none { it.key.equals(HEADER_REFERER, ignoreCase = true) }) {
			val domain = repository?.domain ?: runCatching { java.net.URL(url).host }.getOrNull()
			if (!domain.isNullOrEmpty()) {
				result[HEADER_REFERER] = "https://${IDN.toASCII(domain)}/"
			}
		}
		return result
	}
}
