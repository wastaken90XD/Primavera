package org.wastaken.kotatsu.api21.booru.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Import/Export codec (Task 4). Self-contained JSON shareable between users:
 * only public URLs + MangaSource name strings ride along, never internal ids.
 * Playlist files use type "booru_playlist" (+name/created_at), queue files
 * "booru_queue", settings files "booru_media_settings".
 */
object BooruMediaImportExport {

	const val TYPE_PLAYLIST = "booru_playlist"
	const val TYPE_QUEUE = "booru_queue"
	const val TYPE_SETTINGS = "booru_media_settings"
	const val MIME_JSON = "application/json"

	@Serializable
	data class Envelope(
		val version: Int,
		val type: String,
		val name: String? = null,
		val created_at: Long? = null,
		val items: List<StoredBooruMediaItem> = emptyList(),
		val settings: Map<String, String> = emptyMap(),
	)

	private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

	fun encodeItems(type: String, name: String?, items: List<StoredBooruMediaItem>): String {
		return json.encodeToString(
			Envelope.serializer(),
			Envelope(version = 1, type = type, name = name, created_at = System.currentTimeMillis(), items = items),
		)
	}

	fun encodeSettings(entries: Map<String, String>): String {
		return json.encodeToString(
			Envelope.serializer(),
			Envelope(version = 1, type = TYPE_SETTINGS, settings = entries),
		)
	}

	/** Strict parse: wrong version or unknown type is an error the UI surfaces. */
	fun parse(raw: String): Envelope {
		val envelope = json.decodeFromString(Envelope.serializer(), raw)
		require(envelope.version == 1) { "Unsupported export version ${envelope.version}" }
		require(envelope.type in setOf(TYPE_PLAYLIST, TYPE_QUEUE, TYPE_SETTINGS)) { "Unknown export type ${envelope.type}" }
		return envelope
	}

	suspend fun writeTo(context: Context, uri: Uri, content: String): Boolean = withContext(Dispatchers.IO) {
		runCatching {
			context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) } != null
		}.getOrDefault(false)
	}

	suspend fun readFrom(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
		runCatching {
			context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
		}.getOrNull()
	}

	fun BooruMediaItem.toStored() = StoredBooruMediaItem(
		url = url,
		source = source.name,
		title = title,
		thumbnail_url = thumbnailUrl,
		media_type = mediaType.name,
	)
}
