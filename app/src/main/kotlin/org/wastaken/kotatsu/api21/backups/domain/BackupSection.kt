package org.wastaken.kotatsu.api21.backups.domain

import java.util.Locale
import java.util.zip.ZipEntry

enum class BackupSection(
	val entryName: String,
) {

	INDEX("index"),
	HISTORY("history"),
	CATEGORIES("categories"),
	FAVOURITES("favourites"),
	SETTINGS("settings"),
	SETTINGS_READER_GRID("reader_grid"),
	BOOKMARKS("bookmarks"),
	SOURCES("sources"),
	COOKIES("cookies"),
	;

	companion object {

		/**
		 * Returns `null` for entries that are not a known section.
		 *
		 * Note: this used to use `first`, which threw [NoSuchElementException]
		 * on any unrecognised entry and aborted the whole restore - even though
		 * every caller already handles `null` as "skip unknown entry".
		 */
		fun of(entry: ZipEntry): BackupSection? {
			val name = entry.name.lowercase(Locale.ROOT)
			return entries.firstOrNull { x -> x.entryName == name }
		}
	}
}
