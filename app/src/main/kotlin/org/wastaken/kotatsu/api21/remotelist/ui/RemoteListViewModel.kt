package org.wastaken.kotatsu.api21.remotelist.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.booru.media.BooruMediaItem
import org.wastaken.kotatsu.api21.booru.media.BooruMediaResolver
import org.wastaken.kotatsu.api21.booru.media.BooruMediaType
import org.wastaken.kotatsu.api21.core.model.MangaSource
import org.wastaken.kotatsu.api21.core.model.distinctById
import org.wastaken.kotatsu.api21.core.model.unwrap
import org.wastaken.kotatsu.api21.core.parser.MangaDataRepository
import org.wastaken.kotatsu.api21.core.parser.MangaRepository
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.prefs.ListMode
import org.wastaken.kotatsu.api21.core.util.ext.MutableEventFlow
import org.wastaken.kotatsu.api21.core.util.ext.call
import org.wastaken.kotatsu.api21.core.util.ext.getCauseUrl
import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug
import org.wastaken.kotatsu.api21.explore.data.MangaSourcesRepository
import org.wastaken.kotatsu.api21.explore.domain.ExploreRepository
import org.wastaken.kotatsu.api21.filter.ui.FilterCoordinator
import org.wastaken.kotatsu.api21.list.domain.MangaListMapper
import org.wastaken.kotatsu.api21.list.ui.MangaListViewModel
import org.wastaken.kotatsu.api21.list.ui.model.BooruGridModel
import org.wastaken.kotatsu.api21.list.ui.model.ButtonFooter
import org.wastaken.kotatsu.api21.list.ui.model.EmptyState
import org.wastaken.kotatsu.api21.list.ui.model.ListModel
import org.wastaken.kotatsu.api21.list.ui.model.LoadingFooter
import org.wastaken.kotatsu.api21.list.ui.model.LoadingState
import org.wastaken.kotatsu.api21.list.ui.model.toErrorFooter
import org.wastaken.kotatsu.api21.list.ui.model.toErrorState
import org.wastaken.kotatsu.api21.reader.ui.PageSaveHelper
import org.wastaken.kotatsu.api21.reader.ui.media.isBooruSource
import android.net.Uri
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.util.sizeOrZero
import javax.inject.Inject

private const val FILTER_MIN_INTERVAL = 250L

