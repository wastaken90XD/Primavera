package org.wastaken.kotatsu.api21.booru.data

import kotlinx.serialization.json.Json
import org.wastaken.kotatsu.api21.booru.media.BooruMediaItem
import org.wastaken.kotatsu.api21.booru.media.BooruMediaQueueStore.Companion.toItem
import org.wastaken.kotatsu.api21.booru.media.StoredBooruMediaItem
import org.wastaken.kotatsu.api21.core.db.MangaDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CRUD for named booru media playlists (Task 1). Item payloads are the same
 * serializable shape used for queue persistence and import/export, so a
 * playlist, an exported file and the live queue all share one codec.
 */
@Singleton
class BooruPlaylistsRepository @Inject constructor(
	db: MangaDatabase,
) {

	private val dao = db.getBooruPlaylistsDao()
	private val json = Json { ignoreUnknownKeys = true }

	suspend fun playlists(): List<BooruPlaylistEntity> = dao.findAll()

	/** Creates (or renames) a playlist and stores [items] as its content; returns the id. */
	suspend fun savePlaylist(playlistId: Long = 0, name: String, items: List<BooruMediaItem>): Long {
		val id = dao.upsertPlaylist(
			BooruPlaylistEntity(
				id = playlistId,
				name = name,
				createdAt = System.currentTimeMillis(),
			),
		).takeIf { it > 0 } ?: playlistId
		replaceItems(id, items)
		return id
	}

	suspend fun replaceItems(playlistId: Long, items: List<BooruMediaItem>) {
		dao.deleteItems(playlistId)
		dao.upsertItems(
			items.mapIndexed { position, item ->
				BooruPlaylistItemEntity(
					playlistId = playlistId,
					itemJson = json.encodeToString(StoredBooruMediaItem.serializer(), item.toStored()),
					position = position,
				)
			},
		)
	}

	suspend fun loadItems(playlistId: Long): List<BooruMediaItem> {
		return dao.findItems(playlistId).mapNotNull { entity ->
			runCatching { json.decodeFromString(StoredBooruMediaItem.serializer(), entity.itemJson) }
				.getOrNull()
				?.toItem()
		}
	}

	suspend fun rename(playlistId: Long, name: String) = dao.rename(playlistId, name)

	suspend fun delete(playlistId: Long) = dao.delete(playlistId)

	private fun BooruMediaItem.toStored() = StoredBooruMediaItem(
		url = url,
		source = source.name,
		title = title,
		thumbnail_url = thumbnailUrl,
		media_type = mediaType.name,
	)
}
