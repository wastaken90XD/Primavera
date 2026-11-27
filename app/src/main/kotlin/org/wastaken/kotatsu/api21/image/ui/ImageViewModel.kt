package org.wastaken.kotatsu.api21.image.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.SavedStateHandle
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.wastaken.kotatsu.api21.core.model.MangaSource
import org.wastaken.kotatsu.api21.core.nav.AppRouter
import org.wastaken.kotatsu.api21.core.network.OriginalImageDownloader
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.ui.BaseViewModel
import org.wastaken.kotatsu.api21.core.util.ext.MutableEventFlow
import org.wastaken.kotatsu.api21.core.util.ext.call
import org.wastaken.kotatsu.api21.core.util.ext.getDrawableOrThrow
import org.wastaken.kotatsu.api21.core.util.ext.isNetworkUri
import org.wastaken.kotatsu.api21.core.util.ext.mangaSourceExtra
import org.wastaken.kotatsu.api21.core.util.ext.require
import org.wastaken.kotatsu.api21.reader.ui.media.isBooruSource
import javax.inject.Inject

@HiltViewModel
class ImageViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val savedStateHandle: SavedStateHandle,
	private val coil: ImageLoader,
	private val settings: AppSettings,
	private val originalImageDownloader: OriginalImageDownloader,
) : BaseViewModel() {

	val onImageSaved = MutableEventFlow<Uri>()

	val source = MangaSource(savedStateHandle[AppRouter.KEY_SOURCE])

	fun saveImage(destination: Uri) {
		launchLoadingJob(Dispatchers.Default) {
			val data = savedStateHandle.require<Uri>(AppRouter.KEY_DATA)
			// original-bytes saving is strictly booru-only: every other source keeps
			// the upstream behaviour (decoded PNG copy), the toggle does not affect it
			if (settings.isPagesSaveOriginalEnabled && source.isBooruSource() && data.isNetworkUri()) {
				originalImageDownloader.download(
					url = data.toString(),
					source = source,
					destination = destination,
				)
			} else {
				val request = ImageRequest.Builder(context)
					.memoryCachePolicy(CachePolicy.READ_ONLY)
					.data(data)
					.memoryCachePolicy(CachePolicy.DISABLED)
					.mangaSourceExtra(source)
					.build()
				val bitmap = coil.execute(request).getDrawableOrThrow().toBitmap()
				runInterruptible(Dispatchers.IO) {
					context.contentResolver.openOutputStream(destination)?.use { output ->
						check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
					} ?: error("Cannot open output stream")
				}
			}
			onImageSaved.call(destination)
		}
	}
}
