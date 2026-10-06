package org.wastaken.kotatsu.api21.booru.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
abstract class BooruPlaylistsDao {

	@Query("SELECT * FROM booru_playlists ORDER BY created_at DESC")
	abstract suspend fun findAll(): List<BooruPlaylistEntity>

	@Query("SELECT * FROM booru_playlists WHERE id = :playlistId")
	abstract suspend fun find(playlistId: Long): BooruPlaylistEntity?

	@Upsert
	abstract suspend fun upsertPlaylist(entity: BooruPlaylistEntity): Long

	@Query("UPDATE booru_playlists SET name = :name WHERE id = :playlistId")
	abstract suspend fun rename(playlistId: Long, name: String)

	@Query("DELETE FROM booru_playlists WHERE id = :playlistId")
	abstract suspend fun delete(playlistId: Long)

	@Query("SELECT * FROM booru_playlist_items WHERE playlist_id = :playlistId ORDER BY position ASC")
	abstract suspend fun findItems(playlistId: Long): List<BooruPlaylistItemEntity>

	@Upsert
	abstract suspend fun upsertItems(entities: List<BooruPlaylistItemEntity>)

	@Query("DELETE FROM booru_playlist_items WHERE playlist_id = :playlistId")
	abstract suspend fun deleteItems(playlistId: Long)
}
