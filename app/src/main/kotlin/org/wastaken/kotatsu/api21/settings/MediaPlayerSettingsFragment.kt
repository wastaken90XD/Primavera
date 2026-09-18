package org.wastaken.kotatsu.api21.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.booru.media.BooruMediaImportExport
import org.wastaken.kotatsu.api21.booru.media.BooruMediaImportExport.toStored
import org.wastaken.kotatsu.api21.booru.media.BooruMediaItem
import org.wastaken.kotatsu.api21.booru.media.BooruMediaQueueStore.Companion.toItem
import org.wastaken.kotatsu.api21.booru.media.BooruMediaService
import org.wastaken.kotatsu.api21.booru.media.BooruMediaType
import org.wastaken.kotatsu.api21.booru.media.AspectRatioMode
import org.wastaken.kotatsu.api21.booru.media.BooruLongPressAction
import org.wastaken.kotatsu.api21.booru.media.BooruVideoEngine
import org.wastaken.kotatsu.api21.booru.media.DefaultPlayerMode
import org.wastaken.kotatsu.api21.booru.media.FloatingWindowPosition
import org.wastaken.kotatsu.api21.booru.media.FloatingWindowSize
import org.wastaken.kotatsu.api21.booru.media.GifTapAction
import org.wastaken.kotatsu.api21.booru.media.RepeatMode
import org.wastaken.kotatsu.api21.booru.media.VideoTapAction
import org.wastaken.kotatsu.api21.booru.media.ui.BooruPlaylistsActivity
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.ui.BasePreferenceFragment
import org.wastaken.kotatsu.api21.core.util.ext.putEnumValue
import javax.inject.Inject

/**
 * Settings → Media player (Task 5): every booru media feature toggle in one
 * section, plus the playlist-management link and the Import/Export actions.
 * ListPreferences get entryValues assigned programmatically (the fork's static
 * values-parser crashes on string-typed lists otherwise).
 */
@AndroidEntryPoint
class MediaPlayerSettingsFragment : BasePreferenceFragment(R.string.media_player_settings) {

	private var pendingExportPayload: String? = null

	private val createDocumentCall = registerForActivityResult(
		ActivityResultContracts.CreateDocument(BooruMediaImportExport.MIME_JSON),
	) { uri ->
		val payload = pendingExportPayload
		pendingExportPayload = null
		if (uri != null && payload != null) {
			viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch(Dispatchers.IO) {
				val ok = BooruMediaImportExport.writeTo(requireContext(), uri, payload)
				withContext(Dispatchers.Main) {
					toast(if (ok) R.string.media_export_done else R.string.error_occurred)
				}
			}
		}
	}

