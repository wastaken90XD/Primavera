package org.wastaken.kotatsu.api21.remotelist.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import androidx.appcompat.widget.PopupMenu
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.booru.media.BooruMediaService
import org.wastaken.kotatsu.api21.booru.media.BooruLongPressAction
import org.wastaken.kotatsu.api21.booru.media.BooruMediaType
import org.wastaken.kotatsu.api21.booru.media.GifTapAction
import org.wastaken.kotatsu.api21.booru.media.VideoTapAction
import org.wastaken.kotatsu.api21.booru.media.ui.BooruPlayerActivity
import org.wastaken.kotatsu.api21.core.model.getTitle
import org.wastaken.kotatsu.api21.search.ui.MangaListActivity
import org.wastaken.kotatsu.api21.core.nav.router
import org.wastaken.kotatsu.api21.core.prefs.ListMode
import org.wastaken.kotatsu.api21.core.ui.list.ListSelectionController
import org.wastaken.kotatsu.api21.core.ui.util.MenuInvalidator
import org.wastaken.kotatsu.api21.core.util.ext.addMenuProvider
import org.wastaken.kotatsu.api21.core.util.ext.getCauseUrl
import org.wastaken.kotatsu.api21.core.util.ext.isHttpUrl
import org.wastaken.kotatsu.api21.core.util.ext.observe
import org.wastaken.kotatsu.api21.core.util.ext.observeEvent
import org.wastaken.kotatsu.api21.core.util.ext.withArgs
import org.wastaken.kotatsu.api21.databinding.FragmentListBinding
import org.wastaken.kotatsu.api21.details.ui.pager.pages.PagesSavedObserver
import org.wastaken.kotatsu.api21.filter.ui.FilterCoordinator
import org.wastaken.kotatsu.api21.list.ui.MangaListFragment
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.list.ui.adapter.BooruGridAdapter
import org.wastaken.kotatsu.api21.list.ui.adapter.ListItemType
import org.wastaken.kotatsu.api21.list.ui.adapter.MangaListAdapter
import org.wastaken.kotatsu.api21.list.ui.model.BooruGridModel
import org.wastaken.kotatsu.api21.list.ui.model.MangaListModel
import org.wastaken.kotatsu.api21.reader.ui.PageSaveHelper
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.wastaken.kotatsu.api21.search.domain.SearchKind
import javax.inject.Inject

@AndroidEntryPoint
class RemoteListFragment : MangaListFragment(), FilterCoordinator.Owner {

	override val viewModel by viewModels<RemoteListViewModel>()

	private var booruAdapter: BooruGridAdapter? = null
	private val booruSpanSizeLookup = BooruSpanSizeLookup()

	@Inject
	lateinit var pageSaveHelperFactory: PageSaveHelper.Factory

	private lateinit var pageSaveHelper: PageSaveHelper

