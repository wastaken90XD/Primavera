package org.wastaken.kotatsu.api21.reader.ui.pager

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.annotation.CallSuper
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.wastaken.kotatsu.api21.BuildConfig
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.core.exceptions.resolve.ExceptionResolver
import org.wastaken.kotatsu.api21.core.os.NetworkState
import org.wastaken.kotatsu.api21.core.ui.list.lifecycle.LifecycleAwareViewHolder
import org.wastaken.kotatsu.api21.core.util.ext.getDisplayMessage
import org.wastaken.kotatsu.api21.core.util.ext.isLowRamDevice
import org.wastaken.kotatsu.api21.core.util.ext.isSerializable
import org.wastaken.kotatsu.api21.core.util.ext.observe
import org.wastaken.kotatsu.api21.databinding.LayoutBooruGifOverlayBinding
import org.wastaken.kotatsu.api21.databinding.LayoutBooruVideoOverlayBinding
import org.wastaken.kotatsu.api21.databinding.LayoutPageInfoBinding
import org.koitharu.kotatsu.parsers.util.ifZero
import org.wastaken.kotatsu.api21.reader.domain.PageLoader
import org.wastaken.kotatsu.api21.reader.ui.config.ReaderSettings
import org.wastaken.kotatsu.api21.reader.ui.media.GifPageOverlay
import org.wastaken.kotatsu.api21.reader.ui.media.VideoPageOverlay
import org.wastaken.kotatsu.api21.reader.ui.pager.vm.PageState
import org.wastaken.kotatsu.api21.reader.ui.pager.vm.PageViewModel
import org.wastaken.kotatsu.api21.reader.ui.pager.webtoon.WebtoonHolder

