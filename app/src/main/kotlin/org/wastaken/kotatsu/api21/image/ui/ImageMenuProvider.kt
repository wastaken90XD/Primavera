package org.wastaken.kotatsu.api21.image.ui

import android.Manifest
import android.os.Build
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import com.google.android.material.snackbar.Snackbar
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.util.ext.isNetworkUri
import org.wastaken.kotatsu.api21.core.util.ext.isZipUri
import org.wastaken.kotatsu.api21.core.util.ext.tryLaunch
import org.wastaken.kotatsu.api21.reader.ui.media.isBooruSource

class ImageMenuProvider(
	private val activity: ComponentActivity,
	private val snackbarHost: View,
	private val viewModel: ImageViewModel,
	private val settings: AppSettings,
) : MenuProvider {

	private val permissionLauncher = activity.registerForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) { isGranted ->
		if (isGranted) {
			saveImage()
		}
	}

	private val saveLauncher = activity.registerForActivityResult(
		ActivityResultContracts.CreateDocument("image/*"),
	) { uri ->
		if (uri != null) {
			viewModel.saveImage(uri)
		}
	}

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_image, menu)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_save -> {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
				permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
			} else {
				saveImage()
			}
			true
		}

		else -> false
	}

	private fun saveImage() {
		val data = activity.intent.data
		// must mirror ImageViewModel.saveImage's gate exactly (booru-only original bytes)
		val isOriginal = settings.isPagesSaveOriginalEnabled &&
			viewModel.source.isBooruSource() &&
			data?.isNetworkUri() == true
		val name = data?.let {
			if (it.isZipUri()) {
				it.fragment
			} else {
				it.lastPathSegment
			}
		}?.let { rawName ->
			val extension = if (isOriginal) {
				rawName.substringAfterLast('.', "").takeIf { it.length in 2..4 }
			} else {
				null
			}
			rawName.substringBeforeLast('.') + "." + (extension ?: "png")
		}
		if (name == null || !saveLauncher.tryLaunch(name)) {
			Snackbar.make(snackbarHost, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		}
	}
}