	/** Live-applies the booru grid column setting (existing AppSettings listener pattern). */
	private val booruColumnsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when (key) {
			AppSettings.KEY_BOORU_GRID_COLUMNS -> onBooruGridColumnsChanged()
			AppSettings.KEY_MEDIA_BLUR_THUMBNAILS, AppSettings.KEY_MEDIA_BLUR_INTENSITY -> {
				applyBooruBlur()
				booruAdapter?.notifyDataSetChanged()
			}
		}
	}

	private fun applyBooruBlur() {
		booruAdapter?.blurRadius = if (settings.isMediaBlurThumbnails) settings.mediaBlurIntensity else 0
	}

	override val filterCoordinator: FilterCoordinator
		get() = viewModel.filterCoordinator

	override fun onAttach(context: android.content.Context) {
		super.onAttach(context)
		pageSaveHelper = pageSaveHelperFactory.create(this)
	}

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		addMenuProvider(RemoteListMenuProvider())
		addMenuProvider(MangaSearchMenuProvider(filterCoordinator, viewModel))
		viewModel.isRandomLoading.observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
		viewModel.onOpenManga.observeEvent(viewLifecycleOwner) { router.openDetails(it) }
		viewModel.onImageSaved.observeEvent(viewLifecycleOwner, PagesSavedObserver(binding.recyclerView))
		viewModel.onBooruMediaRoute.observeEvent(viewLifecycleOwner) { item ->
			BooruPlayerActivity.start(requireContext(), item)
		}
		viewModel.onBooruMediaQueued.observeEvent(viewLifecycleOwner) { item -> enqueueIntoService(item) }
		viewModel.onInlineGifResolved.observeEvent(viewLifecycleOwner) { (id, url) ->
			booruAdapter?.onInlineGifResolved(id, url)
		}
		viewModel.onInlineGifFailed.observeEvent(viewLifecycleOwner) {
			booruAdapter?.notifyDataSetChanged()
		}
		settings.subscribe(booruColumnsListener)
		filterCoordinator.observe().distinctUntilChangedBy { it.listFilter.isEmpty() }
			.drop(1)
			.observe(viewLifecycleOwner) {
				activity?.invalidateMenu()
			}
	}

	override fun onCreateAdapter(): MangaListAdapter {
		return if (viewModel.isBooru) {
			// booru sources are browsed as a square-thumbnail grid (see BooruGridAdapter);
			// all other sources keep the standard manga tiles untouched
			BooruGridAdapter(this, settings.booruGridColumns).also {
				booruAdapter = it
				applyBooruBlur()
			}
		} else {
			super.onCreateAdapter()
		}
	}

	override fun onListModeChanged(mode: ListMode) {
		if (!viewModel.isBooru) {
			super.onListModeChanged(mode)
			return
		}
		val columns = settings.booruGridColumns
		booruSpanSizeLookup.fullSpan = columns
		with(requireViewBinding().recyclerView) {
			layoutManager = GridLayoutManager(context, columns).also {
				it.spanSizeLookup = booruSpanSizeLookup
			}
			setItemViewCacheSize(BOORU_VIEW_CACHE_SIZE)
		}
	}

	override fun onGridScaleChanged(scale: Float) {
		// the global grid-size scale does not apply to the booru grid: it follows its
		// own column-count setting (booru_grid_columns) instead
		if (!viewModel.isBooru) {
			super.onGridScaleChanged(scale)
		}
	}

	private fun onBooruGridColumnsChanged() {
		if (!viewModel.isBooru) {
			return
		}
		val binding = requireViewBinding()
		val columns = settings.booruGridColumns
		(binding.recyclerView.layoutManager as? GridLayoutManager)?.let { manager: GridLayoutManager ->
			manager.spanCount = columns
			booruSpanSizeLookup.fullSpan = columns
			// setSpanCount already invalidates the span-index cache; explicit call keeps
			// the lookup state consistent
			booruSpanSizeLookup.invalidateSpanIndexCache()
		}
		booruAdapter?.notifyDataSetChanged()
	}

	override fun onDestroyView() {
		settings.unsubscribe(booruColumnsListener)
		booruAdapter = null
		super.onDestroyView()
	}

	private inner class BooruSpanSizeLookup : GridLayoutManager.SpanSizeLookup() {

		/** Mirrors the current column count: state/footer rows are always full-width. */
		var fullSpan: Int = 3

		override fun getSpanSize(position: Int): Int {
			return when (booruAdapter?.getItemViewType(position)) {
				ListItemType.BOORU_GRID.ordinal -> 1
				else -> fullSpan
			}
		}
	}

	override fun onScrolledToEnd() {
		viewModel.loadNextPage()
	}

	override fun onItemClick(item: MangaListModel, view: View) {
		if (viewModel.isBooru && item is BooruGridModel) {
			if (settings.booruLongPressAction == BooruLongPressAction.SELECT) {
				// SELECT mode restores the original tile behavior wholesale:
				// clicks take the standard (selection-aware) path
				super.onItemClick(item, view)
				return
			}
			onBooruTileClick(item)
			return
		}
		super.onItemClick(item, view)
	}

	override fun onItemLongClick(item: MangaListModel, view: View): Boolean {
		if (viewModel.isBooru && item is BooruGridModel) {
			return when (settings.booruLongPressAction) {
				BooruLongPressAction.DOWNLOAD -> {
					viewModel.saveBooruImage(pageSaveHelper, item.manga)
					true
				}
				BooruLongPressAction.MENU -> {
					showBooruTileMenu(item, view)
					true
				}
				BooruLongPressAction.SELECT -> super.onItemLongClick(item, view)
			}
		}
		return super.onItemLongClick(item, view)
	}

	/**
	 * Booru tile tap routing (media system spec): GIF and VIDEO posts are
	 * handled by their configured tap actions and never reach the reader;
	 * static posts keep the default detail side panel.
	 */
	private fun onBooruTileClick(model: BooruGridModel) {
		when (model.mediaType) {
			BooruMediaType.GIF -> when (settings.mediaGifTapAction) {
				GifTapAction.INLINE -> {
					val adapter = booruAdapter
					if (adapter != null && adapter.inlineGifRegistry.isResolved(model.manga.id)) {
						// already loaded inline — second tap expands to the player
						viewModel.routeBooruPost(model.manga) { openDetailsOrPreview(model) }
					} else {
						adapter?.markInlineGifRequested(model.manga.id)
						viewModel.beginInlineGifLoad(model.manga)
					}
				}
				GifTapAction.OPEN_DETAIL -> openDetailsOrPreview(model)
			}
			BooruMediaType.VIDEO -> when (settings.mediaVideoTapAction) {
				VideoTapAction.PLAY_IN_APP -> viewModel.routeBooruPost(model.manga) { openDetailsOrPreview(model) }
				VideoTapAction.ADD_TO_QUEUE -> viewModel.enqueueBooruPost(model.manga)
				VideoTapAction.OPEN_DETAIL -> openDetailsOrPreview(model)
			}
			null -> openDetailsOrPreview(model)
		}
	}

	private fun openDetailsOrPreview(model: BooruGridModel) {
		val manga = model.toMangaWithOverride()
		if ((activity as? MangaListActivity)?.showPreview(manga) != true) {
			router.openDetails(manga)
		}
	}

	/**
	 * "Add to queue" for the booru media service: binds on demand, appends,
	 * immediately releases the binding. The started service keeps the queue alive.
	 */
	private fun enqueueIntoService(item: org.wastaken.kotatsu.api21.booru.media.BooruMediaItem) {
		val context = context ?: return
		BooruMediaService.start(context)
		context.bindService(
			android.content.Intent(context, BooruMediaService::class.java),
			object : android.content.ServiceConnection {
				override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
					(binder as? BooruMediaService.LocalBinder)?.service?.queue?.add(item)
					runCatching { context.unbindService(this) }
					Snackbar.make(requireViewBinding().recyclerView, R.string.media_queued_toast, Snackbar.LENGTH_SHORT).show()
				}

				override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit
			},
			android.content.Context.BIND_AUTO_CREATE,
		)
	}

	private fun showBooruTileMenu(model: BooruGridModel, anchor: View) {
		val menu = PopupMenu(requireContext(), anchor)
		val isMedia = model.mediaType != null
		// play/queue honor the per-source media-player setting, not just the
		// content-type badge: a disabled source keeps details/save only
		val mediaEnabled = isMedia && settings.isMediaPlayerEnabledForSource(model.manga.source)
		menu.menu.add(0, ACTION_PLAY, 0, R.string.play).isEnabled = mediaEnabled
		menu.menu.add(0, ACTION_ADD_TO_QUEUE, 1, R.string.media_add_to_queue).isEnabled = mediaEnabled
		menu.menu.add(0, ACTION_SAVE, 2, R.string.media_save_image_video)
		menu.menu.add(0, ACTION_OPEN_DETAIL, 3, R.string.media_tap_open_detail)
		menu.setOnMenuItemClickListener { menuItem ->
			when (menuItem.itemId) {
				ACTION_PLAY -> viewModel.routeBooruPost(model.manga) { openDetailsOrPreview(model) }
				ACTION_ADD_TO_QUEUE -> if (mediaEnabled) viewModel.enqueueBooruPost(model.manga)
				ACTION_SAVE -> viewModel.saveBooruImage(pageSaveHelper, model.manga)
				ACTION_OPEN_DETAIL -> openDetailsOrPreview(model)
			}
			true
		}
		menu.show()
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_remote, menu)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}

	override fun onFilterClick(view: View?) {
		router.showFilterSheet()
	}

	override fun onEmptyActionClick() {
		if (filterCoordinator.isFilterApplied) {
			filterCoordinator.reset()
		} else {
			openInBrowser(null) // should never be called
		}
	}

	override fun onFooterButtonClick() {
		val filter = filterCoordinator.snapshot().listFilter
		when {
			!filter.query.isNullOrEmpty() -> router.openSearch(filter.query.orEmpty(), SearchKind.SIMPLE)
			!filter.author.isNullOrEmpty() -> router.openSearch(filter.author.orEmpty(), SearchKind.AUTHOR)
			filter.tags.size == 1 -> router.openSearch(filter.tags.singleOrNull()?.title.orEmpty(), SearchKind.TAG)
		}
	}

	override fun onSecondaryErrorActionClick(error: Throwable) {
		openInBrowser(error.getCauseUrl())
	}

	private fun openInBrowser(url: String?) {
		if (url?.isHttpUrl() == true) {
			router.openBrowser(
				url = url,
				source = viewModel.source,
				title = viewModel.source.getTitle(requireContext()),
			)
		} else {
			Snackbar.make(requireViewBinding().recyclerView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT)
				.show()
		}
	}

	private inner class RemoteListMenuProvider : MenuProvider {

		override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
			menuInflater.inflate(R.menu.opt_list_remote, menu)
		}

		override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
			R.id.action_source_settings -> {
				router.openSourceSettings(viewModel.source)
				true
			}

			R.id.action_random -> {
				viewModel.openRandom()
				true
			}

			R.id.action_filter -> {
				onFilterClick(null)
				true
			}

			R.id.action_filter_reset -> {
				filterCoordinator.reset()
				true
			}

			else -> false
		}

		override fun onPrepareMenu(menu: Menu) {
			super.onPrepareMenu(menu)
			menu.findItem(R.id.action_random)?.isEnabled = !viewModel.isRandomLoading.value
			menu.findItem(R.id.action_filter_reset)?.isVisible = filterCoordinator.isFilterApplied
		}
	}

	companion object {

		const val ARG_SOURCE = "provider"

		/** Extra recycled views kept around to avoid rebinding on slow scroll (weak hardware). */
		private const val BOORU_VIEW_CACHE_SIZE = 6

		// booru tile long-press menu ids (PopupMenu, no xml — actions compose at runtime
		// because Play/Add-to-queue enablement depends on the tile's media classification)
		private const val ACTION_PLAY = 1
		private const val ACTION_ADD_TO_QUEUE = 2
		private const val ACTION_SAVE = 3
		private const val ACTION_OPEN_DETAIL = 4

		fun newInstance(source: MangaSource) = RemoteListFragment().withArgs(1) {
			putString(ARG_SOURCE, source.name)
		}
	}
}
