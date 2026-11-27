package org.wastaken.kotatsu.api21.reader.ui.media

import android.content.Context
import android.util.LruCache
import dagger.hilt.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.wastaken.kotatsu.api21.booru.media.BooruStreamHeaders
import org.wastaken.kotatsu.api21.core.util.ext.ensureSuccess
import org.wastaken.kotatsu.api21.reader.ui.pager.ReaderPage

/**
 * Network evidence gatherer for [MediaSniffer] (TSD-BOORU-MMBD-001 §3.1).
 *
 * Runs ONLY for pages whose URL classification was inconclusive (no extension,
 * no query hint — signed/extension-less CDN links) and only for media-enabled
 * booru sources; everything else keeps the classic image pipeline untouched.
 *
 * Probe recipe: HEAD first (cheap, no body); when the Content-Type is missing
 * or generic (application/octet-stream etc.) a Range: bytes=0-31 GET fetches
 * the magic bytes. Both requests carry the full per-source header set
 * ([BooruStreamHeaders]) and ride the manga OkHttp stack (cookies, Cloudflare,
 * proxy tiers). Verdicts are cached per URL so a rebind/scroll-back never
 * probes twice. A failed probe yields IMAGE (classic behavior), never a crash.
 */
object ReaderMediaProbe {

	private const val PROBE_BYTE_COUNT = 32

	/** URL-keyed verdicts; a page's type cannot change mid-session. */
	private val verdictCache = LruCache<String, ReaderMediaType>(128)

	/** @return null when the page is not eligible for probing (caller treats as "no verdict"). */
	suspend fun probe(context: Context, page: ReaderPage): ReaderMediaType? {
		if (!page.source.isBooruSource()) return null
		verdictCache.get(page.url)?.let { return it }
		val appContext = context.applicationContext
		val entryPoint = EntryPointAccessors.fromApplication(appContext, ReaderMediaEntryPoint::class.java)
		if (!page.source.isMediaPlayerEnabled(entryPoint.settings())) return null
		val verdict = withContext(Dispatchers.IO) {
			runCatching {
				probeInternal(entryPoint, page.source, page.url)
			}.getOrDefault(ReaderMediaType.IMAGE)
		}
		verdictCache.put(page.url, verdict)
		return verdict
	}

	private fun probeInternal(
		entryPoint: ReaderMediaEntryPoint,
		source: MangaSource,
		url: String,
	): ReaderMediaType {
		val headers = BooruStreamHeaders.forStream(
			entryPoint.mangaRepositoryFactory(),
			entryPoint.mangaLoaderContext(),
			source,
			url,
		)
		val headersBuilder = okhttp3.Headers.Builder()
		for ((k, v) in headers) headersBuilder.add(k, v)
		val sharedHeaders = headersBuilder.build()
		val client = entryPoint.okHttpClient()

		// stage A: HEAD — most CDNs answer with the authoritative Content-Type
		val headContentType = runCatching {
			val request = Request.Builder()
				.url(url)
				.head()
				.headers(sharedHeaders)
				.tag(MangaSource::class.java, source)
				.build()
			client.newCall(request).execute().use { response ->
				if (response.isSuccessful) response.header("Content-Type") else null
			}
		}.getOrNull()
		if (isDecisiveMime(headContentType)) {
			return MediaSniffer.sniffType(ByteArray(0), headContentType, url)
		}

		// stage 2/3 need bytes: tiny ranged GET, first PROBE_BYTE_COUNT bytes only
		val request = Request.Builder()
			.url(url)
			.get()
			.headers(sharedHeaders)
			.header("Range", "bytes=0-${PROBE_BYTE_COUNT - 1}")
			.tag(MangaSource::class.java, source)
			.build()
		client.newCall(request).execute().use { response ->
			response.ensureSuccess()
			val body = response.body ?: return MediaSniffer.sniffType(
				ByteArray(0),
				response.header("Content-Type"),
				url,
			)
			val bytes = body.byteStream().use { stream ->
				val buffer = ByteArray(PROBE_BYTE_COUNT)
				var read = 0
				while (read < buffer.size) {
					val n = stream.read(buffer, read, buffer.size - read)
					if (n < 0) break
					read += n
				}
				if (read == buffer.size) buffer else buffer.copyOf(read)
			}
			return MediaSniffer.sniffType(bytes, response.header("Content-Type") ?: headContentType, url)
		}
	}

	/** generic/unknown MIME types must fall through to the byte stage. */
	private fun isDecisiveMime(contentType: String?): Boolean {
		val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
		return mime.startsWith("video/") || mime.startsWith("image/")
	}
}
