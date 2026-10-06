package org.wastaken.kotatsu.api21.core.network

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.buffer
import okio.sink
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.requireBody
import org.wastaken.kotatsu.api21.core.util.ext.ensureSuccess
import org.wastaken.kotatsu.api21.core.util.ext.writeAllCancellable
import org.wastaken.kotatsu.api21.reader.domain.PageLoader
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads the untouched original bytes of an image directly from the source server,
 * bypassing the image proxy chain (wsrv.nl, worker relay) and the pages disk cache.
 * Source-specific headers (referer, user agent, cookies, Cloudflare handling) are still
 * applied via [PageLoader.createPageRequest] and the [MangaHttpClient] client.
 *
 * Used by the "Save original image" feature; see AppSettings.KEY_PAGES_SAVE_ORIGINAL.
 */
@Singleton
class OriginalImageDownloader @Inject constructor(
	@ApplicationContext private val context: Context,
	@MangaHttpClient private val okHttpClient: OkHttpClient,
) {

	suspend fun download(url: String, source: MangaSource, destination: Uri) = withContext(Dispatchers.IO) {
		val request = PageLoader.createPageRequest(url, source)
		okHttpClient.newCall(request).await().use { response ->
			response.ensureSuccess()
			response.requireBody().use { body ->
				val output = runInterruptible(Dispatchers.IO) {
					context.contentResolver.openOutputStream(destination)
				} ?: throw IOException("Cannot open output stream for $destination")
				output.sink().buffer().use { sink ->
					sink.writeAllCancellable(body.source())
				}
			}
		}
	}
}
