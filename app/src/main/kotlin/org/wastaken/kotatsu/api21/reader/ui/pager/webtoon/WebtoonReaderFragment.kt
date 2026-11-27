package org.wastaken.kotatsu.api21.reader.ui.pager.webtoon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.core.os.NetworkState
import org.wastaken.kotatsu.api21.core.ui.list.lifecycle.RecyclerViewLifecycleDispatcher
import org.wastaken.kotatsu.api21.core.util.ext.firstVisibleItemPosition
import org.wastaken.kotatsu.api21.core.util.ext.observe
import org.wastaken.kotatsu.api21.core.util.ext.removeItemDecoration
import org.wastaken.kotatsu.api21.databinding.FragmentReaderWebtoonBinding
import org.wastaken.kotatsu.api21.reader.domain.PageLoader
import org.wastaken.kotatsu.api21.reader.ui.ReaderState
import org.wastaken.kotatsu.api21.reader.ui.pager.BaseReaderAdapter
import org.wastaken.kotatsu.api21.reader.ui.pager.BaseReaderFragment
import org.wastaken.kotatsu.api21.reader.ui.pager.ReaderPage
import org.wastaken.kotatsu.api21.reader.ui.media.ReaderSharedMediaController
import javax.inject.Inject

@AndroidEntryPoint
class WebtoonReaderFragment : BaseReaderFragment<FragmentReaderWebtoonBinding>(),
	WebtoonRecyclerView.OnWebtoonScrollListener {

	@Inject
	lateinit var networkState: NetworkState

	@Inject
	lateinit var pageLoader: PageLoader

	private val scrollInterpolator = DecelerateInterpolator()

	private var recyclerLifecycleDispatcher: RecyclerViewLifecycleDispatcher? = null

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentReaderWebtoonBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentReaderWebtoonBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		// webtoon videos attach from the center-scroll detector, never self-start
		ReaderSharedMediaController.get(requireContext()).mode = ReaderSharedMediaController.Mode.WEBTOON
		with(binding.recyclerView) {
			setHasFixedSize(true)
			adapter = readerAdapter
			addOnPageScrollListener(this@WebtoonReaderFragment)
			recyclerLifecycleDispatcher = RecyclerViewLifecycleDispatcher().also {
				addOnScrollListener(it)
			}
		}
		viewModel.isWebtoonZooEnabled.observe(viewLifecycleOwner) {
			binding.frame.isZoomEnable = it
		}
		viewModel.defaultWebtoonZoomOut.take(1).observe(viewLifecycleOwner) {
			binding.frame.zoom = 1f - it
		}
		viewModel.isWebtoonGapsEnabled.observe(viewLifecycleOwner) {
			val rv = binding.recyclerView
			rv.removeItemDecoration(WebtoonGapsDecoration::class.java)
			if (it) {
				rv.addItemDecoration(WebtoonGapsDecoration())
			}
		}
		viewModel.readerSettingsProducer.observe(viewLifecycleOwner) {
			it.applyBackground(binding.root)
		}
	}

	override fun onDestroyView() {
		recyclerLifecycleDispatcher = null
		requireViewBinding().recyclerView.adapter = null
		super.onDestroyView()
	}

	override fun onCreateAdapter() = WebtoonAdapter(
		lifecycleOwner = viewLifecycleOwner,
		loader = pageLoader,
		readerSettingsProducer = viewModel.readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
	)

	override fun onScrollChanged(
		recyclerView: WebtoonRecyclerView,
		dy: Int,
		firstVisiblePosition: Int,
		lastVisiblePosition: Int,
	) {
		viewModel.onCurrentPageChanged(firstVisiblePosition, lastVisiblePosition)
		updateCenterVideo(recyclerView)
	}

	/** The holder whose video currently owns the shared engine (center-most page). */
	private var centerVideoHolder: WebtoonHolder? = null

	/**
	 * TSD-BOORU-MMBD-001 §3.3 center detection: the visible child nearest the
	 * viewport center gets the shared video engine; when the center moves to a
	 * different page the previous one is paused + detached. Non-video centers
	 * just release the current owner.
	 */
	private fun updateCenterVideo(recyclerView: WebtoonRecyclerView) {
		val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
		val centerY = recyclerView.height / 2
		var bestHolder: WebtoonHolder? = null
		var minDistance = Int.MAX_VALUE
		for (i in 0 until lm.childCount) {
			val child = lm.getChildAt(i) ?: continue
			val childCenterY = (child.top + child.bottom) / 2
			val distance = kotlin.math.abs(childCenterY - centerY)
			if (distance < minDistance) {
				minDistance = distance
				bestHolder = recyclerView.getChildViewHolder(child) as? WebtoonHolder
			}
		}
		val previous = centerVideoHolder
		if (previous === bestHolder) return
		previous?.onCenterViewportExit()
		centerVideoHolder = bestHolder
		if (bestHolder != null) {
			bestHolder.onCenterViewportEnter()
		} else {
			ReaderSharedMediaController.get(requireContext()).detachAny()
		}
	}

	// explicit Unit: the trailing post{} returns Boolean?, which would otherwise leak into the inferred return type
	override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?): Unit = coroutineScope {
		val setItems = launch {
			requireAdapter().setItems(pages)
			yield()
			viewBinding?.recyclerView?.let { rv ->
				recyclerLifecycleDispatcher?.invalidate(rv)
			}
		}
		if (pendingState != null) {
			val position = pages.indexOfFirst {
				it.chapterId == pendingState.chapterId && it.index == pendingState.page
			}
			setItems.join()
			if (position != -1) {
				with(requireViewBinding().recyclerView) {
					firstVisibleItemPosition = position
					post {
						(findViewHolderForAdapterPosition(position) as? WebtoonHolder)
							?.restoreScroll(pendingState.scroll)
					}
				}
				viewModel.onCurrentPageChanged(position, position)
			} else {
				Snackbar.make(requireView(), R.string.not_found_404, Snackbar.LENGTH_SHORT)
					.show()
			}
		} else {
			setItems.join()
		}
		// initial center evaluation: no scroll event fires for the first layout
		viewBinding?.recyclerView?.post {
			viewBinding?.recyclerView?.let { updateCenterVideo(it) }
		}
	}

	override fun getCurrentState(): ReaderState? = viewBinding?.run {
		val currentItem = recyclerView.findCurrentPagePosition()
		val adapter = recyclerView.adapter as? BaseReaderAdapter<*>
		val page = adapter?.getItemOrNull(currentItem) ?: return@run null
		ReaderState(
			chapterId = page.chapterId,
			page = page.index,
			scroll = (recyclerView.findViewHolderForAdapterPosition(currentItem) as? WebtoonHolder)?.getScrollY() ?: 0,
		)
	}

	override fun onZoomIn() {
		viewBinding?.frame?.onZoomIn()
	}

	override fun onZoomOut() {
		viewBinding?.frame?.onZoomOut()
	}

	override fun switchPageBy(delta: Int) {
		with(requireViewBinding().recyclerView) {
			if (isAnimationEnabled()) {
				smoothScrollBy(0, (height * 0.9).toInt() * delta, scrollInterpolator)
			} else {
				nestedScrollBy(0, (height * 0.9).toInt() * delta)
			}
		}
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		requireViewBinding().recyclerView.firstVisibleItemPosition = position
	}

	override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
		if (smooth && isAnimationEnabled()) {
			requireViewBinding().recyclerView.smoothScrollBy(0, delta, scrollInterpolator)
		} else {
			requireViewBinding().recyclerView.nestedScrollBy(0, delta)
		}
		return true
	}

	private fun RecyclerView.findCurrentPagePosition(): Int {
		val centerX = width / 2f
		val centerY = height - resources.getDimension(R.dimen.webtoon_pages_gap)
		if (centerY <= 0) {
			return RecyclerView.NO_POSITION
		}
		val view = findChildViewUnder(centerX, centerY) ?: return RecyclerView.NO_POSITION
		return getChildAdapterPosition(view)
	}
}