@HiltViewModel
open class RemoteListViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	final override val filterCoordinator: FilterCoordinator,
	private val settings: AppSettings,
	protected val mangaListMapper: MangaListMapper,
	private val exploreRepository: ExploreRepository,
	sourcesRepository: MangaSourcesRepository,
	private val mangaDataRepository: MangaDataRepository
) : MangaListViewModel(settings, mangaDataRepository), FilterCoordinator.Owner {

	val source = MangaSource(savedStateHandle[RemoteListFragment.ARG_SOURCE])
	val isRandomLoading = MutableStateFlow(false)
	val onOpenManga = MutableEventFlow<Manga>()
	val onImageSaved = MutableEventFlow<Collection<Uri>>()

	/** Route to the booru full-screen player (video/GIF posts only). */
	val onBooruMediaRoute = MutableEventFlow<BooruMediaItem>()

	/** Emitted when an item lands in the media queue (fragment shows the toast). */
	val onBooruMediaQueued = MutableEventFlow<BooruMediaItem>()

	/** Inline GIF load finished resolving: (manga id, file url). */
	val onInlineGifResolved = MutableEventFlow<Pair<Long, String>>()

	/** Inline GIF resolution failed/returned non-GIF: manga id. */
	val onInlineGifFailed = MutableEventFlow<Long>()

	/**
	 * Resolves the post's file URL and routes media posts to the booru player.
	 * Static posts and failures fall back to [fallback] on the main thread.
	 */
	fun routeBooruPost(manga: Manga, fallback: () -> Unit) {
		if (!settings.isMediaPlayerEnabledForSource(manga.source)) {
			fallback()
			return
		}
		launchLoadingJob(Dispatchers.Main) {
			val item = runCatching { BooruMediaResolver.resolve(mangaRepositoryFactory, manga) }.getOrNull()
			if (item != null) {
				onBooruMediaRoute.call(item)
			} else {
				fallback()
			}
		}
	}

	/** Adds the post to the media queue without starting playback. */
	fun enqueueBooruPost(manga: Manga) {
		launchLoadingJob(Dispatchers.Main) {
			val item = runCatching { BooruMediaResolver.resolve(mangaRepositoryFactory, manga) }.getOrNull()
			if (item != null) {
				onBooruMediaQueued.call(item)
			} else {
				errorEvent.call(NullPointerException("Not a media post"))
			}
		}
	}

	/** Resolves the post and, if it is a GIF, queues it for playback. */
	fun enqueueBooruGif(manga: Manga) = enqueueBooruPost(manga)

	/**
	 * "GIF tap action" = INLINE: fetch the file and hand it to the grid card
	 * so the still cover is swapped for the animated one (explicit load).
	 */
	fun beginInlineGifLoad(manga: Manga) {
		launchLoadingJob(Dispatchers.Default) {
			val pair = runCatching {
				val item = BooruMediaResolver.resolve(mangaRepositoryFactory, manga)
				item?.takeIf { it.mediaType == BooruMediaType.GIF }?.let { manga.id to it.url }
			}.getOrNull()
			if (pair != null) {
				onInlineGifResolved.call(pair)
			} else {
				onInlineGifFailed.call(manga.id)
			}
		}
	}

	/**
	 * Long-press save on the booru grid. STRICTLY booru-only: the internal guard
	 * makes this a no-op for every non-booru source, even if a future caller
	 * forgets to check (same rule as PageSaveHelper.isSaveOriginalForBooru).
	 * Uses the details screen's resolution pipeline (getDetails -> chapter -> page
	 * -> PageSaveHelper.Task), so source headers, the download folder picker and
	 * the original-bytes preference behave identically for both entry points.
	 */
	fun saveBooruImage(pageSaveHelper: PageSaveHelper, manga: Manga) {
		if (!manga.source.isBooruSource()) {
			return
		}
		launchLoadingJob(Dispatchers.Default) {
			// this VM's repository is already created for the grid's own source,
			// which is the only source booru grid items can belong to
			val details = repository.getDetails(manga)
			val chapter = checkNotNull(details.chapters?.firstOrNull()) { "No pages found" }
			val pages = repository.getPages(chapter)
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

	/**
	 * Booru sources get the square-thumbnail grid treatment (see BooruGridAdapter):
	 * their posts are standalone images, not manga covers.
	 */
	val isBooru: Boolean by lazy(LazyThreadSafetyMode.NONE) {
		(source.unwrap() as? MangaParserSource)?.contentType == ContentType.BOORU
	}

	protected val repository = mangaRepositoryFactory.create(source)
	private val mangaList = MutableStateFlow<List<Manga>?>(null)
	private val hasNextPage = MutableStateFlow(false)
	private val listError = MutableStateFlow<Throwable?>(null)
	private var loadingJob: Job? = null
	private var randomJob: Job? = null

	override val content = combine(
		mangaList.map { it?.skipNsfwIfNeeded() },
		observeListModeWithTriggers(),
		listError,
		hasNextPage,
	) { list, mode, error, hasNext ->
		buildList(list?.size?.plus(2) ?: 2) {
			when {
				list.isNullOrEmpty() && error != null -> add(
					error.toErrorState(
						canRetry = true,
						secondaryAction = if (error.getCauseUrl().isNullOrEmpty()) 0 else R.string.open_in_browser,
					),
				)

				list == null -> add(LoadingState)
				list.isEmpty() -> add(createEmptyState(canResetFilter = filterCoordinator.isFilterApplied))
				else -> {
					mapMangaList(this, list, mode)
					when {
						error != null -> add(error.toErrorFooter())
						hasNext -> add(LoadingFooter())
						else -> getFooter()?.let(::add)
					}
				}
			}
			onBuildList(this)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, listOf(LoadingState))

	init {
		filterCoordinator.observe()
			.debounce(FILTER_MIN_INTERVAL)
			.onEach { filterState ->
				loadingJob?.cancelAndJoin()
				mangaList.value = null
				loadList(filterState, false)
			}.catch { error ->
				listError.value = error
			}.launchIn(viewModelScope)

		launchJob(Dispatchers.Default) {
			sourcesRepository.trackUsage(source)
		}
	}

	override fun onRefresh() {
		loadList(filterCoordinator.snapshot(), append = false)
	}

	override fun onRetry() {
		loadList(filterCoordinator.snapshot(), append = !mangaList.value.isNullOrEmpty())
	}

	fun loadNextPage() {
		if (hasNextPage.value && listError.value == null) {
			loadList(filterCoordinator.snapshot(), append = true)
		}
	}

	protected fun loadList(filterState: FilterCoordinator.Snapshot, append: Boolean): Job {
		loadingJob?.let {
			if (it.isActive) return it
		}
		return launchLoadingJob(Dispatchers.Default) {
			try {
				listError.value = null
				val list = repository.getList(
					offset = if (append) mangaList.value.sizeOrZero() else 0,
					order = filterState.sortOrder,
					filter = filterState.listFilter,
				)
				val prevList = mangaList.value.orEmpty()
				if (!append) {
					mangaList.value = list.distinctById()
				} else if (list.isNotEmpty()) {
					mangaList.value = (prevList + list).distinctById()
				}
				hasNextPage.value = if (append) {
					prevList != mangaList.value
				} else {
					list.size > prevList.size || hasNextPage.value
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				e.printStackTraceDebug()
				listError.value = e
				if (!mangaList.value.isNullOrEmpty()) {
					errorEvent.call(e)
				}
				hasNextPage.value = false
			}
		}.also { loadingJob = it }
	}

	protected open fun createEmptyState(canResetFilter: Boolean) = EmptyState(
		icon = R.drawable.ic_empty_common,
		textPrimary = R.string.nothing_found,
		textSecondary = 0,
		actionStringRes = if (canResetFilter) R.string.reset_filter else 0,
	)

	protected open suspend fun onBuildList(list: MutableList<ListModel>) = Unit

	protected open suspend fun mapMangaList(
		destination: MutableCollection<in ListModel>,
		manga: Collection<Manga>,
		mode: ListMode
	) {
		if (isBooru) {
			mapBooruList(destination, manga)
		} else {
			mangaListMapper.toListModelList(destination, manga, mode)
		}
	}

	private suspend fun mapBooruList(
		destination: MutableCollection<in ListModel>,
		manga: Collection<Manga>,
	) {
		val overrides = mangaDataRepository.getOverrides()
		manga.mapTo(destination) { BooruGridModel(it, overrides[it.id]) }
	}

	protected open fun getFooter(): ButtonFooter? {
		val filter = filterCoordinator.snapshot().listFilter
		val hasQuery = !filter.query.isNullOrEmpty()
		val hasAuthor = !filter.author.isNullOrEmpty()
		val isOneTag = filter.tags.size == 1
		return if ((hasQuery xor isOneTag xor hasAuthor) && !(hasQuery && isOneTag && hasAuthor)) {
			ButtonFooter(R.string.global_search)
		} else {
			null
		}
	}

	fun openRandom() {
		if (randomJob?.isActive == true) {
			return
		}
		randomJob = launchLoadingJob(Dispatchers.Default) {
			isRandomLoading.value = true
			val manga = exploreRepository.findRandomManga(source, 16)
			onOpenManga.call(manga)
			isRandomLoading.value = false
		}
	}
}
