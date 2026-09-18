package org.wastaken.kotatsu.api21.details.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.wastaken.kotatsu.api21.booru.media.BooruMediaItem
import org.wastaken.kotatsu.api21.booru.media.BooruMediaResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import android.net.Uri
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.bookmarks.domain.BookmarksRepository
import org.wastaken.kotatsu.api21.core.model.getPreferredBranch
import org.wastaken.kotatsu.api21.core.nav.MangaIntent
import org.wastaken.kotatsu.api21.core.parser.MangaRepository
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.prefs.ListMode
import org.wastaken.kotatsu.api21.core.prefs.TriStateOption
import org.wastaken.kotatsu.api21.core.ui.util.ReversibleAction
import org.wastaken.kotatsu.api21.core.util.ext.MutableEventFlow
import org.wastaken.kotatsu.api21.core.util.ext.call
import org.wastaken.kotatsu.api21.core.util.ext.computeSize
import org.wastaken.kotatsu.api21.core.util.ext.onEachWhile
import org.wastaken.kotatsu.api21.details.data.MangaDetails
import org.wastaken.kotatsu.api21.details.domain.BranchComparator
import org.wastaken.kotatsu.api21.details.domain.DetailsInteractor
import org.wastaken.kotatsu.api21.details.domain.DetailsLoadUseCase
import org.wastaken.kotatsu.api21.details.domain.ProgressUpdateUseCase
import org.wastaken.kotatsu.api21.details.domain.ReadingTimeUseCase
import org.wastaken.kotatsu.api21.details.domain.RelatedMangaUseCase
import org.wastaken.kotatsu.api21.details.ui.model.HistoryInfo
import org.wastaken.kotatsu.api21.details.ui.model.MangaBranch
import org.wastaken.kotatsu.api21.details.ui.pager.ChaptersPagesViewModel
import org.wastaken.kotatsu.api21.download.ui.worker.DownloadWorker
import org.wastaken.kotatsu.api21.history.data.HistoryRepository
import org.wastaken.kotatsu.api21.list.domain.MangaListMapper
import org.wastaken.kotatsu.api21.list.ui.model.MangaListModel
import org.wastaken.kotatsu.api21.local.data.LocalStorageChanges
import org.wastaken.kotatsu.api21.local.domain.DeleteLocalMangaUseCase
import org.wastaken.kotatsu.api21.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.wastaken.kotatsu.api21.core.model.isLocal
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.wastaken.kotatsu.api21.reader.ui.PageSaveHelper
import org.wastaken.kotatsu.api21.reader.ui.ReaderState
import org.wastaken.kotatsu.api21.scrobbling.common.domain.Scrobbler
import org.wastaken.kotatsu.api21.scrobbling.common.domain.model.ScrobblingInfo
import org.wastaken.kotatsu.api21.scrobbling.common.domain.model.ScrobblingStatus
import org.wastaken.kotatsu.api21.stats.data.StatsRepository
import javax.inject.Inject

