package org.wastaken.kotatsu.api21.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Booru media: named playlists for the media queue (Task 1 schema).
 */
class Migration27To28 : Migration(27, 28) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `booru_playlists` (" +
				"`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
				"`name` TEXT NOT NULL, " +
				"`created_at` INTEGER NOT NULL)",
		)
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `booru_playlist_items` (" +
				"`playlist_id` INTEGER NOT NULL, " +
				"`item_json` TEXT NOT NULL, " +
				"`position` INTEGER NOT NULL, " +
				"PRIMARY KEY(`playlist_id`, `position`), " +
				"FOREIGN KEY(`playlist_id`) REFERENCES `booru_playlists`(`id`) " +
				"ON UPDATE NO ACTION ON DELETE CASCADE)",
		)
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_booru_playlist_items_playlist_id` ON `booru_playlist_items` (`playlist_id`)")
	}
}
