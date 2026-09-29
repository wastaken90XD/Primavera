package org.wastaken.kotatsu.api21.booru.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Named playlists built from the booru media queue (Task 1).
 * Items are stored as one JSON document per row (spec schema:
 * `booru_playlist_items(playlist_id, item_json, position)`) so the player
 * model can evolve without further migrations.
 */
@Entity(tableName = "booru_playlists")
data class BooruPlaylistEntity(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "id") val id: Long = 0,
	@ColumnInfo(name = "name") val name: String,
	@ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
	tableName = "booru_playlist_items",
	primaryKeys = ["playlist_id", "position"],
	foreignKeys = [
		ForeignKey(
			entity = BooruPlaylistEntity::class,
			parentColumns = ["id"],
			childColumns = ["playlist_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("playlist_id")],
)
data class BooruPlaylistItemEntity(
	@ColumnInfo(name = "playlist_id") val playlistId: Long,
	@ColumnInfo(name = "item_json") val itemJson: String,
	@ColumnInfo(name = "position") val position: Int,
)
