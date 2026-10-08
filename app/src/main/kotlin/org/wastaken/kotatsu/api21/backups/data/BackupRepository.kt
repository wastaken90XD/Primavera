package org.wastaken.kotatsu.api21.backups.data

import androidx.collection.ArrayMap
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import org.json.JSONArray
import org.json.JSONObject
import org.wastaken.kotatsu.api21.backups.data.model.BackupIndex
import org.wastaken.kotatsu.api21.backups.data.model.BookmarkBackup
import org.wastaken.kotatsu.api21.backups.data.model.CategoryBackup
import org.wastaken.kotatsu.api21.backups.data.model.FavouriteBackup
import org.wastaken.kotatsu.api21.backups.data.model.HistoryBackup
import org.wastaken.kotatsu.api21.backups.data.model.MangaBackup
import org.wastaken.kotatsu.api21.backups.data.model.SourceBackup
import org.wastaken.kotatsu.api21.backups.domain.BackupSection
import org.wastaken.kotatsu.api21.core.db.MangaDatabase
import org.wastaken.kotatsu.api21.core.model.MangaSource
import org.wastaken.kotatsu.api21.core.network.cookies.MutableCookieJar
import org.wastaken.kotatsu.api21.core.parser.MangaRepository
import org.wastaken.kotatsu.api21.core.parser.ParserMangaRepository
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.util.CompositeResult
import org.wastaken.kotatsu.api21.core.util.json.JsonArrayStreamReader
import org.wastaken.kotatsu.api21.core.util.progress.Progress
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.wastaken.kotatsu.api21.reader.data.TapGridSettings
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@Reusable
class BackupRepository @Inject constructor(
	private val database: MangaDatabase,
	private val settings: AppSettings,
	private val tapGridSettings: TapGridSettings,
	private val cookieJar: MutableCookieJar? = null,
	private val mangaRepositoryFactory: MangaRepository.Factory? = null,
) {

	private val json = Json {
		allowSpecialFloatingPointValues = true
		coerceInputValues = true
		encodeDefaults = true
		ignoreUnknownKeys = true
		useAlternativeNames = false
	}

	suspend fun createBackup(
		output: ZipOutputStream,
		progress: FlowCollector<Progress>?,
	) {
		progress?.emit(Progress.INDETERMINATE)
		var commonProgress = Progress(0, BackupSection.entries.size)
		for (section in BackupSection.entries) {
			when (section) {
				BackupSection.INDEX -> output.writeJsonArray(
					section = BackupSection.INDEX,
					data = flowOf(BackupIndex()),
					serializer = serializer(),
				)

				BackupSection.HISTORY -> output.writeJsonArray(
					section = BackupSection.HISTORY,
					data = database.getHistoryDao().dump().map { HistoryBackup(it) },
					serializer = serializer(),
				)

				BackupSection.CATEGORIES -> output.writeJsonArray(
					section = BackupSection.CATEGORIES,
					data = database.getFavouriteCategoriesDao().findAll().asFlow().map { CategoryBackup(it) },
					serializer = serializer(),
				)

				BackupSection.FAVOURITES -> output.writeJsonArray(
					section = BackupSection.FAVOURITES,
					data = database.getFavouritesDao().dump().map { FavouriteBackup(it) },
					serializer = serializer(),
				)

				BackupSection.SETTINGS -> output.writeString(
					section = BackupSection.SETTINGS,
					data = dumpSettings(),
				)

				BackupSection.SETTINGS_READER_GRID -> output.writeString(
					section = BackupSection.SETTINGS_READER_GRID,
					data = dumpReaderGridSettings(),
				)

				BackupSection.BOOKMARKS -> output.writeJsonArray(
					section = BackupSection.BOOKMARKS,
					data = database.getBookmarksDao().dump().map { BookmarkBackup(it.first, it.second) },
					serializer = serializer(),
				)

				BackupSection.SOURCES -> output.writeJsonArray(
					section = BackupSection.SOURCES,
					data = database.getSourcesDao().dumpEnabled().map { SourceBackup(it) },
					serializer = serializer(),
				)

				BackupSection.COOKIES -> dumpCookies()?.let {
					output.writeString(
						section = BackupSection.COOKIES,
						data = it,
					)
				}
			}
			progress?.emit(commonProgress)
			commonProgress++
		}
		progress?.emit(commonProgress)
	}

	suspend fun restoreBackup(
		input: ZipInputStream,
		sections: Set<BackupSection>,
		progress: FlowCollector<Progress>?,
	): CompositeResult {
		progress?.emit(Progress.INDETERMINATE)
		var commonProgress = Progress(0, sections.size)
		var entry = input.nextEntry
		var result = CompositeResult.EMPTY
		while (entry != null) {
			val section = BackupSection.of(entry)
			if (section in sections) {
				// A section that fails outright (bad settings JSON, unreadable
				// zip entry, ...) must not prevent the remaining sections from
				// being restored, so failures are contained per section.
				val sectionResult = runCatchingCancellable {
					when (section) {
						BackupSection.INDEX -> CompositeResult.EMPTY // useless in our case
						BackupSection.HISTORY -> input.readJsonArray<HistoryBackup>(serializer()).restoreToDb {
							upsertManga(it.manga)
							getHistoryDao().upsert(it.toEntity())
						}

						BackupSection.CATEGORIES -> input.readJsonArray<CategoryBackup>(serializer()).restoreToDb {
							getFavouriteCategoriesDao().upsert(it.toEntity())
						}

						BackupSection.FAVOURITES -> input.readJsonArray<FavouriteBackup>(serializer()).restoreToDb {
							upsertManga(it.manga)
							getFavouritesDao().upsert(it.toEntity())
						}

						BackupSection.SETTINGS -> input.readMap().let {
							settings.upsertAll(it)
							CompositeResult.success()
						}

						BackupSection.SETTINGS_READER_GRID -> input.readMap().let {
							tapGridSettings.upsertAll(it)
							CompositeResult.success()
						}

						BackupSection.BOOKMARKS -> input.readJsonArray<BookmarkBackup>(serializer()).restoreToDb {
							upsertManga(it.manga)
							getBookmarksDao().upsert(it.bookmarks.map { b -> b.toEntity() })
						}

						BackupSection.SOURCES -> input.readJsonArray<SourceBackup>(serializer()).restoreToDb {
							getSourcesDao().upsert(it.toEntity())
						}

						BackupSection.COOKIES -> input.readMap().let {
							restoreCookies(it)
							CompositeResult.success()
						}

						null -> CompositeResult.EMPTY // skip unknown entries
					}
				}.getOrElse { error ->
					// Whole section failed - record it and move on to the next one
					CompositeResult.failure(error)
				}
				result += sectionResult
				progress?.emit(commonProgress)
				commonProgress++
			}
			input.closeEntry()
			entry = input.nextEntry
		}
		progress?.emit(commonProgress)
		return result
	}

	private suspend fun <T> ZipOutputStream.writeJsonArray(
		section: BackupSection,
		data: Flow<T>,
		serializer: SerializationStrategy<T>,
	) {
		data.onStart {
			putNextEntry(ZipEntry(section.entryName))
			write("[")
		}.onCompletion { error ->
			if (error == null) {
				write("]")
			}
			closeEntry()
			flush()
		}.collectIndexed { index, value ->
			if (index > 0) {
				write(",")
			}
			json.encodeToStream(serializer, value, this)
		}
	}

	/**
	 * Reads a JSON array lazily, one element at a time, as a sequence of
	 * [Result]s so that a single bad entry cannot abort the whole restore.
	 *
	 * Two failure modes are distinguished:
	 *
	 * - A single element fails to deserialize (valid JSON, but an unexpected
	 *   shape - e.g. a field added or removed across app versions). The element
	 *   boundary is still intact, so the failure is reported and reading
	 *   continues with the next element.
	 * - The JSON itself is structurally broken or truncated. There is no
	 *   reliable way to find the next element boundary, so the failure is
	 *   reported and the sequence ends, abandoning just this section.
	 *
	 * Either way this sequence never throws, so the caller keeps processing the
	 * remaining sections of the backup.
	 *
	 * Note: this deliberately avoids [kotlinx.serialization.json.decodeToSequence] /
	 * `decodeFromStream`. Those read through an internal `CharsetReader` that
	 * trips a decoder bug on Android 5.x (API 21-23, and this app supports 21),
	 * failing with `IllegalArgumentException: Bad position (limit N): -NNNNN`
	 * once the payload grows past the 16 KiB lexer buffer. Elements are sliced
	 * out of the stream and parsed individually with [Json.decodeFromString],
	 * which does not use the affected code path.
	 * See https://github.com/Kotlin/kotlinx.serialization/issues/2457
	 */
	private fun <T> InputStream.readJsonArray(
		serializer: DeserializationStrategy<T>,
	): Sequence<Result<T>> {
		val reader = JsonArrayStreamReader(this)
		return sequence {
			var failureCount = 0
			while (true) {
				val elementResult = runCatchingCancellable { reader.nextElement() }
				val readError = elementResult.exceptionOrNull()
				if (readError != null) {
					// Structural damage: cannot resync, so give up on this section
					yield(Result.failure<T>(readError))
					break
				}
				val element = elementResult.getOrNull() ?: break // end of array
				val result = runCatchingCancellable { json.decodeFromString(serializer, element) }
				yield(result)
				if (result.isFailure && ++failureCount >= MAX_ELEMENT_FAILURES) {
					// Everything is failing - most likely an incompatible backup
					// rather than isolated corruption. Stop instead of collecting
					// (and later rendering) thousands of identical errors.
					break
				}
			}
		}
	}

	private fun InputStream.readMap(): Map<String, Any?> {
		val jo = JSONArray(readString()).getJSONObject(0)
		val map = ArrayMap<String, Any?>(jo.length())
		val keys = jo.keys()
		while (keys.hasNext()) {
			val key = keys.next()
			map[key] = jo.get(key)
		}
		return map
	}

	private fun ZipOutputStream.writeString(
		section: BackupSection,
		data: String,
	) {
		putNextEntry(ZipEntry(section.entryName))
		try {
			write("[")
			write(data)
			write("]")
		} finally {
			closeEntry()
			flush()
		}
	}

	private fun OutputStream.write(str: String) = write(str.toByteArray())

	private fun InputStream.readString(): String = readBytes().decodeToString()

	private fun dumpSettings(): String {
		val map = settings.getAllValues().toMutableMap()
		map.remove(AppSettings.KEY_APP_PASSWORD)
		map.remove(AppSettings.KEY_PROXY_PASSWORD)
		map.remove(AppSettings.KEY_PROXY_LOGIN)
		map.remove(AppSettings.KEY_INCOGNITO_MODE)
		return JSONObject(map).toString()
	}

	/**
	 * Cookie snapshot per source domain, same scope the cookies management UI
	 * uses (https://<domain>/ -> jar.loadForRequest). The live jar is the
	 * WebView CookieManager, which cannot enumerate cookies by API, so the
	 * candidate hosts come from the sources table instead. Stored as plain
	 * "name=value; name=value" headers: CookieManager.getCookie returns no
	 * attributes (expiry/path/httpOnly are not readable on any API level) and
	 * for login/bypass sessions name+value is the whole credential anyway.
	 *
	 * Returns null when no cookie jar/product factory is injected. That is the
	 * AppBackupAgent (system Auto Backup) path: it constructs this repository
	 * without DI, and session cookies are credentials that should not go
	 * unencrypted into Google Drive sync anyway.
	 */
	private suspend fun dumpCookies(): String? {
		val jar = cookieJar ?: return null
		val factory = mangaRepositoryFactory ?: return null
		val map = ArrayMap<String, String>()
		for (entity in database.getSourcesDao().findAll()) {
			val repository = runCatching {
				factory.create(MangaSource(entity.source))
			}.getOrNull() ?: continue
			val domain = (repository as? ParserMangaRepository)?.domain ?: continue
			val url = runCatching { "https://$domain/".toHttpUrl() }.getOrNull() ?: continue
			val cookies = runCatching { jar.loadForRequest(url) }.getOrDefault(emptyList())
			if (cookies.isNotEmpty()) {
				map[url.toString()] = cookies.joinToString("; ") { it.name + "=" + it.value }
			}
		}
		return JSONObject(map).toString()
	}

	/**
	 * Re-inserts the dumped headers through the jar's own Set-Cookie path, so
	 * both live implementations end up in a valid state: the WebView store gets
	 * host cookies via CookieManager.setCookie, the SharedPreferences fallback
	 * persists them across restarts by itself. No-op without an injected jar.
	 */
	private fun restoreCookies(map: Map<String, Any?>) {
		val jar = cookieJar ?: return
		for ((url, headerAny) in map) {
			val header = headerAny as? String ?: continue
			val httpUrl = runCatching { url.toHttpUrl() }.getOrNull() ?: continue
			for (token in header.split(';')) {
				if (token.isNotBlank()) {
					runCatching { jar.insertCookie(httpUrl, token.trim()) }
				}
			}
		}
	}

	private fun dumpReaderGridSettings(): String {
		return JSONObject(tapGridSettings.getAllValues()).toString()
	}

	private suspend fun MangaDatabase.upsertManga(manga: MangaBackup) {
		val tags = manga.tags.map { it.toEntity() }
		getTagsDao().upsert(tags)
		getMangaDao().upsert(manga.toEntity(), tags)
	}

	/**
	 * Restores items in bounded-size batches instead of a single huge transaction
	 * (which can hit SQLite row/transaction size limits on large history or
	 * favourites backups) or one transaction per item (which is very slow for
	 * large datasets). The sequence is consumed lazily, so only one batch is
	 * held in memory at a time.
	 *
	 * If a batch transaction fails - e.g. because it is still too large - it is
	 * split into smaller batches and retried, down to a single item if needed,
	 * so one bad or oversized entry does not abort the whole restore.
	 */
	private suspend inline fun <T> Sequence<Result<T>>.restoreToDb(
		noinline block: suspend MangaDatabase.(T) -> Unit,
	): CompositeResult {
		var result = CompositeResult.EMPTY
		for (chunk in chunked(RESTORE_BATCH_SIZE)) {
			// Entries that could not even be parsed are counted as failures and
			// skipped; the rest are still written to the database.
			val (parsed, failed) = chunk.partition { it.isSuccess }
			for (failure in failed) {
				result += failure
			}
			result += restoreChunk(parsed.map { it.getOrThrow() }, block)
		}
		return result
	}

	// Not inline: this function recurses, which Kotlin does not allow for inline functions.
	private suspend fun <T> restoreChunk(
		chunk: List<T>,
		block: suspend MangaDatabase.(T) -> Unit,
	): CompositeResult {
		if (chunk.isEmpty()) {
			return CompositeResult.EMPTY
		}
		if (chunk.size == 1) {
			val single = chunk[0]
			return CompositeResult.EMPTY + runCatchingCancellable {
				database.withTransaction {
					database.block(single)
				}
			}
		}
		val batchResult = runCatchingCancellable {
			database.withTransaction {
				for (item in chunk) {
					database.block(item)
				}
			}
		}
		if (batchResult.isSuccess) {
			return chunk.fold(CompositeResult.EMPTY) { acc, _ -> acc + Result.success(Unit) }
		}
		// The whole batch failed (possibly due to hitting a size limit) - split it
		// and retry, isolating the failure to a smaller subset of items.
		val middle = chunk.size / 2
		return restoreChunk(chunk.subList(0, middle), block) + restoreChunk(chunk.subList(middle, chunk.size), block)
	}

	private companion object {

		private const val RESTORE_BATCH_SIZE = 200

		/**
		 * How many elements of a section may fail to parse before the section
		 * is abandoned. Guards against an incompatible backup producing an
		 * error per entry.
		 */
		private const val MAX_ELEMENT_FAILURES = 100
	}
}