@HiltViewModel
class DetailsViewModel @Inject constructor(
	private val historyRepository: HistoryRepository,
	bookmarksRepository: BookmarksRepository,
	settings: AppSettings,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
	downloadScheduler: DownloadWorker.Scheduler,
	interactor: DetailsInteractor,
	savedStateHandle: SavedStateHandle,
	deleteLocalMangaUseCase: DeleteLocalMangaUseCase,
	private val relatedMangaUseCase: RelatedMangaUseCase,
	private val mangaListMapper: MangaListMapper,
	private val detailsLoadUseCase: DetailsLoadUseCase,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
	private val readingTimeUseCase: ReadingTimeUseCase,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	statsRepository: StatsRepository,
) : ChaptersPagesViewModel(
	settings = settings,
	interactor = interactor,
	bookmarksRepository = bookmarksRepository,
	historyRepository = historyRepository,
	downloadScheduler = downloadScheduler,
	deleteLocalMangaUseCase = deleteLocalMangaUseCase,
	localStorageChanges = localStorageChanges,
) {

	private val intent = MangaIntent(savedStateHandle)
	private var loadingJob: Job
	val mangaId = intent.mangaId
	val onImageSaved = MutableEventFlow<Collection<Uri>>()

	/** Route to the booru full-screen player (GIF/video posts from the read button). */
	val onBooruMediaRoute = MutableEventFlow<BooruMediaItem>()

	/**
	 * Booru read-button routing (media spec): media posts never open the reader,
	 * they resolve to the booru player; static posts run [fallback] (main thread).
	 */
	/** Read-button gate: only media-player-enabled, non-local posts take the player route. */
	fun isMediaRoutingEnabled(manga: Manga): Boolean =
		!manga.isLocal && settings.isMediaPlayerEnabledForSource(manga.source)

	fun routeBooruPost(manga: Manga, fallback: () -> Unit) {
		viewModelScope.launch(Dispatchers.Main) {
			val item = runCatching {
				BooruMediaResolver.resolve(mangaRepositoryFactory, manga)
			}.getOrNull()
			if (item != null) {
				onBooruMediaRoute.call(item)
			} else {
				fallback()
			}
		}
	}

	init {
		mangaDetails.value = intent.manga?.let { MangaDetails(it) }
	}

	val history = historyRepository.observeOne(mangaId)
		.onEach { h ->
			readingState.value = h?.let(::ReaderState)
		}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val favouriteCategories = interactor.observeFavourite(mangaId)
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptySet())

	val isStatsAvailable = statsRepository.observeHasStats(mangaId)
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	val remoteManga = MutableStateFlow<Manga?>(null)

	val historyInfo: StateFlow<HistoryInfo> = combine(
		mangaDetails,
		selectedBranch,
		history,
		interactor.observeIncognitoMode(manga),
	) { m, b, h, im ->
		val estimatedTime = readingTimeUseCase.invoke(m, b, h)
		HistoryInfo(m, b, h, im == TriStateOption.ENABLED, estimatedTime)
	}.withErrorHandling()
		.stateIn(
			scope = viewModelScope + Dispatchers.Default,
			started = SharingStarted.Eagerly,
			initialValue = HistoryInfo(null, null, null, false, null),
		)

	val localSize = mangaDetails
		.map { it?.local }
		.distinctUntilChanged()
		.combine(localStorageChanges.onStart { emit(null) }) { x, _ -> x }
		.map { local ->
			if (local != null) {
				runCatchingCancellable {
					local.file.computeSize()
				}.getOrDefault(0L)
			} else {
				0L
			}
		}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), 0L)

	val isScrobblingAvailable: Boolean
		get() = scrobblers.any { it.isEnabled }

	val scrobblingInfo: StateFlow<List<ScrobblingInfo>> = interactor.observeScrobblingInfo(mangaId)
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val relatedManga: StateFlow<List<MangaListModel>> = manga.mapLatest {
		if (it != null && settings.isRelatedMangaEnabled) {
			mangaListMapper.toListModelList(
				manga = relatedMangaUseCase(it).orEmpty(),
				mode = ListMode.GRID,
			)
		} else {
			emptyList()
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

	val tags = manga.mapLatest {
		mangaListMapper.mapTags(it?.tags.orEmpty())
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val branches: StateFlow<List<MangaBranch>> = combine(
		mangaDetails,
		selectedBranch,
		history,
	) { m, b, h ->
		val c = m?.chapters
		if (c.isNullOrEmpty()) {
			return@combine emptyList()
		}
		val currentBranch = h?.let { m.allChapters.findById(it.chapterId) }?.branch
		c.map { x ->
			MangaBranch(
				name = x.key,
				count = x.value.size,
				isSelected = x.key == b,
				isCurrent = h != null && x.key == currentBranch,
			)
		}.sortedWith(BranchComparator())
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val selectedBranchValue: String?
		get() = selectedBranch.value

	init {
		loadingJob = doLoad(force = false)
		launchJob(Dispatchers.Default + SkipErrors) {
			val manga = mangaDetails.firstOrNull { !it?.chapters.isNullOrEmpty() } ?: return@launchJob
			val h = history.firstOrNull()
			if (h != null) {
				progressUpdateUseCase(manga.toManga())
			}
		}
		launchJob(Dispatchers.Default) {
			val manga = mangaDetails.firstOrNull { it != null && it.isLocal } ?: return@launchJob
			remoteManga.value = interactor.findRemote(manga.toManga())
		}
	}

	fun reload() {
		loadingJob.cancel()
		loadingJob = doLoad(force = true)
	}

	/**
	 * Saves the original image of a booru post: a booru post is a single-chapter,
	 * single-page manga, so this loads the pages of the first chapter and saves
	 * the first page via [PageSaveHelper].
	 */
	fun saveBooruImage(pageSaveHelper: PageSaveHelper) {
		launchLoadingJob(Dispatchers.Default) {
			val details = mangaDetails.firstOrNull { it != null && it.isLoaded } ?: return@launchLoadingJob
			val manga = details.toManga()
			val chapter = checkNotNull(details.allChapters.firstOrNull()) { "No pages found" }
			val pages = mangaRepositoryFactory.create(chapter.source).getPages(chapter)
			val page = checkNotNull(pages.firstOrNull()) { "No pages found" }
			val task = PageSaveHelper.Task(
				manga = manga,
				chapterId = chapter.id,
				pageNumber = 1,
				page = page,
			)
			onImageSaved.call(pageSaveHelper.save(setOf(task)))
		}
	}

	fun updateScrobbling(index: Int, rating: Float, status: ScrobblingStatus?) {
		val scrobbler = getScrobbler(index) ?: return
		launchJob(Dispatchers.Default) {
			scrobbler.updateScrobblingInfo(
				mangaId = mangaId,
				rating = rating,
				status = status,
				comment = null,
			)
		}
	}

	fun unregisterScrobbling(index: Int) {
		val scrobbler = getScrobbler(index) ?: return
		launchJob(Dispatchers.Default) {
			scrobbler.unregisterScrobbling(
				mangaId = mangaId,
			)
		}
	}

	fun removeFromHistory() {
		launchJob(Dispatchers.Default) {
			val handle = historyRepository.delete(setOf(mangaId))
			onActionDone.call(ReversibleAction(R.string.removed_from_history, handle))
		}
	}

	private fun doLoad(force: Boolean) = launchLoadingJob(Dispatchers.Default) {
		detailsLoadUseCase.invoke(intent, force)
			.onEachWhile {
				if (it.allChapters.isNotEmpty()) {
					val manga = it.toManga()
					// find default branch
					val hist = historyRepository.getOne(manga)
					selectedBranch.value = manga.getPreferredBranch(hist)
					true
				} else {
					false
				}
			}.collect {
				mangaDetails.value = it
			}
	}

	private fun getScrobbler(index: Int): Scrobbler? {
		val info = scrobblingInfo.value.getOrNull(index)
		val scrobbler = if (info != null) {
			scrobblers.find { it.scrobblerService == info.scrobbler && it.isEnabled }
		} else {
			null
		}
		if (scrobbler == null) {
			errorEvent.call(IllegalStateException("Scrobbler [$index] is not available"))
		}
		return scrobbler
	}
}
