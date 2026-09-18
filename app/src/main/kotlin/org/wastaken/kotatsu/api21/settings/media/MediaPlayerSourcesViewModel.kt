package org.wastaken.kotatsu.api21.settings.media

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.wastaken.kotatsu.api21.core.LocalizedAppContext
import org.wastaken.kotatsu.api21.core.model.getTitle
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.ui.BaseViewModel
import org.wastaken.kotatsu.api21.explore.data.MangaSourcesRepository
import org.wastaken.kotatsu.api21.reader.ui.media.mediaPlayerDefault
import javax.inject.Inject

/**
 * Settings -> Media player -> Sources: the per-source media-player toggles.
 * Booru-native sources sort first (their default is ON), the rest follows
 * (default OFF). Toggles apply immediately; reset restores defaults by
 * clearing every "media_player_source_*" override.
 */
@HiltViewModel
class MediaPlayerSourcesViewModel @Inject constructor(
	sourcesRepository: MangaSourcesRepository,
	private val settings: AppSettings,
	@LocalizedAppContext private val context: Context,
) : BaseViewModel() {

	data class SourceRow(
		val source: MangaParserSource,
		val title: String,
		val enabled: Boolean,
	)

	val rows = MutableStateFlow<List<SourceRow>>(emptyList())

	private var query: String = ""

	private var allSources: List<MangaParserSource> = emptyList()

	init {
		viewModelScope.launch(Dispatchers.Default) {
			allSources = sourcesRepository.allMangaSources.sortedWith(
				compareByDescending<MangaParserSource> { it.mediaPlayerDefault() }
					.thenBy { it.getTitle(context).lowercase() },
			)
			refresh()
		}
	}

	fun setQuery(value: String) {
		query = value
		refresh()
	}

	fun setEnabled(source: MangaParserSource, enabled: Boolean) {
		settings.setMediaPlayerEnabledForSource(source, enabled)
		refresh()
	}

	fun resetDefaults() {
		settings.resetMediaPlayerSourceOverrides()
		refresh()
	}

	private fun refresh() {
		val q = query.trim()
		rows.value = allSources
			.asSequence()
			.filter { q.isEmpty() || it.getTitle(context).contains(q, ignoreCase = true) }
			.map { SourceRow(it, it.getTitle(context), settings.isMediaPlayerEnabledForSource(it)) }
			.toList()
	}
}