	private val openDocumentCall = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) importFile(uri)
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_media_player)
		bindEnumList(AppSettings.KEY_BOORU_LONG_PRESS_ACTION, BooruLongPressAction.entries)
		bindEnumList(AppSettings.KEY_BOORU_VIDEO_ENGINE, BooruVideoEngine.entries)
		bindEnumList(AppSettings.KEY_MEDIA_GIF_TAP_ACTION, GifTapAction.entries)
		bindEnumList(AppSettings.KEY_MEDIA_VIDEO_TAP_ACTION, VideoTapAction.entries)
		bindEnumList(AppSettings.KEY_MEDIA_DEFAULT_PLAYER_MODE, DefaultPlayerMode.entries)
		bindEnumList(AppSettings.KEY_MEDIA_FLOATING_SIZE, FloatingWindowSize.entries)
		bindEnumList(AppSettings.KEY_MEDIA_FLOATING_POSITION, FloatingWindowPosition.entries)
		bindEnumList(AppSettings.KEY_MEDIA_ASPECT_RATIO, AspectRatioMode.entries)
		bindEnumList(AppSettings.KEY_MEDIA_QUEUE_REPEAT, RepeatMode.entries)
		findPreference<ListPreference>(AppSettings.KEY_MEDIA_DEFAULT_SPEED)?.entryValues =
			arrayOf("0.5", "1.0", "1.25", "1.5", "2.0")
		findPreference<ListPreference>(AppSettings.KEY_MEDIA_SKIP_INTERVAL)?.entryValues =
			arrayOf("5", "10", "15", "30")
		findPreference<Preference>("media_playlists_manage")?.setOnPreferenceClickListener {
			startActivity(Intent(requireContext(), BooruPlaylistsActivity::class.java))
			true
		}
		findPreference<Preference>("media_export_settings")?.setOnPreferenceClickListener {
			exportSettings()
			true
		}
		findPreference<Preference>("media_import_settings")?.setOnPreferenceClickListener {
			openDocumentCall.launch(arrayOf(BooruMediaImportExport.MIME_JSON))
			true
		}
		findPreference<Preference>("media_export_queue")?.setOnPreferenceClickListener {
			exportQueue()
			true
		}
		findPreference<Preference>("media_import_queue")?.setOnPreferenceClickListener {
			openDocumentCall.launch(arrayOf(BooruMediaImportExport.MIME_JSON))
			true
		}
	}

	private fun bindEnumList(key: String, values: List<Enum<*>>) {
		findPreference<ListPreference>(key)?.entryValues = values.map { it.name }.toTypedArray()
	}

	// region export

	private fun exportSettings() {
		val prefs = preferenceManager.sharedPreferences ?: return
		val entries = AppSettings.MEDIA_KEYS.associateWith { key ->
			prefs.all[key]?.toString().orEmpty()
		}
		pendingExportPayload = BooruMediaImportExport.encodeSettings(entries)
		createDocumentCall.launch(DEFAULT_SETTINGS_EXPORT_NAME)
	}

	private fun exportQueue() {
		withService("Reading queue failed") { service ->
			val content = BooruMediaImportExport.encodeItems(
				BooruMediaImportExport.TYPE_QUEUE,
				null,
				service.queue.items.map { it.toStored() },
			)
			pendingExportPayload = content
			createDocumentCall.launch(DEFAULT_QUEUE_EXPORT_NAME)
		}
	}

	// endregion

	// region import

	private fun importFile(uri: Uri) {
		viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch(Dispatchers.IO) {
			val raw = BooruMediaImportExport.readFrom(requireContext(), uri)
			val envelope = raw?.let { runCatching { BooruMediaImportExport.parse(it) }.getOrNull() }
			withContext(Dispatchers.Main) {
				when {
					envelope == null -> toast(R.string.media_import_invalid)
					envelope.type == BooruMediaImportExport.TYPE_SETTINGS -> confirmSettingsImport(envelope.settings)
					else -> confirmQueueImport(envelope.items.mapNotNull { it.toItem() })
				}
			}
		}
	}

	private fun confirmSettingsImport(entries: Map<String, String>) {
		val prefs = preferenceManager.sharedPreferences ?: return
		val changes = entries.filterKeys { it in AppSettings.MEDIA_KEYS }
		if (changes.isEmpty()) {
			toast(R.string.media_import_invalid)
			return
		}
		val diff = changes.entries.joinToString("\n") { (key, value) ->
			val headline = key.removePrefix("media_").replace('_', ' ')
			"• $headline: ${prefs.all[key] ?: "—"} → $value"
		}
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.media_import_settings_confirm)
			.setMessage(diff)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				applySettings(changes)
				requireActivity().recreate()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun applySettings(entries: Map<String, String>) {
		val prefs = preferenceManager.sharedPreferences ?: return
		val editor = prefs.edit()
		for ((key, value) in entries) {
			when (key) {
				AppSettings.KEY_MEDIA_BLUR_THUMBNAILS,
				AppSettings.KEY_MEDIA_GIF_LOOP,
				AppSettings.KEY_MEDIA_GIF_FRAME_CONTROLS,
				AppSettings.KEY_MEDIA_VIDEO_LOOP,
				AppSettings.KEY_MEDIA_FLOATING_LOCK,
				AppSettings.KEY_MEDIA_VOLUME_GESTURE,
				AppSettings.KEY_MEDIA_BRIGHTNESS_GESTURE,
				AppSettings.KEY_MEDIA_PINCH_ZOOM,
				AppSettings.KEY_MEDIA_QUEUE_PERSIST,
				AppSettings.KEY_MEDIA_QUEUE_SHUFFLE,
				-> editor.putBoolean(key, value.toBoolean())
				AppSettings.KEY_MEDIA_BLUR_INTENSITY -> editor.putInt(key, value.toIntOrNull() ?: 10)
				AppSettings.KEY_BOORU_LONG_PRESS_ACTION -> editor.putEnumValue(key, runCatching { BooruLongPressAction.valueOf(value) }.getOrNull())
				AppSettings.KEY_BOORU_VIDEO_ENGINE -> editor.putEnumValue(key, runCatching { BooruVideoEngine.valueOf(value) }.getOrNull())
				AppSettings.KEY_MEDIA_GIF_TAP_ACTION -> editor.putEnumValue(key, runCatching { GifTapAction.valueOf(value) }.getOrNull())
				AppSettings.KEY_MEDIA_VIDEO_TAP_ACTION -> editor.putEnumValue(key, runCatching { VideoTapAction.valueOf(value) }.getOrNull())
				AppSettings.KEY_MEDIA_DEFAULT_PLAYER_MODE -> editor.putEnumValue(key, runCatching { DefaultPlayerMode.valueOf(value) }.getOrNull())
				AppSettings.KEY_MEDIA_FLOATING_SIZE -> editor.putEnumValue(key, runCatching { FloatingWindowSize.valueOf(value) }.getOrNull())
				AppSettings.KEY_MEDIA_FLOATING_POSITION -> editor.putEnumValue(key, runCatching { FloatingWindowPosition.valueOf(value) }.getOrNull())
				AppSettings.KEY_MEDIA_ASPECT_RATIO -> editor.putEnumValue(key, runCatching { AspectRatioMode.valueOf(value) }.getOrNull())
				AppSettings.KEY_MEDIA_QUEUE_REPEAT -> editor.putEnumValue(key, runCatching { RepeatMode.valueOf(value) }.getOrNull())
				AppSettings.KEY_MEDIA_DEFAULT_SPEED, AppSettings.KEY_MEDIA_SKIP_INTERVAL -> editor.putString(key, value)
			}
		}
		editor.apply()
	}

	private fun confirmQueueImport(items: List<BooruMediaItem>) {
		if (items.isEmpty()) {
			toast(R.string.media_import_invalid)
			return
		}
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.media_queue_title)
			.setMessage(getString(R.string.media_import_queue_confirm, items.size))
			.setPositiveButton(R.string.media_replace_queue) { _, _ -> applyQueueImport(items, replace = true) }
			.setNegativeButton(R.string.media_append_queue) { _, _ -> applyQueueImport(items, replace = false) }
			.setNeutralButton(android.R.string.cancel, null)
			.show()
	}

	private fun applyQueueImport(items: List<BooruMediaItem>, replace: Boolean) {
		withService("Queue unavailable") { service ->
			if (replace) service.queue.clear()
			items.forEach { service.queue.add(it) }
			toast(R.string.media_import_done)
		}
	}

	// endregion

	private fun withService(errorHint: String, block: (BooruMediaService) -> Unit) {
		val context = requireContext()
		BooruMediaService.start(context)
		context.bindService(
			Intent(context, BooruMediaService::class.java),
			object : ServiceConnection {
				override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
					val service = (binder as? BooruMediaService.LocalBinder)?.service
					if (service == null) {
						toast(R.string.error_occurred)
						return
					}
					block(service)
					runCatching { context.unbindService(this) }
				}

				override fun onServiceDisconnected(name: ComponentName?) = Unit
			},
			Context.BIND_AUTO_CREATE,
		)
	}

	private fun toast(resId: Int) {
		Snackbar.make(listView, resId, Snackbar.LENGTH_SHORT).show()
	}

	companion object {
		private const val DEFAULT_SETTINGS_EXPORT_NAME = "primavera-media-settings.json"
		private const val DEFAULT_QUEUE_EXPORT_NAME = "primavera-media-queue.json"
	}
}
