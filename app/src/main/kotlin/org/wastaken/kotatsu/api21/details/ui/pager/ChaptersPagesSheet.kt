package org.wastaken.kotatsu.api21.details.ui.pager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import org.wastaken.kotatsu.api21.core.exceptions.resolve.SnackbarErrorObserver
import org.wastaken.kotatsu.api21.core.nav.AppRouter
import org.wastaken.kotatsu.api21.booru.media.ui.BooruPlayerActivity
import org.wastaken.kotatsu.api21.core.nav.router
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_COLLAPSED
import org.wastaken.kotatsu.api21.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_DRAGGING
import org.wastaken.kotatsu.api21.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_EXPANDED
import org.wastaken.kotatsu.api21.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_SETTLING
import org.wastaken.kotatsu.api21.core.ui.sheet.AdaptiveSheetCallback
import org.wastaken.kotatsu.api21.core.ui.sheet.BaseAdaptiveSheet
import org.wastaken.kotatsu.api21.core.ui.util.ActionModeListener
import org.wastaken.kotatsu.api21.core.ui.util.MenuInvalidator
import org.wastaken.kotatsu.api21.core.ui.util.RecyclerViewOwner
import org.wastaken.kotatsu.api21.core.ui.util.ReversibleActionObserver
import org.wastaken.kotatsu.api21.core.util.ext.doOnPageChanged
import org.wastaken.kotatsu.api21.core.util.ext.findCurrentPagerFragment
import org.wastaken.kotatsu.api21.core.util.ext.menuView
import org.wastaken.kotatsu.api21.core.util.ext.observe
import org.wastaken.kotatsu.api21.core.util.ext.observeEvent
import org.wastaken.kotatsu.api21.core.util.ext.recyclerView
import org.wastaken.kotatsu.api21.core.util.ext.smoothScrollToTop
import org.wastaken.kotatsu.api21.databinding.SheetChaptersPagesBinding
import org.wastaken.kotatsu.api21.details.ui.DetailsViewModel
import org.wastaken.kotatsu.api21.details.ui.ReadButtonDelegate
import org.wastaken.kotatsu.api21.download.ui.worker.DownloadStartedObserver
import javax.inject.Inject

@AndroidEntryPoint
class ChaptersPagesSheet : BaseAdaptiveSheet<SheetChaptersPagesBinding>(),
	TabLayout.OnTabSelectedListener,
	ActionModeListener,
	AdaptiveSheetCallback {

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by ChaptersPagesViewModel.ActivityVMLazy(this)

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetChaptersPagesBinding {
		return SheetChaptersPagesBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetChaptersPagesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		disableFitToContents()

		val args = arguments ?: Bundle.EMPTY
		var defaultTab = args.getInt(AppRouter.KEY_TAB, settings.defaultDetailsTab)
		val adapter = ChaptersPagesAdapter(this, settings.isPagesTabEnabled)
		if (!adapter.isPagesTabEnabled) {
			defaultTab = (defaultTab - 1).coerceAtLeast(TAB_CHAPTERS)
		}
		(viewModel as? DetailsViewModel)?.let { dvm ->
			ReadButtonDelegate(binding.splitButtonRead, dvm, router).attach(viewLifecycleOwner)
			dvm.onBooruMediaRoute.observeEvent(viewLifecycleOwner) { item ->
				BooruPlayerActivity.start(requireContext(), item)
			}
		}
		binding.pager.offscreenPageLimit = adapter.itemCount
		binding.pager.recyclerView?.isNestedScrollingEnabled = false
		binding.pager.adapter = adapter
		binding.pager.doOnPageChanged(::onPageChanged)
		TabLayoutMediator(binding.tabs, binding.pager, adapter).attach()
		binding.tabs.addOnTabSelectedListener(this)
		binding.pager.setCurrentItem(defaultTab, false)
		binding.tabs.isVisible = adapter.itemCount > 1

		val menuProvider = ChapterPagesMenuProvider(viewModel, this, binding.pager, settings)
		onBackPressedDispatcher.addCallback(viewLifecycleOwner, menuProvider)
		binding.toolbar.addMenuProvider(menuProvider)

		val menuInvalidator = MenuInvalidator(binding.toolbar)
		viewModel.isChaptersReversed.observe(viewLifecycleOwner, menuInvalidator)
		viewModel.isChaptersInGridView.observe(viewLifecycleOwner, menuInvalidator)
		viewModel.isDownloadedOnly.observe(viewLifecycleOwner, menuInvalidator)

		actionModeDelegate?.addListener(this, viewLifecycleOwner)
		addSheetCallback(this, viewLifecycleOwner)

		viewModel.newChaptersCount.observe(viewLifecycleOwner, ::onNewChaptersChanged)
		if (dialog != null) {
			viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.pager, this))
			viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
			viewModel.onDownloadStarted.observeEvent(viewLifecycleOwner, DownloadStartedObserver(binding.pager))
		} else {
			PeekHeightController(arrayOf(binding.headerBar, binding.toolbar)).attach()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onStateChanged(sheet: View, newState: Int) {
		if (newState == STATE_DRAGGING || newState == STATE_SETTLING) {
			return
		}
		val binding = viewBinding ?: return
		val isActionModeStarted = actionModeDelegate?.isActionModeStarted == true
		binding.toolbar.menuView?.isVisible = newState == STATE_EXPANDED && !isActionModeStarted
		binding.splitButtonRead.isVisible = newState != STATE_EXPANDED && !isActionModeStarted
			&& viewModel is DetailsViewModel
	}

	override fun onActionModeStarted(mode: ActionMode) {
		viewBinding?.toolbar?.menuView?.isVisible = false
		view?.post(::expandAndLock)
	}

	override fun onActionModeFinished(mode: ActionMode) {
		unlock()
		val state = behavior?.state ?: STATE_EXPANDED
		viewBinding?.toolbar?.menuView?.isVisible = state != STATE_COLLAPSED
	}

	override fun onTabSelected(tab: TabLayout.Tab?) = Unit

	override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

	override fun onTabReselected(tab: TabLayout.Tab?) {
		val f = childFragmentManager.findCurrentPagerFragment(
			viewBinding?.pager ?: return,
		) as? RecyclerViewOwner ?: return
		f.recyclerView?.smoothScrollToTop()
	}

	override fun expandAndLock() {
		super.expandAndLock()
		adjustLockState()
	}

	override fun unlock() {
		super.unlock()
		adjustLockState()
	}

	private fun adjustLockState() {
		viewBinding?.run {
			pager.isUserInputEnabled = !isLocked
			tabs.visibility = when {
				(pager.adapter?.itemCount ?: 0) <= 1 -> View.GONE
				isLocked -> View.INVISIBLE
				else -> View.VISIBLE
			}
		}
	}

	private fun onPageChanged(position: Int) {
		viewBinding?.toolbar?.invalidateMenu()
		settings.lastDetailsTab = position
	}

	private fun onNewChaptersChanged(counter: Int) {
		val tab = viewBinding?.tabs?.getTabAt(0) ?: return
		if (counter == 0) {
			tab.removeBadge()
		} else {
			val badge = tab.orCreateBadge
			badge.number = counter
		}
	}

	companion object {

		const val TAB_CHAPTERS = 0
		const val TAB_PAGES = 1
		const val TAB_BOOKMARKS = 2
	}
}
