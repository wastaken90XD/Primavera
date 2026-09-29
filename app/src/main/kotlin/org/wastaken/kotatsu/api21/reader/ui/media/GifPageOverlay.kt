package org.wastaken.kotatsu.api21.reader.ui.media

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import coil3.asDrawable
import coil3.decode.ImageSource
import coil3.gif.GifDecoder
import coil3.request.Options
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.requireBody
import org.wastaken.kotatsu.api21.core.util.ext.ensureSuccess
import org.wastaken.kotatsu.api21.core.util.ext.getDisplayMessage
import org.wastaken.kotatsu.api21.databinding.LayoutBooruGifOverlayBinding
import org.wastaken.kotatsu.api21.reader.domain.PageLoader
import org.wastaken.kotatsu.api21.reader.ui.pager.ReaderPage

/**
 * Explicit-load GIF support for booru reader pages. **Booru sources only**
 * (source check first, always — see BooruMedia.kt).
 *
 * GIFs never load or animate automatically: a static placeholder with a
 * "Load GIF" button replaces the page until the user's explicit tap, and the
 * normal image pipeline stays suppressed (no prefetch, no page download).
 *
 * Nothing is persisted for playback: the GIF is streamed over OkHttp with the
 * source's headers (no image proxy — it cannot optimize animations anyway) and
 * decoded straight from the network stream; the response body is read exactly
 * once by [GifDecoder] (`Movie.decodeStream` is a single sequential pass, so a
 * streaming [ImageSource] is enough and [ImageSource.file] is never called,
 * which means no temporary file is created). The full file exists on the device
 * ONLY when the user explicitly downloads it elsewhere. Decoded frames are
 * dropped on rebind/recycle by design (low-RAM policy).
 *
 * The raw [GifDecoder] path is also what the base app already uses below API
 * 28, so this is the oldest-compatible route by construction. The resulting
 * MovieDrawable is started/stopped explicitly (Coil normally does this inside
 * its own ViewTarget pipeline, which we bypass on purpose).
 */
class GifPageOverlay(
	private val binding: LayoutBooruGifOverlayBinding,
	private val lifecycleOwner: LifecycleOwner,
) : DefaultLifecycleObserver {

	var isHandling = false
		private set

	private val entryPoint: ReaderMediaEntryPoint by lazy(LazyThreadSafetyMode.NONE) {
		EntryPointAccessors.fromApplication(
			binding.root.context.applicationContext,
			ReaderMediaEntryPoint::class.java,
		)
	}

	private var loadJob: Job? = null

	/** Last decoded animation; stopped explicitly on reset (frame callbacks do not stop on their own). */
	private var animatedDrawable: Drawable? = null

	init {
		lifecycleOwner.lifecycle.addObserver(this)
	}

	/** @return true if this overlay handles the page and the normal page load must be suppressed. */
	fun onBind(page: ReaderPage): Boolean {
		reset()
		// source check comes first, always (see BooruMedia.kt)
		isHandling = page.source.isMediaPlayerEnabled(entryPoint.settings()) && page.url.looksLikeGif()
		if (!isHandling) {
			return false
		}
		binding.root.isVisible = true
		binding.panelGif.isVisible = true
		binding.progressGif.isGone = true
		binding.buttonLoadGif.isVisible = true
		binding.gifImageView.isGone = true
		binding.buttonLoadGif.setOnClickListener { load(page) }
		return true
	}

	fun onRecycled() {
		reset()
	}

	override fun onPause(owner: LifecycleOwner) {
		stopAnimation()
	}

	override fun onStop(owner: LifecycleOwner) {
		stopAnimation()
	}

	override fun onResume(owner: LifecycleOwner) {
		val drawable = animatedDrawable ?: return
		if (binding.gifImageView.isVisible) {
			(drawable as? Animatable2Compat)?.start()
			(drawable as? Animatable)?.start()
		}
	}

	override fun onDestroy(owner: LifecycleOwner) {
		reset()
		lifecycleOwner.lifecycle.removeObserver(this)
	}

	private fun reset() {
		loadJob?.cancel()
		loadJob = null
		stopAnimation()
		animatedDrawable = null
		binding.gifImageView.setImageDrawable(null)
		binding.root.isGone = true
	}

	private fun stopAnimation() {
		(animatedDrawable as? Animatable2Compat)?.stop()
		(animatedDrawable as? Animatable)?.stop()
	}

	private fun load(page: ReaderPage) {
		if (loadJob?.isActive == true) {
			return
		}
		loadJob = lifecycleOwner.lifecycleScope.launch {
			binding.buttonLoadGif.isGone = true
			binding.progressGif.isVisible = true
			try {
				// network + decode are off-thread; nothing is written to any cache
				val drawable = withContext(Dispatchers.Default) {
					val request = PageLoader.createPageRequest(page.url, page.source)
					entryPoint.okHttpClient().newCall(request).await().use { response ->
						response.ensureSuccess()
						response.requireBody().use { body ->
							ImageSource(body.source(), FileSystem.SYSTEM).use { imageSource ->
								GifDecoder(
									imageSource,
									Options(binding.root.context.applicationContext),
								).decode().image.asDrawable(binding.root.resources)
							}
						}
					}
				}
				binding.gifImageView.setImageDrawable(drawable)
				when (drawable) {
					is Animatable2Compat -> drawable.start()
					is Animatable -> drawable.start()
				}
				animatedDrawable = drawable
				binding.panelGif.isGone = true
				binding.progressGif.isGone = true
				binding.gifImageView.isVisible = true
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				onError(e)
			}
		}
	}

	private fun onError(e: Throwable) {
		binding.progressGif.isGone = true
		binding.buttonLoadGif.isVisible = true
		Snackbar.make(
			binding.root,
			e.getDisplayMessage(binding.root.resources),
			Snackbar.LENGTH_SHORT,
		).show()
	}
}