abstract class BasePageHolder<B : ViewBinding>(
	protected val binding: B,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
	lifecycleOwner: LifecycleOwner,
) : LifecycleAwareViewHolder(binding.root, lifecycleOwner), DefaultOnImageEventListener, ComponentCallbacks2 {

	protected val viewModel = PageViewModel(
		loader = loader,
		settingsProducer = readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
		isWebtoon = this is WebtoonHolder,
	)
	protected val bindingInfo = LayoutPageInfoBinding.bind(binding.root)
	// The overlay bindings MUST be created from the overlay's own subtree root
	// (found by id): ViewBinding.bind(rootView) returns a binding whose root is
	// the passed view, so binding the whole page root would let the overlay
	// hide the entire page (blank reader regression).
	private val gifOverlay = GifPageOverlay(
		LayoutBooruGifOverlayBinding.bind(
			requireNotNull(binding.root.findViewById(R.id.booru_gif_overlay_root)),
		),
		this,
	)
	private val videoOverlay = VideoPageOverlay(
		LayoutBooruVideoOverlayBinding.bind(
			requireNotNull(binding.root.findViewById(R.id.booru_video_overlay_root)),
		),
		this,
	)
	protected abstract val ssiv: SubsamplingScaleImageView

	protected val settings: ReaderSettings
		get() = viewModel.settingsProducer.value

	val context: Context
		get() = itemView.context

	var boundData: ReaderPage? = null
		private set

	init {
		lifecycleScope.launch(Dispatchers.Main) {
			ssiv.bindToLifecycle(this@BasePageHolder)
			ssiv.isEagerLoadingEnabled = !context.isLowRamDevice()
			ssiv.addOnImageEventListener(viewModel)
			ssiv.addOnImageEventListener(this@BasePageHolder)
		}
		val clickListener = View.OnClickListener { v ->
			when (v.id) {
				R.id.button_retry -> viewModel.retry(
					page = boundData?.toMangaPage() ?: return@OnClickListener,
					isFromUser = true,
				)

				R.id.button_error_details -> viewModel.showErrorDetails(boundData?.url)
			}
		}
		bindingInfo.buttonRetry.setOnClickListener(clickListener)
		bindingInfo.buttonErrorDetails.setOnClickListener(clickListener)
	}

	@CallSuper
	protected open fun onConfigChanged(settings: ReaderSettings) {
		settings.applyBackground(itemView)
		if (settings.applyBitmapConfig(ssiv)) {
			reloadImage()
		} else if (viewModel.state.value is PageState.Shown) {
			onReady()
		}
		ssiv.applyDownSampling(isResumed())
	}

	fun reloadImage() {
		val source = (viewModel.state.value as? PageState.Shown)?.source ?: return
		ssiv.setImage(source)
	}

	fun bind(data: ReaderPage) {
		boundData = data
		// both overlays must always be told about the bind (they reset themselves
		// internally, so evaluate eagerly without short-circuiting); a page
		// cannot be a gif and a video at once
		val gifHandled = gifOverlay.onBind(data)
		val videoHandled = videoOverlay.onBind(data)
		val mediaHandled = gifHandled || videoHandled
		if (mediaHandled) {
			// media pages (gif/video) are explicit-load only: the automatic page
			// pipeline is suppressed and the overlay drives everything from here
			viewModel.onRecycle()
			ssiv.recycle()
		} else {
			viewModel.onBind(data.toMangaPage())
		}
		onBind(data)
	}

	@CallSuper
	protected open fun onBind(data: ReaderPage) = Unit

	override fun onCreate() {
		super.onCreate()
		context.registerComponentCallbacks(this)
		viewModel.state.observe(this, ::onStateChanged)
		viewModel.settingsProducer.observe(this, ::onConfigChanged)
	}

	override fun onResume() {
		super.onResume()
		ssiv.applyDownSampling(isForeground = true)
		if (viewModel.state.value is PageState.Error && !viewModel.isLoading()) {
			boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false) }
		}
	}

	override fun onPause() {
		super.onPause()
		ssiv.applyDownSampling(isForeground = false)
	}

	override fun onDestroy() {
		context.unregisterComponentCallbacks(this)
		super.onDestroy()
	}

	open fun onAttachedToWindow() = Unit

	open fun onDetachedFromWindow() = Unit

	@CallSuper
	open fun onRecycled() {
		gifOverlay.onRecycled()
		videoOverlay.onRecycled()
		viewModel.onRecycle()
		ssiv.recycle()
	}

	override fun onTrimMemory(level: Int) {
		// TODO
	}

	override fun onConfigurationChanged(newConfig: Configuration) = Unit

	@Deprecated("Deprecated in Java")
	final override fun onLowMemory() = onTrimMemory(TRIM_MEMORY_COMPLETE)

	protected open fun onStateChanged(state: PageState) {
		if (gifOverlay.isHandling || videoOverlay.isHandling) {
			// media pages are driven by their overlay, not by the page state flow
			bindingInfo.layoutError.isGone = true
			bindingInfo.layoutProgress.isGone = true
			return
		}
		bindingInfo.layoutError.isVisible = state is PageState.Error
		bindingInfo.layoutProgress.isGone = state.isFinalState()
		val progress = (state as? PageState.Loading)?.progress ?: -1
		if (progress in 0..100) {
			bindingInfo.progressBar.isIndeterminate = false
			bindingInfo.progressBar.setProgressCompat(progress, true)
			bindingInfo.textViewStatus.text = context.getString(R.string.percent_string_pattern, progress.toString())
		} else {
			bindingInfo.progressBar.isIndeterminate = true
			bindingInfo.textViewStatus.setText(R.string.loading_)
		}
		when (state) {
			is PageState.Converting -> {
				bindingInfo.textViewStatus.setText(R.string.processing_)
			}

			is PageState.Empty -> Unit

			is PageState.Error -> {
				val e = state.error
				bindingInfo.textViewError.text = e.getDisplayMessage(context.resources)
				bindingInfo.buttonRetry.setText(
					ExceptionResolver.getResolveStringId(e).ifZero { R.string.try_again },
				)
				bindingInfo.buttonErrorDetails.isVisible = e.isSerializable()
				bindingInfo.layoutError.isVisible = true
				bindingInfo.progressBar.hide()
			}

			is PageState.Loaded -> {
				bindingInfo.textViewStatus.setText(R.string.preparing_)
				ssiv.setImage(state.source)
			}

			is PageState.Loading -> {
				if (state.preview != null && ssiv.getState() == null) {
					ssiv.setImage(state.preview)
				}
			}

			is PageState.Shown -> Unit
		}
	}

	protected fun SubsamplingScaleImageView.applyDownSampling(isForeground: Boolean) {
		downSampling = when {
			isForeground || !settings.isReaderOptimizationEnabled -> 1
			BuildConfig.DEBUG -> 32
			context.isLowRamDevice() -> 8
			else -> 4
		}
	}
}
