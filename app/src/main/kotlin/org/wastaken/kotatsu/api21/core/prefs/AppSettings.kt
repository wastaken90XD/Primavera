package org.wastaken.kotatsu.api21.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.FloatRange
import androidx.appcompat.app.AppCompatDelegate
import androidx.collection.ArraySet
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.core.model.ZoomMode
import org.wastaken.kotatsu.api21.core.network.DoHProvider
import org.wastaken.kotatsu.api21.core.network.proxy.ProxyType
import org.wastaken.kotatsu.api21.core.util.ext.connectivityManager
import org.wastaken.kotatsu.api21.booru.media.AspectRatioMode
import org.wastaken.kotatsu.api21.booru.media.BooruLongPressAction
import org.wastaken.kotatsu.api21.booru.media.BooruVideoEngine
import org.wastaken.kotatsu.api21.booru.media.DefaultPlayerMode
import org.wastaken.kotatsu.api21.booru.media.FloatingWindowPosition
import org.wastaken.kotatsu.api21.booru.media.FloatingWindowSize
import org.wastaken.kotatsu.api21.booru.media.GifTapAction
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.wastaken.kotatsu.api21.reader.ui.media.mediaPlayerDefault
import org.wastaken.kotatsu.api21.booru.media.RepeatMode
import org.wastaken.kotatsu.api21.booru.media.VideoTapAction
import org.wastaken.kotatsu.api21.core.util.ext.getEnumValue
import org.wastaken.kotatsu.api21.core.util.ext.observeChanges
import org.wastaken.kotatsu.api21.core.util.ext.putAll
import org.wastaken.kotatsu.api21.core.util.ext.putEnumValue
import org.wastaken.kotatsu.api21.core.util.ext.takeIfReadable
import org.wastaken.kotatsu.api21.core.util.ext.toUriOrNull
import org.wastaken.kotatsu.api21.explore.data.SourcesSortOrder
import org.wastaken.kotatsu.api21.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.find
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.wastaken.kotatsu.api21.reader.domain.ReaderColorFilter
import java.io.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
	private val connectivityManager = context.connectivityManager
	private val mangaListBadgesDefault = ArraySet(context.resources.getStringArray(R.array.values_list_badges))

	var listMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE, ListMode.GRID)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE, value) }

	val theme: Int
		get() = prefs.getString(KEY_THEME, null)?.toIntOrNull()
			?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

	val colorScheme: ColorScheme
		get() = prefs.getEnumValue(KEY_COLOR_THEME, ColorScheme.default).availableOrDefault()

	val isAmoledTheme: Boolean
		get() = prefs.getBoolean(KEY_THEME_AMOLED, false)

	var mainNavItems: List<NavItem>
		get() {
			val raw = prefs.getString(KEY_NAV_MAIN, null)?.split(',')
			return if (raw.isNullOrEmpty()) {
				listOf(NavItem.HISTORY, NavItem.FAVORITES, NavItem.EXPLORE, NavItem.FEED)
			} else {
				raw.mapNotNull { x -> NavItem.entries.find(x) }.ifEmpty { listOf(NavItem.EXPLORE) }
			}
		}
		set(value) {
			prefs.edit {
				putString(KEY_NAV_MAIN, value.joinToString(",") { it.name })
			}
		}

	val isNavLabelsVisible: Boolean
		get() = prefs.getBoolean(KEY_NAV_LABELS, true)

	val isNavBarPinned: Boolean
		get() = prefs.getBoolean(KEY_NAV_PINNED, false)

	val isMainFabEnabled: Boolean
		get() = prefs.getBoolean(KEY_MAIN_FAB, true)

	var gridSize: Int
		get() = prefs.getInt(KEY_GRID_SIZE, 100)
		set(value) = prefs.edit { putInt(KEY_GRID_SIZE, value) }

	var gridSizePages: Int
		get() = prefs.getInt(KEY_GRID_SIZE_PAGES, 100)
		set(value) = prefs.edit { putInt(KEY_GRID_SIZE_PAGES, value) }

	/** Booru grid column count (2..4, default 3). ListPreference stores it as text. */
	val booruGridColumns: Int
		get() = (prefs.getString(KEY_BOORU_GRID_COLUMNS, null)?.toIntOrNull() ?: BOORU_GRID_COLUMNS_DEFAULT)
			.coerceIn(BOORU_GRID_COLUMNS_MIN, BOORU_GRID_COLUMNS_MAX)

	// region booru media player

	val isMediaBlurThumbnails: Boolean
		get() = prefs.getBoolean(KEY_MEDIA_BLUR_THUMBNAILS, false)

	val mediaBlurIntensity: Int
		get() = prefs.getInt(KEY_MEDIA_BLUR_INTENSITY, 10).coerceIn(1, 25)

	val mediaGifTapAction: GifTapAction
		get() = prefs.getEnumValue(KEY_MEDIA_GIF_TAP_ACTION, GifTapAction.INLINE)

	val mediaVideoTapAction: VideoTapAction
		get() = prefs.getEnumValue(KEY_MEDIA_VIDEO_TAP_ACTION, VideoTapAction.PLAY_IN_APP)

	val mediaDefaultPlayerMode: DefaultPlayerMode
		get() = prefs.getEnumValue(KEY_MEDIA_DEFAULT_PLAYER_MODE, DefaultPlayerMode.FULLSCREEN)

	val isMediaGifLoop: Boolean
		get() = prefs.getBoolean(KEY_MEDIA_GIF_LOOP, true)

	val isMediaGifFrameControls: Boolean
		get() = prefs.getBoolean(KEY_MEDIA_GIF_FRAME_CONTROLS, false)

	val isMediaVideoLoop: Boolean
		get() = prefs.getBoolean(KEY_MEDIA_VIDEO_LOOP, false)

	/** Stored as text for the settings ListPreference; unpersistable/blend values fall back to 1x. */
	val mediaDefaultSpeed: Float
		get() = prefs.getString(KEY_MEDIA_DEFAULT_SPEED, null)?.toFloatOrNull()?.coerceIn(0.5f, 3f) ?: 1f

	/** Skip amount in seconds (5/10/15/30). */
	val mediaSkipIntervalSec: Int
		get() = (prefs.getString(KEY_MEDIA_SKIP_INTERVAL, null)?.toIntOrNull() ?: 10)
			.coerceIn(1, Int.MAX_VALUE)

	val mediaFloatingSize: FloatingWindowSize
		get() = prefs.getEnumValue(KEY_MEDIA_FLOATING_SIZE, FloatingWindowSize.MEDIUM)

	val mediaFloatingPosition: FloatingWindowPosition
		get() = prefs.getEnumValue(KEY_MEDIA_FLOATING_POSITION, FloatingWindowPosition.TOP_RIGHT)

	val isMediaFloatingLock: Boolean
		get() = prefs.getBoolean(KEY_MEDIA_FLOATING_LOCK, false)

	val isMediaVolumeGesture: Boolean
		get() = prefs.getBoolean(KEY_MEDIA_VOLUME_GESTURE, true)

	val isMediaBrightnessGesture: Boolean
		get() = prefs.getBoolean(KEY_MEDIA_BRIGHTNESS_GESTURE, true)

	val isMediaPinchZoom: Boolean
		get() = prefs.getBoolean(KEY_MEDIA_PINCH_ZOOM, true)

	val mediaAspectRatio: AspectRatioMode
		get() = prefs.getEnumValue(KEY_MEDIA_ASPECT_RATIO, AspectRatioMode.FIT)

	var isMediaQueuePersist: Boolean
		get() = prefs.getBoolean(KEY_MEDIA_QUEUE_PERSIST, true)
		set(value) = prefs.edit { putBoolean(KEY_MEDIA_QUEUE_PERSIST, value) }

	/** Grid tile long-press: immediate download (old behavior), popup menu, or batch selection. */
	var booruLongPressAction: BooruLongPressAction
		get() = prefs.getEnumValue(KEY_BOORU_LONG_PRESS_ACTION, BooruLongPressAction.DOWNLOAD)
		set(value) = prefs.edit { putEnumValue(KEY_BOORU_LONG_PRESS_ACTION, value) }

	/** Video decoder used by the booru media player: embedded libVLC or the platform MediaPlayer. */
	var booruVideoEngine: BooruVideoEngine
		get() = prefs.getEnumValue(KEY_BOORU_VIDEO_ENGINE, BooruVideoEngine.LIBVLC)
		set(value) = prefs.edit { putEnumValue(KEY_BOORU_VIDEO_ENGINE, value) }

	/**
	 * Per-source gate for the built-in media player ("media_player_source_*").
	 * Default: on for native booru sources, off otherwise.
	 */
	fun isMediaPlayerEnabledForSource(source: MangaSource): Boolean =
		prefs.getBoolean(KEY_MEDIA_PLAYER_SOURCE_PREFIX + source.name, source.mediaPlayerDefault())

	fun setMediaPlayerEnabledForSource(source: MangaSource, enabled: Boolean) {
		prefs.edit { putBoolean(KEY_MEDIA_PLAYER_SOURCE_PREFIX + source.name, enabled) }
	}

	fun resetMediaPlayerSourceOverrides() {
		val keys = prefs.all.keys.filter { it.startsWith(KEY_MEDIA_PLAYER_SOURCE_PREFIX) }
		if (keys.isNotEmpty()) {
			prefs.edit { for (key in keys) remove(key) }
		}
	}

	val mediaQueueRepeat: RepeatMode
		get() = prefs.getEnumValue(KEY_MEDIA_QUEUE_REPEAT, RepeatMode.NONE)

	val isMediaQueueShuffleOnLoad: Boolean
		get() = prefs.getBoolean(KEY_MEDIA_QUEUE_SHUFFLE, false)

	// endregion booru media player

	val isQuickFilterEnabled: Boolean
		get() = prefs.getBoolean(KEY_QUICK_FILTER, true)

	val isDescriptionExpanded: Boolean
		get() = !prefs.getBoolean(KEY_COLLAPSE_DESCRIPTION, true)

	var historyListMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE_HISTORY, listMode)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE_HISTORY, value) }

	var suggestionsListMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE_SUGGESTIONS, listMode)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE_SUGGESTIONS, value) }

	var favoritesListMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE_FAVORITES, listMode)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE_FAVORITES, value) }

	val isTagsWarningsEnabled: Boolean
		get() = prefs.getBoolean(KEY_TAGS_WARNINGS, true)

	var isNsfwContentDisabled: Boolean
		get() = prefs.getBoolean(KEY_DISABLE_NSFW, false)
		set(value) = prefs.edit { putBoolean(KEY_DISABLE_NSFW, value) }

	var appLocales: LocaleListCompat
		get() {
			val raw = prefs.getString(KEY_APP_LOCALE, null)
			return LocaleListCompat.forLanguageTags(raw)
		}
		set(value) {
			prefs.edit {
				putString(KEY_APP_LOCALE, value.toLanguageTags())
			}
		}

	var isReaderDoubleOnLandscape: Boolean
		get() = prefs.getBoolean(KEY_READER_DOUBLE_PAGES, false)
		set(value) = prefs.edit { putBoolean(KEY_READER_DOUBLE_PAGES, value) }

	val readerScreenOrientation: Int
		get() = prefs.getString(KEY_READER_ORIENTATION, null)?.toIntOrNull()
			?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

	val isReaderVolumeButtonsEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_VOLUME_BUTTONS, false)

	val isReaderZoomButtonsEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_ZOOM_BUTTONS, false)

	val isReaderControlAlwaysLTR: Boolean
		get() = prefs.getBoolean(KEY_READER_CONTROL_LTR, false)

	val isReaderNavigationInverted: Boolean
		get() = prefs.getBoolean(KEY_READER_NAVIGATION_INVERTED, false)

	val isReaderFullscreenEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_FULLSCREEN, true)

	val isReaderOptimizationEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_OPTIMIZE, false)

	val readerControls: Set<ReaderControl>
		get() = prefs.getStringSet(KEY_READER_CONTROLS, null)?.mapNotNullTo(EnumSet.noneOf(ReaderControl::class.java)) {
			ReaderControl.entries.find(it)
		} ?: ReaderControl.DEFAULT

	val isOfflineCheckDisabled: Boolean
		get() = prefs.getBoolean(KEY_OFFLINE_DISABLED, false)

	var isAllFavouritesVisible: Boolean
		get() = prefs.getBoolean(KEY_ALL_FAVOURITES_VISIBLE, true)
		set(value) = prefs.edit { putBoolean(KEY_ALL_FAVOURITES_VISIBLE, value) }

	val isTrackerEnabled: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_ENABLED, true)

	val isTrackerWifiOnly: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_WIFI_ONLY, false)

	val trackerFrequencyFactor: Float
		get() = prefs.getString(KEY_TRACKER_FREQUENCY, null)?.toFloatOrNull() ?: 1f

	val isTrackerNotificationsEnabled: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_NOTIFICATIONS, true)

	val isTrackerNsfwDisabled: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_NO_NSFW, false)

	val trackerDownloadStrategy: TrackerDownloadStrategy
		get() = prefs.getEnumValue(KEY_TRACKER_DOWNLOAD, TrackerDownloadStrategy.DISABLED)

	var notificationSound: Uri
		get() = prefs.getString(KEY_NOTIFICATIONS_SOUND, null)?.toUriOrNull()
			?: Settings.System.DEFAULT_NOTIFICATION_URI
		set(value) = prefs.edit { putString(KEY_NOTIFICATIONS_SOUND, value.toString()) }

	val notificationVibrate: Boolean
		get() = prefs.getBoolean(KEY_NOTIFICATIONS_VIBRATE, false)

	val notificationLight: Boolean
		get() = prefs.getBoolean(KEY_NOTIFICATIONS_LIGHT, true)

	val readerAnimation: ReaderAnimation
		get() = prefs.getEnumValue(KEY_READER_ANIMATION, ReaderAnimation.DEFAULT)

	val readerBackground: ReaderBackground
		get() = prefs.getEnumValue(KEY_READER_BACKGROUND, ReaderBackground.DEFAULT)

	val defaultReaderMode: ReaderMode
		get() = prefs.getEnumValue(KEY_READER_MODE, ReaderMode.STANDARD)

	val isReaderModeDetectionEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_MODE_DETECT, true)

	var isHistoryGroupingEnabled: Boolean
		get() = prefs.getBoolean(KEY_HISTORY_GROUPING, true)
		set(value) = prefs.edit { putBoolean(KEY_HISTORY_GROUPING, value) }

	var isUpdatedGroupingEnabled: Boolean
		get() = prefs.getBoolean(KEY_UPDATED_GROUPING, true)
		set(value) = prefs.edit { putBoolean(KEY_UPDATED_GROUPING, value) }

	var isFeedHeaderVisible: Boolean
		get() = prefs.getBoolean(KEY_FEED_HEADER, true)
		set(value) = prefs.edit { putBoolean(KEY_FEED_HEADER, value) }

	val progressIndicatorMode: ProgressIndicatorMode
		get() = prefs.getEnumValue(KEY_PROGRESS_INDICATORS, ProgressIndicatorMode.PERCENT_READ)

	var incognitoModeForNsfw: TriStateOption
		get() = prefs.getEnumValue(KEY_INCOGNITO_NSFW, TriStateOption.ASK)
		set(value) = prefs.edit { putEnumValue(KEY_INCOGNITO_NSFW, value) }

	var isIncognitoModeEnabled: Boolean
		get() = prefs.getBoolean(KEY_INCOGNITO_MODE, false)
		set(value) = prefs.edit { putBoolean(KEY_INCOGNITO_MODE, value) }

	val isReaderMultiTaskEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_MULTITASK, false)

	var isChaptersReverse: Boolean
		get() = prefs.getBoolean(KEY_REVERSE_CHAPTERS, false)
		set(value) = prefs.edit { putBoolean(KEY_REVERSE_CHAPTERS, value) }

	var isChaptersGridView: Boolean
		get() = prefs.getBoolean(KEY_GRID_VIEW_CHAPTERS, false)
		set(value) = prefs.edit { putBoolean(KEY_GRID_VIEW_CHAPTERS, value) }

	val zoomMode: ZoomMode
		get() = prefs.getEnumValue(KEY_ZOOM_MODE, ZoomMode.FIT_CENTER)

	val trackSources: Set<String>
		get() = prefs.getStringSet(KEY_TRACK_SOURCES, null) ?: setOf(TRACK_FAVOURITES)

	var appPassword: String?
		get() = prefs.getString(KEY_APP_PASSWORD, null)
		set(value) = prefs.edit {
			if (value != null) putString(KEY_APP_PASSWORD, value) else remove(KEY_APP_PASSWORD)
		}

	var isAppPasswordNumeric: Boolean
		get() = prefs.getBoolean(KEY_APP_PASSWORD_NUMERIC, false)
		set(value) = prefs.edit { putBoolean(KEY_APP_PASSWORD_NUMERIC, value) }

	val searchSuggestionTypes: Set<SearchSuggestionType>
		get() = prefs.getStringSet(KEY_SEARCH_SUGGESTION_TYPES, null)?.let { stringSet ->
			stringSet.mapNotNullTo(EnumSet.noneOf(SearchSuggestionType::class.java)) { x ->
				enumValueOf<SearchSuggestionType>(x)
			}
		} ?: EnumSet.allOf(SearchSuggestionType::class.java)

	var isBiometricProtectionEnabled: Boolean
		get() = prefs.getBoolean(KEY_PROTECT_APP_BIOMETRIC, true)
		set(value) = prefs.edit { putBoolean(KEY_PROTECT_APP_BIOMETRIC, value) }

	val isMirrorSwitchingEnabled: Boolean
		get() = prefs.getBoolean(KEY_MIRROR_SWITCHING, false)

	val isExitConfirmationEnabled: Boolean
		get() = prefs.getBoolean(KEY_EXIT_CONFIRM, false)

	val isDynamicShortcutsEnabled: Boolean
		get() = prefs.getBoolean(KEY_SHORTCUTS, true)

	val isUnstableUpdatesAllowed: Boolean
		get() = prefs.getBoolean(KEY_UPDATES_UNSTABLE, false)

	val isPagesTabEnabled: Boolean
		get() = prefs.getBoolean(KEY_PAGES_TAB, true)

	val defaultDetailsTab: Int
		get() = if (isPagesTabEnabled) {
			val raw = prefs.getString(KEY_DETAILS_TAB, null)?.toIntOrNull() ?: -1
			if (raw == -1) {
				lastDetailsTab
			} else {
				raw
			}.coerceIn(0, 2)
		} else {
			0
		}

	var lastDetailsTab: Int
		get() = prefs.getInt(KEY_DETAILS_LAST_TAB, 0)
		set(value) = prefs.edit { putInt(KEY_DETAILS_LAST_TAB, value) }

	val isContentPrefetchEnabled: Boolean
		get() {
			if (isBackgroundNetworkRestricted()) {
				return false
			}
			val policy =
				NetworkPolicy.from(prefs.getString(KEY_PREFETCH_CONTENT, null), NetworkPolicy.NEVER)
			return policy.isNetworkAllowed(connectivityManager)
		}

	var sourcesSortOrder: SourcesSortOrder
		get() = prefs.getEnumValue(KEY_SOURCES_ORDER, SourcesSortOrder.MANUAL)
		set(value) = prefs.edit { putEnumValue(KEY_SOURCES_ORDER, value) }

	var isSourcesGridMode: Boolean
		get() = prefs.getBoolean(KEY_SOURCES_GRID, true)
		set(value) = prefs.edit { putBoolean(KEY_SOURCES_GRID, value) }

	var sourcesVersion: Int
		get() = prefs.getInt(KEY_SOURCES_VERSION, 0)
		set(value) = prefs.edit { putInt(KEY_SOURCES_VERSION, value) }

	var isAllSourcesEnabled: Boolean
		get() = prefs.getBoolean(KEY_SOURCES_ENABLED_ALL, false)
		set(value) = prefs.edit { putBoolean(KEY_SOURCES_ENABLED_ALL, value) }

	val isPagesNumbersEnabled: Boolean
		get() = prefs.getBoolean(KEY_PAGES_NUMBERS, false)

	val screenshotsPolicy: ScreenshotsPolicy
		get() = prefs.getEnumValue(KEY_SCREENSHOTS_POLICY, ScreenshotsPolicy.ALLOW)

	val isAdBlockEnabled: Boolean
		get() = prefs.getBoolean(KEY_ADBLOCK, false)

	var userSpecifiedMangaDirectories: Set<File>
		get() {
			val set = prefs.getStringSet(KEY_LOCAL_MANGA_DIRS, emptySet()).orEmpty()
			return set.mapNotNullToSet { File(it).takeIfReadable() }
		}
		set(value) {
			val set = value.mapToSet { it.absolutePath }
			prefs.edit { putStringSet(KEY_LOCAL_MANGA_DIRS, set) }
		}

	var mangaStorageDir: File?
		get() = prefs.getString(KEY_LOCAL_STORAGE, null)?.let {
			File(it)
		}?.takeIf { it.exists() && it in userSpecifiedMangaDirectories }
		set(value) = prefs.edit {
			if (value == null) {
				remove(KEY_LOCAL_STORAGE)
			} else {
				val userDirs = userSpecifiedMangaDirectories
				if (value !in userDirs) {
					userSpecifiedMangaDirectories = userDirs + value
				}
				putString(KEY_LOCAL_STORAGE, value.path)
			}
		}

	var allowDownloadOnMeteredNetwork: TriStateOption
		get() = prefs.getEnumValue(KEY_DOWNLOADS_METERED_NETWORK, TriStateOption.ASK)
		set(value) = prefs.edit { putEnumValue(KEY_DOWNLOADS_METERED_NETWORK, value) }

	val preferredDownloadFormat: DownloadFormat
		get() = prefs.getEnumValue(KEY_DOWNLOADS_FORMAT, DownloadFormat.AUTOMATIC)

	var isSuggestionsEnabled: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS, false)
		set(value) = prefs.edit { putBoolean(KEY_SUGGESTIONS, value) }

	val isSuggestionsWiFiOnly: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS_WIFI_ONLY, false)

	val isSuggestionsExcludeNsfw: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS_EXCLUDE_NSFW, false)

	val isSuggestionsIncludeDisabledSources: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS_DISABLED_SOURCES, false)

	val isSuggestionsNotificationAvailable: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS_NOTIFICATIONS, false)

	val suggestionsTagsBlacklist: Set<String>
		get() {
			val string = prefs.getString(KEY_SUGGESTIONS_EXCLUDE_TAGS, null)?.trimEnd(' ', ',')
			if (string.isNullOrEmpty()) {
				return emptySet()
			}
			return string.split(',').mapToSet { it.trim() }
		}

	val isReaderBarEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_BAR, true)

	val isReaderBarTransparent: Boolean
		get() = prefs.getBoolean(KEY_READER_BAR_TRANSPARENT, true)

	val isReaderKeepScreenOn: Boolean
		get() = prefs.getBoolean(KEY_READER_SCREEN_ON, true)

	var readerColorFilter: ReaderColorFilter?
		get() = runCatching {
			ReaderColorFilter(
				brightness = prefs.getFloat(KEY_CF_BRIGHTNESS, ReaderColorFilter.EMPTY.brightness),
				contrast = prefs.getFloat(KEY_CF_CONTRAST, ReaderColorFilter.EMPTY.contrast),
				isInverted = prefs.getBoolean(KEY_CF_INVERTED, ReaderColorFilter.EMPTY.isInverted),
				isGrayscale = prefs.getBoolean(KEY_CF_GRAYSCALE, ReaderColorFilter.EMPTY.isGrayscale),
				isBookBackground = prefs.getBoolean(KEY_CF_BOOK, ReaderColorFilter.EMPTY.isBookBackground),
			).takeUnless { it.isEmpty }
		}.getOrNull()
		set(value) {
			prefs.edit {
				if (value != null) {
					putFloat(KEY_CF_BRIGHTNESS, value.brightness)
					putFloat(KEY_CF_CONTRAST, value.contrast)
					putBoolean(KEY_CF_INVERTED, value.isInverted)
					putBoolean(KEY_CF_GRAYSCALE, value.isGrayscale)
					putBoolean(KEY_CF_BOOK, value.isBookBackground)
				} else {
					remove(KEY_CF_BRIGHTNESS)
					remove(KEY_CF_CONTRAST)
					remove(KEY_CF_INVERTED)
					remove(KEY_CF_GRAYSCALE)
					remove(KEY_CF_BOOK)
				}
			}
		}

	val imagesProxy: Int
		get() {
			val raw = prefs.getString(KEY_IMAGES_PROXY, null)?.toIntOrNull()
			return raw ?: if (prefs.getBoolean(KEY_IMAGES_PROXY_OLD, false)) 0 else -1
		}

	val dnsOverHttps: DoHProvider
		get() = prefs.getEnumValue(KEY_DOH, DoHProvider.NONE)

	var isSSLBypassEnabled: Boolean
		get() = prefs.getBoolean(KEY_SSL_BYPASS, false)
		set(value) = prefs.edit { putBoolean(KEY_SSL_BYPASS, value) }

	val proxyType: ProxyType
		get() = ProxyType.of(prefs.getString(KEY_PROXY_TYPE, null))

	val proxyAddress: String?
		get() = prefs.getString(KEY_PROXY_ADDRESS, null)

	val proxyPort: Int
		get() = prefs.getString(KEY_PROXY_PORT, null)?.toIntOrNull() ?: 0

	// Experimental wsrv.nl image quality (revertable)
	val wsrvQuality: Int
		get() = prefs.getString(KEY_WSRV_QUALITY, null)?.toIntOrNull() ?: 0
	val wsrvFormat: String
		get() = prefs.getString(KEY_WSRV_FORMAT, null) ?: "original"
	val wsrvMaxWidth: Int
		get() = prefs.getString(KEY_WSRV_MAX_WIDTH, null)?.toIntOrNull() ?: 0
	val wsrvMaxHeight: Int
		get() = prefs.getString(KEY_WSRV_MAX_HEIGHT, null)?.toIntOrNull() ?: 0
	val wsrvDpr: Float
		get() = prefs.getString(KEY_WSRV_DPR, null)?.toFloatOrNull() ?: 0f
	val wsrvNeverUpscale: Boolean
		get() = prefs.getBoolean(KEY_WSRV_NEVER_UPSCALE, false)
	val wsrvFit: String
		get() = prefs.getString(KEY_WSRV_FIT, null) ?: "cover"
	val wsrvAlignment: String
		get() = prefs.getString(KEY_WSRV_ALIGNMENT, null) ?: "center"
	val wsrvSharpen: Int
		get() = prefs.getString(KEY_WSRV_SHARPEN, null)?.toIntOrNull() ?: 0
	val wsrvBlur: Float
		get() = prefs.getString(KEY_WSRV_BLUR, null)?.toFloatOrNull() ?: 0f
	val wsrvContrast: Int
		get() = prefs.getString(KEY_WSRV_CONTRAST, null)?.toIntOrNull() ?: 0
	val wsrvSaturation: Int
		get() = prefs.getString(KEY_WSRV_SATURATION, null)?.toIntOrNull() ?: 0
	val wsrvGamma: Float
		get() = prefs.getString(KEY_WSRV_GAMMA, null)?.toFloatOrNull() ?: 0f
	val wsrvHue: Int
		get() = prefs.getString(KEY_WSRV_HUE, null)?.toIntOrNull() ?: 0
	val wsrvBrightness: Float
		get() = prefs.getString(KEY_WSRV_BRIGHTNESS, null)?.toFloatOrNull() ?: 0f
	val wsrvTint: String
		get() = prefs.getString(KEY_WSRV_TINT, null) ?: ""
	val wsrvProgressive: Boolean
		get() = prefs.getBoolean(KEY_WSRV_PROGRESSIVE, false)
	val wsrvPngLevel: Int
		get() = prefs.getString(KEY_WSRV_PNG_LEVEL, null)?.toIntOrNull() ?: 0
	val wsrvPngFilter: Boolean
		get() = prefs.getBoolean(KEY_WSRV_PNG_FILTER, false)
	val wsrvLossless: Boolean
		get() = prefs.getBoolean(KEY_WSRV_LOSSLESS, false)

	// Optional Cloudflare Worker relay in front of wsrv.nl (default off)
	val wsrvWorkerEnabled: Boolean
		get() = prefs.getBoolean(KEY_WSRV_WORKER_ENABLED, false)
	val wsrvWorkerUrl: String
		get() = prefs.getString(KEY_WSRV_WORKER_URL, null) ?: ""
	val wsrvWorkerQueryParam: String
		get() = prefs.getString(KEY_WSRV_WORKER_QUERY_PARAM, null)?.takeIf { it.isNotBlank() } ?: "url"

	val proxyLogin: String?
		get() = prefs.getString(KEY_PROXY_LOGIN, null)?.nullIfEmpty()

	val proxyPassword: String?
		get() = prefs.getString(KEY_PROXY_PASSWORD, null)?.nullIfEmpty()

	val proxySecret: String?
		get() = prefs.getString(KEY_PROXY_SECRET, null)?.nullIfEmpty()

	var localListOrder: SortOrder
		get() = prefs.getEnumValue(KEY_LOCAL_LIST_ORDER, SortOrder.NEWEST)
		set(value) = prefs.edit { putEnumValue(KEY_LOCAL_LIST_ORDER, value) }

	var historySortOrder: ListSortOrder
		get() = prefs.getEnumValue(KEY_HISTORY_ORDER, ListSortOrder.LAST_READ)
		set(value) = prefs.edit { putEnumValue(KEY_HISTORY_ORDER, value) }

	var allFavoritesSortOrder: ListSortOrder
		get() = prefs.getEnumValue(KEY_FAVORITES_ORDER, ListSortOrder.NEWEST)
		set(value) = prefs.edit { putEnumValue(KEY_FAVORITES_ORDER, value) }

	val isRelatedMangaEnabled: Boolean
		get() = prefs.getBoolean(KEY_RELATED_MANGA, true)

	val isWebtoonZoomEnabled: Boolean
		get() = prefs.getBoolean(KEY_WEBTOON_ZOOM, true)

	var isWebtoonGapsEnabled: Boolean
		get() = prefs.getBoolean(KEY_WEBTOON_GAPS, false)
		set(value) = prefs.edit { putBoolean(KEY_WEBTOON_GAPS, value) }

	@get:FloatRange(from = 0.0, to = 0.5)
	val defaultWebtoonZoomOut: Float
		get() = prefs.getInt(KEY_WEBTOON_ZOOM_OUT, 0).coerceIn(0, 50) / 100f

	@get:FloatRange(from = 0.0, to = 1.0)
	var readerAutoscrollSpeed: Float
		get() = prefs.getFloat(KEY_READER_AUTOSCROLL_SPEED, 0f)
		set(@FloatRange(from = 0.0, to = 1.0) value) = prefs.edit {
			putFloat(
				KEY_READER_AUTOSCROLL_SPEED,
				value,
			)
		}

	var isReaderAutoscrollFabVisible: Boolean
		get() = prefs.getBoolean(KEY_READER_AUTOSCROLL_FAB, true)
		set(value) = prefs.edit { putBoolean(KEY_READER_AUTOSCROLL_FAB, value) }

	val isPagesPreloadEnabled: Boolean
		get() {
			if (isBackgroundNetworkRestricted()) {
				return false
			}
			val policy = NetworkPolicy.from(
				prefs.getString(KEY_PAGES_PRELOAD, null),
				NetworkPolicy.NON_METERED,
			)
			return policy.isNetworkAllowed(connectivityManager)
		}

	val is32BitColorsEnabled: Boolean
		get() = prefs.getBoolean(KEY_32BIT_COLOR, false)

	val isDiscordRpcEnabled: Boolean
		get() = prefs.getBoolean(KEY_DISCORD_RPC, false)

	val isDiscordRpcSkipNsfw: Boolean
		get() = prefs.getBoolean(KEY_DISCORD_RPC_SKIP_NSFW, false)

	var discordToken: String?
		get() = prefs.getString(KEY_DISCORD_TOKEN, null)?.trim()?.nullIfEmpty()
		set(value) = prefs.edit { putString(KEY_DISCORD_TOKEN, value?.nullIfEmpty()) }

	val isPeriodicalBackupEnabled: Boolean
		get() = prefs.getBoolean(KEY_BACKUP_PERIODICAL_ENABLED, false)

	val periodicalBackupFrequency: Long
		get() = prefs.getString(KEY_BACKUP_PERIODICAL_FREQUENCY, null)?.toLongOrNull() ?: 7L

	val periodicalBackupFrequencyMillis: Long
		get() = TimeUnit.DAYS.toMillis(periodicalBackupFrequency)

	val periodicalBackupMaxCount: Int
		get() = if (prefs.getBoolean(KEY_BACKUP_PERIODICAL_TRIM, true)) {
			prefs.getInt(KEY_BACKUP_PERIODICAL_COUNT, 10)
		} else {
			Int.MAX_VALUE
		}

	var periodicalBackupDirectory: Uri?
		get() = prefs.getString(KEY_BACKUP_PERIODICAL_OUTPUT, null)?.toUriOrNull()
		set(value) = prefs.edit { putString(KEY_BACKUP_PERIODICAL_OUTPUT, value?.toString()) }

	val isBackupTelegramUploadEnabled: Boolean
		get() = prefs.getBoolean(KEY_BACKUP_TG_ENABLED, false)

	val backupTelegramChatId: String?
		get() = prefs.getString(KEY_BACKUP_TG_CHAT, null)?.nullIfEmpty()

	val isReadingTimeEstimationEnabled: Boolean
		get() = prefs.getBoolean(KEY_READING_TIME, true)

	val isPagesSavingAskEnabled: Boolean
		get() = prefs.getBoolean(KEY_PAGES_SAVE_ASK, true)

	val isPagesSaveOriginalEnabled: Boolean
		get() = prefs.getBoolean(KEY_PAGES_SAVE_ORIGINAL, true)

	val isStatsEnabled: Boolean
		get() = prefs.getBoolean(KEY_STATS_ENABLED, false)

	val isAutoLocalChaptersCleanupEnabled: Boolean
		get() = prefs.getBoolean(KEY_CHAPTERS_CLEAR_AUTO, false)

	fun isPagesCropEnabled(mode: ReaderMode): Boolean {
		val rawValue = prefs.getStringSet(KEY_READER_CROP, emptySet())
		if (rawValue.isNullOrEmpty()) {
			return false
		}
		val needle = if (mode == ReaderMode.WEBTOON) READER_CROP_WEBTOON else READER_CROP_PAGED
		return needle.toString() in rawValue
	}

	fun isTipEnabled(tip: String): Boolean {
		return prefs.getStringSet(KEY_TIPS_CLOSED, emptySet())?.contains(tip) != true
	}

	fun closeTip(tip: String) {
		val closedTips = prefs.getStringSet(KEY_TIPS_CLOSED, emptySet()).orEmpty()
		if (tip in closedTips) {
			return
		}
		prefs.edit { putStringSet(KEY_TIPS_CLOSED, closedTips + tip) }
	}

	fun isIncognitoModeEnabled(isNsfw: Boolean): Boolean {
		return isIncognitoModeEnabled || (isNsfw && incognitoModeForNsfw == TriStateOption.ENABLED)
	}

	fun getPagesSaveDir(context: Context): DocumentFile? =
		prefs.getString(KEY_PAGES_SAVE_DIR, null)?.toUriOrNull()?.let {
			DocumentFile.fromTreeUri(context, it)?.takeIf { it.canWrite() }
		}

	fun setPagesSaveDir(uri: Uri?) {
		prefs.edit { putString(KEY_PAGES_SAVE_DIR, uri?.toString()) }
	}

	fun getMangaListBadges(): Int {
		val raw = prefs.getStringSet(KEY_MANGA_LIST_BADGES, mangaListBadgesDefault).orEmpty()
		var result = 0
		for (item in raw) {
			result = result or item.toInt()
		}
		return result
	}

	fun subscribe(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
		prefs.registerOnSharedPreferenceChangeListener(listener)
	}

	fun unsubscribe(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
		prefs.unregisterOnSharedPreferenceChangeListener(listener)
	}

	fun observeChanges() = prefs.observeChanges()

	fun observe(vararg keys: String): Flow<String?> = prefs.observeChanges()
		.filter { key -> key == null || key in keys }
		.onStart { emit(null) }
		.flowOn(Dispatchers.IO)

	fun getAllValues(): Map<String, *> = prefs.all

	fun upsertAll(m: Map<String, *>) = prefs.edit {
		clear()
		putAll(m)
	}

	private fun isBackgroundNetworkRestricted(): Boolean {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			connectivityManager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
		} else {
			false
		}
	}

	companion object {

		const val TRACK_HISTORY = "history"
		const val TRACK_FAVOURITES = "favourites"

		const val KEY_ADBLOCK = "adblock"
		const val KEY_LIST_MODE = "list_mode_2"
		const val KEY_LIST_MODE_HISTORY = "list_mode_history"
		const val KEY_LIST_MODE_FAVORITES = "list_mode_favorites"
		const val KEY_LIST_MODE_SUGGESTIONS = "list_mode_suggestions"
		const val KEY_THEME = "theme"
		const val KEY_COLOR_THEME = "color_theme"
		const val KEY_THEME_AMOLED = "amoled_theme"
		const val KEY_OFFLINE_DISABLED = "no_offline"
		const val KEY_PAGES_CACHE_CLEAR = "pages_cache_clear"
		const val KEY_HTTP_CACHE_CLEAR = "http_cache_clear"
		const val KEY_COOKIES_CLEAR = "cookies_clear"
		const val KEY_COOKIES_MANAGE = "cookies_manage"
		const val KEY_CHAPTERS_CLEAR = "chapters_clear"
		const val KEY_CHAPTERS_CLEAR_AUTO = "chapters_clear_auto"
		const val KEY_THUMBS_CACHE_CLEAR = "thumbs_cache_clear"
		const val KEY_SEARCH_HISTORY_CLEAR = "search_history_clear"
		const val KEY_UPDATES_FEED_CLEAR = "updates_feed_clear"
		const val KEY_GRID_SIZE = "grid_size"
		const val KEY_BOORU_GRID_COLUMNS = "booru_grid_columns"

		// booru media player ("Media player" settings section)
		const val KEY_MEDIA_BLUR_THUMBNAILS = "media_blur_thumbnails"
		const val KEY_BOORU_LONG_PRESS_ACTION = "booru_long_press_action"
		const val KEY_MEDIA_BLUR_INTENSITY = "media_blur_intensity"
		const val KEY_MEDIA_GIF_TAP_ACTION = "media_gif_tap_action"
		const val KEY_MEDIA_VIDEO_TAP_ACTION = "media_video_tap_action"
		const val KEY_MEDIA_DEFAULT_PLAYER_MODE = "media_default_player_mode"
		const val KEY_MEDIA_GIF_LOOP = "media_gif_loop"
		const val KEY_MEDIA_GIF_FRAME_CONTROLS = "media_gif_frame_controls"
		const val KEY_MEDIA_VIDEO_LOOP = "media_video_loop"
		const val KEY_MEDIA_DEFAULT_SPEED = "media_default_speed"
		const val KEY_MEDIA_SKIP_INTERVAL = "media_skip_interval"
		const val KEY_MEDIA_FLOATING_SIZE = "media_floating_size"
		const val KEY_MEDIA_FLOATING_POSITION = "media_floating_position"
		const val KEY_MEDIA_FLOATING_LOCK = "media_floating_lock"
		const val KEY_MEDIA_VOLUME_GESTURE = "media_volume_gesture"
		const val KEY_MEDIA_BRIGHTNESS_GESTURE = "media_brightness_gesture"
		const val KEY_MEDIA_PINCH_ZOOM = "media_pinch_zoom"
		const val KEY_MEDIA_ASPECT_RATIO = "media_aspect_ratio"
		const val KEY_MEDIA_QUEUE_PERSIST = "media_queue_persist"
		const val KEY_MEDIA_QUEUE_REPEAT = "media_queue_repeat"
		const val KEY_MEDIA_QUEUE_SHUFFLE = "media_queue_shuffle"
		const val KEY_BOORU_VIDEO_ENGINE = "booru_video_engine"

		/** Prefix for the per-source media-player toggles (dynamic keys, not exported). */
		const val KEY_MEDIA_PLAYER_SOURCE_PREFIX = "media_player_source_"

		/** Every key owned by Settings → "Media player" (settings import/export + diff). */
		@JvmField
		val MEDIA_KEYS: Set<String> = setOf(
			KEY_MEDIA_BLUR_THUMBNAILS,
			KEY_MEDIA_BLUR_INTENSITY,
			KEY_MEDIA_GIF_TAP_ACTION,
			KEY_MEDIA_VIDEO_TAP_ACTION,
			KEY_MEDIA_DEFAULT_PLAYER_MODE,
			KEY_MEDIA_GIF_LOOP,
			KEY_MEDIA_GIF_FRAME_CONTROLS,
			KEY_MEDIA_VIDEO_LOOP,
			KEY_MEDIA_DEFAULT_SPEED,
			KEY_MEDIA_SKIP_INTERVAL,
			KEY_MEDIA_FLOATING_SIZE,
			KEY_MEDIA_FLOATING_POSITION,
			KEY_MEDIA_FLOATING_LOCK,
			KEY_MEDIA_VOLUME_GESTURE,
			KEY_MEDIA_BRIGHTNESS_GESTURE,
			KEY_MEDIA_PINCH_ZOOM,
			KEY_MEDIA_ASPECT_RATIO,
			KEY_MEDIA_QUEUE_PERSIST,
			KEY_MEDIA_QUEUE_REPEAT,
			KEY_MEDIA_QUEUE_SHUFFLE,
			KEY_BOORU_LONG_PRESS_ACTION,
			KEY_BOORU_VIDEO_ENGINE,
		)
		const val KEY_GRID_SIZE_PAGES = "grid_size_pages"
		const val KEY_REMOTE_SOURCES = "remote_sources"
		const val KEY_LOCAL_STORAGE = "local_storage"
		const val KEY_READER_DOUBLE_PAGES = "reader_double_pages"
		const val KEY_READER_ZOOM_BUTTONS = "reader_zoom_buttons"
		const val KEY_READER_CONTROL_LTR = "reader_taps_ltr"
		const val KEY_READER_NAVIGATION_INVERTED = "reader_navigation_inverted"
		const val KEY_READER_FULLSCREEN = "reader_fullscreen"
		const val KEY_READER_VOLUME_BUTTONS = "reader_volume_buttons"
		const val KEY_READER_ORIENTATION = "reader_orientation"
		const val KEY_TRACKER_ENABLED = "tracker_enabled"
		const val KEY_TRACKER_WIFI_ONLY = "tracker_wifi"
		const val KEY_TRACKER_FREQUENCY = "tracker_freq"
		const val KEY_TRACK_SOURCES = "track_sources"
		const val KEY_TRACK_CATEGORIES = "track_categories"
		const val KEY_TRACK_WARNING = "track_warning"
		const val KEY_TRACKER_NOTIFICATIONS = "tracker_notifications"
		const val KEY_TRACKER_NO_NSFW = "tracker_no_nsfw"
		const val KEY_TRACKER_DOWNLOAD = "tracker_download"
		const val KEY_NOTIFICATIONS_SETTINGS = "notifications_settings"
		const val KEY_NOTIFICATIONS_SOUND = "notifications_sound"
		const val KEY_NOTIFICATIONS_VIBRATE = "notifications_vibrate"
		const val KEY_NOTIFICATIONS_LIGHT = "notifications_light"
		const val KEY_NOTIFICATIONS_INFO = "tracker_notifications_info"
		const val KEY_READER_ANIMATION = "reader_animation2"
		const val KEY_READER_CONTROLS = "reader_controls"
		const val KEY_READER_MODE = "reader_mode"
		const val KEY_READER_MODE_DETECT = "reader_mode_detect"
		const val KEY_READER_CROP = "reader_crop"
		const val KEY_APP_PASSWORD = "app_password"
		const val KEY_APP_PASSWORD_NUMERIC = "app_password_num"
		const val KEY_PROTECT_APP = "protect_app"
		const val KEY_PROTECT_APP_BIOMETRIC = "protect_app_bio"
		const val KEY_ZOOM_MODE = "zoom_mode"
		const val KEY_BACKUP = "backup"
		const val KEY_RESTORE = "restore"
		const val KEY_BACKUP_PERIODICAL_ENABLED = "backup_periodic"
		const val KEY_BACKUP_PERIODICAL_FREQUENCY = "backup_periodic_freq"
		const val KEY_BACKUP_PERIODICAL_TRIM = "backup_periodic_trim"
		const val KEY_BACKUP_PERIODICAL_COUNT = "backup_periodic_count"
		const val KEY_BACKUP_PERIODICAL_OUTPUT = "backup_periodic_output"
		const val KEY_BACKUP_PERIODICAL_LAST = "backup_periodic_last"
		const val KEY_HISTORY_GROUPING = "history_grouping"
		const val KEY_UPDATED_GROUPING = "updated_grouping"
		const val KEY_PROGRESS_INDICATORS = "progress_indicators"
		const val KEY_REVERSE_CHAPTERS = "reverse_chapters"
		const val KEY_GRID_VIEW_CHAPTERS = "grid_view_chapters"
		const val KEY_INCOGNITO_NSFW = "incognito_nsfw"
		const val KEY_PAGES_NUMBERS = "pages_numbers"
		const val KEY_SCREENSHOTS_POLICY = "screenshots_policy"
		const val KEY_PAGES_PRELOAD = "pages_preload"
		const val KEY_SUGGESTIONS = "suggestions"
		const val KEY_SUGGESTIONS_WIFI_ONLY = "suggestions_wifi"
		const val KEY_SUGGESTIONS_EXCLUDE_NSFW = "suggestions_exclude_nsfw"
		const val KEY_SUGGESTIONS_EXCLUDE_TAGS = "suggestions_exclude_tags"
		const val KEY_SUGGESTIONS_DISABLED_SOURCES = "suggestions_disabled_sources"
		const val KEY_SUGGESTIONS_NOTIFICATIONS = "suggestions_notifications"
		const val KEY_SHIKIMORI = "shikimori"
		const val KEY_ANILIST = "anilist"
		const val KEY_MAL = "mal"
		const val KEY_KITSU = "kitsu"
		const val KEY_DOWNLOADS_METERED_NETWORK = "downloads_metered_network"
		const val KEY_DOWNLOADS_FORMAT = "downloads_format"
		const val KEY_ALL_FAVOURITES_VISIBLE = "all_favourites_visible"
		const val KEY_DOH = "doh"
		const val KEY_EXIT_CONFIRM = "exit_confirm"
		const val KEY_INCOGNITO_MODE = "incognito"
		const val KEY_READER_MULTITASK = "reader_multitask"
		const val KEY_SYNC = "sync"
		const val KEY_SYNC_SETTINGS = "sync_settings"
		const val KEY_READER_BAR = "reader_bar"
		const val KEY_READER_BAR_TRANSPARENT = "reader_bar_transparent"
		const val KEY_READER_BACKGROUND = "reader_background"
		const val KEY_READER_SCREEN_ON = "reader_screen_on"
		const val KEY_SHORTCUTS = "dynamic_shortcuts"
		const val KEY_READER_TAP_ACTIONS = "reader_tap_actions"
		const val KEY_READER_OPTIMIZE = "reader_optimize"
		const val KEY_LOCAL_LIST_ORDER = "local_order"
		const val KEY_HISTORY_ORDER = "history_order"
		const val KEY_FAVORITES_ORDER = "fav_order"
		const val KEY_WEBTOON_GAPS = "webtoon_gaps"
		const val KEY_WEBTOON_ZOOM = "webtoon_zoom"
		const val KEY_WEBTOON_ZOOM_OUT = "webtoon_zoom_out"
		const val KEY_PREFETCH_CONTENT = "prefetch_content"
		const val KEY_APP_LOCALE = "app_locale"
		const val KEY_SOURCES_GRID = "sources_grid"
		const val KEY_UPDATES_UNSTABLE = "updates_unstable"
		const val KEY_TIPS_CLOSED = "tips_closed"
		const val KEY_SSL_BYPASS = "ssl_bypass"
		const val KEY_READER_AUTOSCROLL_SPEED = "as_speed"
		const val KEY_READER_AUTOSCROLL_FAB = "as_fab"
		const val KEY_MIRROR_SWITCHING = "mirror_switching"
		const val KEY_PROXY = "proxy"
		const val KEY_PROXY_TYPE = "proxy_type_2"
		const val KEY_PROXY_ADDRESS = "proxy_address"
		const val KEY_PROXY_PORT = "proxy_port"
		const val KEY_PROXY_AUTH = "proxy_auth"
		const val KEY_PROXY_LOGIN = "proxy_login"
		const val KEY_PROXY_PASSWORD = "proxy_password"
		const val KEY_PROXY_SECRET = "proxy_secret"
		const val KEY_IMAGES_PROXY = "images_proxy_2"

		// Experimental wsrv.nl quality settings (revertable)
		const val KEY_WSRV_QUALITY = "wsrv_quality"
		const val KEY_WSRV_FORMAT = "wsrv_format"
		const val KEY_WSRV_MAX_WIDTH = "wsrv_max_width"
		const val KEY_WSRV_MAX_HEIGHT = "wsrv_max_height"
		const val KEY_WSRV_DPR = "wsrv_dpr"
		const val KEY_WSRV_NEVER_UPSCALE = "wsrv_never_upscale"
		const val KEY_WSRV_FIT = "wsrv_fit"
		const val KEY_WSRV_ALIGNMENT = "wsrv_alignment"
		const val KEY_WSRV_SHARPEN = "wsrv_sharpen"
		const val KEY_WSRV_BLUR = "wsrv_blur"
		const val KEY_WSRV_CONTRAST = "wsrv_contrast"
		const val KEY_WSRV_SATURATION = "wsrv_saturation"
		const val KEY_WSRV_GAMMA = "wsrv_gamma"
		const val KEY_WSRV_HUE = "wsrv_hue"
		const val KEY_WSRV_BRIGHTNESS = "wsrv_brightness"
		const val KEY_WSRV_TINT = "wsrv_tint"
		const val KEY_WSRV_PROGRESSIVE = "wsrv_progressive"
		const val KEY_WSRV_PNG_LEVEL = "wsrv_png_level"
		const val KEY_WSRV_PNG_FILTER = "wsrv_png_filter"
		const val KEY_WSRV_LOSSLESS = "wsrv_lossless"
		const val KEY_WSRV_WORKER_ENABLED = "wsrv_worker_enabled"
		const val KEY_WSRV_WORKER_URL = "wsrv_worker_url"
		const val KEY_WSRV_WORKER_QUERY_PARAM = "wsrv_worker_query_param"
		const val KEY_LOCAL_MANGA_DIRS = "local_manga_dirs"
		const val KEY_DISABLE_NSFW = "no_nsfw"
		const val KEY_RELATED_MANGA = "related_manga"
		const val KEY_NAV_MAIN = "nav_main"
		const val KEY_NAV_LABELS = "nav_labels"
		const val KEY_NAV_PINNED = "nav_pinned"
		const val KEY_MAIN_FAB = "main_fab"
		const val KEY_32BIT_COLOR = "enhanced_colors"
		const val KEY_SOURCES_ORDER = "sources_sort_order"
		const val KEY_SOURCES_CATALOG = "sources_catalog"
		const val KEY_CF_BRIGHTNESS = "cf_brightness"
		const val KEY_CF_CONTRAST = "cf_contrast"
		const val KEY_CF_INVERTED = "cf_inverted"
		const val KEY_CF_GRAYSCALE = "cf_grayscale"
		const val KEY_CF_BOOK = "cf_book"
		const val KEY_PAGES_TAB = "pages_tab"
		const val KEY_DETAILS_TAB = "details_tab"
		const val KEY_DETAILS_LAST_TAB = "details_last_tab"
		const val KEY_READING_TIME = "reading_time"
		const val KEY_PAGES_SAVE_DIR = "pages_dir"
		const val KEY_PAGES_SAVE_ASK = "pages_dir_ask"
		const val KEY_PAGES_SAVE_ORIGINAL = "pages_save_original"
		const val KEY_STATS_ENABLED = "stats_on"
		const val KEY_FEED_HEADER = "feed_header"
		const val KEY_SEARCH_SUGGESTION_TYPES = "search_suggest_types"
		const val KEY_SOURCES_VERSION = "sources_version"
		const val KEY_SOURCES_ENABLED_ALL = "sources_enabled_all"
		const val KEY_QUICK_FILTER = "quick_filter"
		const val KEY_COLLAPSE_DESCRIPTION = "description_collapse"
		const val KEY_BACKUP_TG_ENABLED = "backup_periodic_tg_enabled"
		const val KEY_BACKUP_TG_CHAT = "backup_periodic_tg_chat_id"
		const val KEY_MANGA_LIST_BADGES = "manga_list_badges"
		const val KEY_TAGS_WARNINGS = "tags_warnings"
		const val KEY_DISCORD_RPC = "discord_rpc"
		const val KEY_DISCORD_RPC_SKIP_NSFW = "discord_rpc_skip_nsfw"
		const val KEY_DISCORD_TOKEN = "discord_token"

		// keys for non-persistent preferences
		const val KEY_APP_VERSION = "app_version"
		const val KEY_IGNORE_DOZE = "ignore_dose"
		const val KEY_TRACKER_DEBUG = "tracker_debug"
		const val KEY_LINK_WEBLATE = "about_app_translation"
		const val KEY_LINK_TELEGRAM = "about_telegram"
		const val KEY_LINK_GITHUB = "about_github"
		const val KEY_LINK_MANUAL = "about_help"
		const val KEY_PROXY_TEST = "proxy_test"
		const val KEY_OPEN_BROWSER = "open_browser"
		const val KEY_HANDLE_LINKS = "handle_links"
		const val KEY_BACKUP_TG = "backup_periodic_tg"
		const val KEY_BACKUP_TG_OPEN = "backup_periodic_tg_open"
		const val KEY_BACKUP_TG_TEST = "backup_periodic_tg_test"
		const val KEY_CLEAR_MANGA_DATA = "manga_data_clear"
		const val KEY_STORAGE_USAGE = "storage_usage"
		const val KEY_WEBVIEW_CLEAR = "webview_clear"

		// old keys are for migration only
		private const val KEY_IMAGES_PROXY_OLD = "images_proxy"

		// values
		private const val READER_CROP_PAGED = 1
		private const val READER_CROP_WEBTOON = 2
		private const val BOORU_GRID_COLUMNS_DEFAULT = 3
		private const val BOORU_GRID_COLUMNS_MIN = 2
		private const val BOORU_GRID_COLUMNS_MAX = 4
	}
}
