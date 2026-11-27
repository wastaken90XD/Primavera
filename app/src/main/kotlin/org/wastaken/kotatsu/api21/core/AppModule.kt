package org.wastaken.kotatsu.api21.core

import android.app.Application
import android.content.Context
import android.provider.SearchRecentSuggestions
import android.os.Build
import android.text.Html
import androidx.collection.arraySetOf
import androidx.core.content.ContextCompat
import androidx.room.InvalidationTracker
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.svg.SvgDecoder
import coil3.util.DebugLogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import org.wastaken.kotatsu.api21.BuildConfig
import org.wastaken.kotatsu.api21.backups.domain.BackupObserver
import org.wastaken.kotatsu.api21.core.db.MangaDatabase
import org.wastaken.kotatsu.api21.core.exceptions.resolve.CaptchaHandler
import org.wastaken.kotatsu.api21.core.image.AvifImageDecoder
import org.wastaken.kotatsu.api21.core.image.CbzFetcher
import org.wastaken.kotatsu.api21.core.image.MangaSourceHeaderInterceptor
import org.wastaken.kotatsu.api21.core.network.MangaHttpClient
import org.wastaken.kotatsu.api21.core.network.imageproxy.ImageProxyInterceptor
import org.wastaken.kotatsu.api21.core.os.AppShortcutManager
import org.wastaken.kotatsu.api21.core.os.NetworkState
import org.wastaken.kotatsu.api21.core.parser.MangaLoaderContextImpl
import org.wastaken.kotatsu.api21.core.parser.favicon.FaviconFetcher
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.ui.image.CoilImageGetter
import org.wastaken.kotatsu.api21.core.ui.util.ActivityRecreationHandle
import org.wastaken.kotatsu.api21.core.util.AcraScreenLogger
import org.wastaken.kotatsu.api21.core.util.FileSize
import org.wastaken.kotatsu.api21.core.util.ext.connectivityManager
import org.wastaken.kotatsu.api21.core.util.ext.isLowRamDevice
import org.wastaken.kotatsu.api21.details.ui.pager.pages.MangaPageFetcher
import org.wastaken.kotatsu.api21.details.ui.pager.pages.MangaPageKeyer
import org.wastaken.kotatsu.api21.local.data.CacheDir
import org.wastaken.kotatsu.api21.local.data.FaviconCache
import org.wastaken.kotatsu.api21.local.data.LocalStorageCache
import org.wastaken.kotatsu.api21.local.data.LocalStorageChanges
import org.wastaken.kotatsu.api21.local.data.PageCache
import org.wastaken.kotatsu.api21.local.domain.model.LocalManga
import org.wastaken.kotatsu.api21.main.domain.CoverRestoreInterceptor
import org.wastaken.kotatsu.api21.main.ui.protect.AppProtectHelper
import org.wastaken.kotatsu.api21.main.ui.protect.ScreenshotPolicyHelper
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.wastaken.kotatsu.api21.search.ui.MangaSuggestionsProvider
import org.wastaken.kotatsu.api21.sync.domain.SyncController
import org.wastaken.kotatsu.api21.widget.WidgetUpdater
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface AppModule {

	@Binds
	fun bindMangaLoaderContext(mangaLoaderContextImpl: MangaLoaderContextImpl): MangaLoaderContext

	@Binds
	fun bindImageGetter(coilImageGetter: CoilImageGetter): Html.ImageGetter

	companion object {

		@Provides
		@LocalizedAppContext
		fun provideLocalizedContext(
			@ApplicationContext context: Context,
		): Context = ContextCompat.getContextForLanguage(context)

		@Provides
		@Singleton
		fun provideNetworkState(
			@ApplicationContext context: Context,
			settings: AppSettings,
		) = NetworkState(context.connectivityManager, settings)

		@Provides
		@Singleton
		fun provideMangaDatabase(
			@ApplicationContext context: Context,
		): MangaDatabase = MangaDatabase(context)

		@Provides
		@Singleton
		fun provideCoil(
			@LocalizedAppContext context: Context,
			@MangaHttpClient okHttpClientProvider: Provider<OkHttpClient>,
			faviconFetcherFactory: FaviconFetcher.Factory,
			imageProxyInterceptor: ImageProxyInterceptor,
			pageFetcherFactory: MangaPageFetcher.Factory,
			coverRestoreInterceptor: CoverRestoreInterceptor,
			networkStateProvider: Provider<NetworkState>,
			captchaHandler: CaptchaHandler,
		): ImageLoader {
			val diskCacheFactory = {
				val rootDir = context.externalCacheDir ?: context.cacheDir
				DiskCache.Builder()
					.directory(rootDir.resolve(CacheDir.THUMBS.dir))
					.build()
			}
			val okHttpClientLazy = lazy {
				okHttpClientProvider.get().newBuilder().cache(null).build()
			}
			return ImageLoader.Builder(context)
				.interceptorCoroutineContext(Dispatchers.Default)
				.diskCache(diskCacheFactory)
				.logger(if (BuildConfig.DEBUG) DebugLogger() else null)
				// pre-26 bitmap pixels live on the dalvik/java heap directly: every reader
				// page/gallery bitmap fragments it. Allowing RGB_565 there halves bitmap
				// bytes and is the main lever against fragmentation OOMs on API 21-25
				// devices (e.g. OkHttp bystander crashes like failed 8KB okio.Segment
				// allocation with hundreds of MB heap room left). API 26+ pixels are
				// native-side and unaffected, so restrict the trade-off to <26.
				.allowRgb565(context.isLowRamDevice() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
				.eventListener(captchaHandler)
				.components {
					add(
						OkHttpNetworkFetcherFactory(
							callFactory = okHttpClientLazy::value,
							connectivityChecker = { networkStateProvider.get() },
						),
					)
					// decoder selection identical to the base fork: hardware ImageDecoder
					// on API 28+, software Movie decoder below. The booru GIF overlay
					// works with both (its result is started explicitly, GifPageOverlay)
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
						add(AnimatedImageDecoder.Factory())
					} else {
						add(GifDecoder.Factory())
					}
					add(SvgDecoder.Factory())
					add(CbzFetcher.Factory())
					add(AvifImageDecoder.Factory())
					add(faviconFetcherFactory)
					add(MangaPageKeyer())
					add(pageFetcherFactory)
					add(imageProxyInterceptor)
					add(coverRestoreInterceptor)
					add(MangaSourceHeaderInterceptor())
				}.build()
		}

		@Provides
		fun provideSearchSuggestions(
			@ApplicationContext context: Context,
		): SearchRecentSuggestions = MangaSuggestionsProvider.createSuggestions(context)

		@Provides
		@ElementsIntoSet
		fun provideDatabaseObservers(
			widgetUpdater: WidgetUpdater,
			appShortcutManager: AppShortcutManager,
			backupObserver: BackupObserver,
			syncController: SyncController,
		): Set<@JvmSuppressWildcards InvalidationTracker.Observer> = arraySetOf(
			widgetUpdater,
			appShortcutManager,
			backupObserver,
			syncController,
		)

		@Provides
		@ElementsIntoSet
		fun provideActivityLifecycleCallbacks(
			appProtectHelper: AppProtectHelper,
			activityRecreationHandle: ActivityRecreationHandle,
			acraScreenLogger: AcraScreenLogger,
			screenshotPolicyHelper: ScreenshotPolicyHelper,
		): Set<@JvmSuppressWildcards Application.ActivityLifecycleCallbacks> = arraySetOf(
			appProtectHelper,
			activityRecreationHandle,
			acraScreenLogger,
			screenshotPolicyHelper,
		)

		@Provides
		@Singleton
		@LocalStorageChanges
		fun provideMutableLocalStorageChangesFlow(): MutableSharedFlow<LocalManga?> = MutableSharedFlow()

		@Provides
		@LocalStorageChanges
		fun provideLocalStorageChangesFlow(
			@LocalStorageChanges flow: MutableSharedFlow<LocalManga?>,
		): SharedFlow<LocalManga?> = flow.asSharedFlow()

		@Provides
		fun provideWorkManager(
			@ApplicationContext context: Context,
		): WorkManager = WorkManager.getInstance(context)

		@Provides
		@Singleton
		@PageCache
		fun providePageCache(
			@ApplicationContext context: Context,
		) = LocalStorageCache(
			context = context,
			dir = CacheDir.PAGES,
			defaultSize = FileSize.MEGABYTES.convert(200, FileSize.BYTES),
			minSize = FileSize.MEGABYTES.convert(20, FileSize.BYTES),
		)

		@Provides
		@Singleton
		@FaviconCache
		fun provideFaviconCache(
			@ApplicationContext context: Context,
		) = LocalStorageCache(
			context = context,
			dir = CacheDir.FAVICONS,
			defaultSize = FileSize.MEGABYTES.convert(8, FileSize.BYTES),
			minSize = FileSize.MEGABYTES.convert(2, FileSize.BYTES),
		)
	}
}
