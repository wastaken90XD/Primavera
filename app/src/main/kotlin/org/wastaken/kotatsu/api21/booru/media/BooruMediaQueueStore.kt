package org.wastaken.kotatsu.api21.booru.media

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.wastaken.kotatsu.api21.core.model.MangaSource
import org.wastaken.kotatsu.api21.core.model.UnknownMangaSource
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class StoredBooruQueue(
	val version: Int = 1,
	val items: List<StoredBooruMediaItem> = emptyList(),
	val index: Int = 0,
	val repeat_mode: String = "NONE",
)

/**
 * SharedPreferences-JSON persistence for [BooruMediaQueue] (Task 1:
 * "persist queue as JSON on every change, restore on service start"),
 * gated by the "Persist queue across restarts" setting at the call site.
 *
 * Sources round-trip by enum-ish name: on load, names that no longer
 * resolve to an installed parser are dropped (the settings-less equivalent
 * of import's "unavailable source" rule).
 */
@Singleton
class BooruMediaQueueStore @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val prefs = context.getSharedPreferences("booru_media_queue", Context.MODE_PRIVATE)
	private val json = Json { ignoreUnknownKeys = true }

	fun persist(queue: BooruMediaQueue) {
		val stored = StoredBooruQueue(
			items = queue.items.map { it.toStored() },
			index = queue.index,
			repeat_mode = queue.repeatMode.name,
		)
		prefs.edit { putString(KEY_QUEUE, json.encodeToString(StoredBooruQueue.serializer(), stored)) }
	}

	fun restoreInto(queue: BooruMediaQueue) {
		val raw = prefs.getString(KEY_QUEUE, null) ?: return
		val stored = runCatching { json.decodeFromString(StoredBooruQueue.serializer(), raw) }.getOrNull() ?: return
		if (stored.version != 1) return
		val items = stored.items.mapNotNull { it.toItem() }
		val repeat = runCatching { RepeatMode.valueOf(stored.repeat_mode) }.getOrDefault(RepeatMode.NONE)
		queue.restore(items, stored.index, repeat)
	}

	fun clear() {
		prefs.edit { remove(KEY_QUEUE) }
	}

	private fun BooruMediaItem.toStored() = StoredBooruMediaItem(
		url = url,
		source = source.name,
		title = title,
		thumbnail_url = thumbnailUrl,
		media_type = mediaType.name,
	)

	companion object {

		private const val KEY_QUEUE = "queue"

		/** Shared with Import/Export (Task 4): null when the source or type is unresolvable. */
		fun StoredBooruMediaItem.toItem(): BooruMediaItem? {
			val src = MangaSource(source)
			if (src == UnknownMangaSource) return null
			val type = runCatching { BooruMediaType.valueOf(media_type) }.getOrNull() ?: return null
			return BooruMediaItem(
				id = BooruMediaItem.uidOf(url),
				url = url,
				source = src,
				title = title,
				thumbnailUrl = thumbnail_url,
				mediaType = type,
			)
		}
	}
}
