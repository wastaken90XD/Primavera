package org.wastaken.kotatsu.api21.core.network

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.wastaken.kotatsu.api21.BuildConfig
import org.wastaken.kotatsu.api21.core.network.cookies.AndroidCookieJar
import org.wastaken.kotatsu.api21.core.network.cookies.MutableCookieJar
import org.wastaken.kotatsu.api21.core.network.cookies.PreferencesCookieJar
import org.wastaken.kotatsu.api21.core.network.imageproxy.ImageProxyInterceptor
import org.wastaken.kotatsu.api21.core.network.imageproxy.RealImageProxyInterceptor
import org.wastaken.kotatsu.api21.core.network.proxy.ProxyProvider
import org.wastaken.kotatsu.api21.core.network.proxypool.PoolCategory
import org.wastaken.kotatsu.api21.core.network.proxypool.PoolMode
import org.wastaken.kotatsu.api21.core.network.proxypool.PooledClients
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyPoolRoutingInterceptor
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyPoolState
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.util.ext.assertNotInMainThread
import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug
import org.wastaken.kotatsu.api21.local.data.LocalStorageManager
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface NetworkModule {

	@Binds
	fun bindCookieJar(androidCookieJar: MutableCookieJar): CookieJar

	@Binds
	fun bindImageProxyInterceptor(impl: RealImageProxyInterceptor): ImageProxyInterceptor

	companion object {

		@Provides
		@Singleton
		fun provideCookieJar(
			@ApplicationContext context: Context
		): MutableCookieJar = runCatching {
			AndroidCookieJar()
		}.getOrElse { e ->
			e.printStackTraceDebug()
			// WebView is not available
			PreferencesCookieJar(context)
		}

		@Provides
		@Singleton
		fun provideHttpCache(
			localStorageManager: LocalStorageManager,
		): Cache = localStorageManager.createHttpCache()

		/**
		 * Base tier. Transport is EXACTLY what it was before the proxy pool
		 * existed: `proxySelector(proxyProvider.selector)`. Nothing from the pool
		 * touches the transport - the pool works only through the routing
		 * interceptor below and the clients derived from these tiers.
		 *
		 * The routing interceptor is installed only when the mode is not OFF, and
		 * it is installed at client BUILD time, so switching between Off and any
		 * other mode needs an app restart (the settings screen says so). With the
		 * pool Off there is no pool interceptor in any client at all.
		 */
		@Provides
		@Singleton
		@BaseHttpClient
		fun provideBaseHttpClient(
			@ApplicationContext contextProvider: Provider<Context>,
			cache: Cache,
			cookieJar: CookieJar,
			settings: AppSettings,
			proxyProvider: ProxyProvider,
		): OkHttpClient {
			val poolInstalled = settings.poolMode != PoolMode.OFF
			val builder = OkHttpClient.Builder().apply {
				assertNotInMainThread()
				connectTimeout(20, TimeUnit.SECONDS)
				readTimeout(60, TimeUnit.SECONDS)
				writeTimeout(20, TimeUnit.SECONDS)
				cookieJar(cookieJar)
				proxySelector(proxyProvider.selector)
				proxyAuthenticator(proxyProvider.authenticator)
				dns(DoHManager(cache, settings))
				if (settings.isSSLBypassEnabled) {
					disableCertificateVerification()
				} else {
					installExtraCertificates(contextProvider.get())
				}
				cache(cache)
				addInterceptor(GZipInterceptor())
				addInterceptor(CloudFlareInterceptor())
				addInterceptor(RateLimitInterceptor())
				if (BuildConfig.DEBUG) {
					addInterceptor(CurlLoggingInterceptor())
				}
			}
			if (poolInstalled) {
				// outermost, so a pooled request skips the rest of this chain:
				// no CloudFlareInterceptor on proxy attempts, one rate limiter
				builder.interceptors().add(0, ProxyPoolRoutingInterceptor(PoolCategory.APP_SERVICES))
			}
			val client = builder.build()
			if (poolInstalled) {
				ProxyPoolState.attach(contextProvider.get(), settings, client)
				PooledClients.registerTier(PoolCategory.APP_SERVICES, client)
			}
			return client
		}

		/**
		 * Manga tier: parser requests, listings and pages - plus the Coil image
		 * tier, which is derived from this client and so shares its category.
		 * Inherits the static selector from the base builder unchanged.
		 */
		@Provides
		@Singleton
		@MangaHttpClient
		fun provideMangaHttpClient(
			@BaseHttpClient baseClient: OkHttpClient,
			commonHeadersInterceptor: CommonHeadersInterceptor,
			settings: AppSettings,
		): OkHttpClient {
			val poolInstalled = settings.poolMode != PoolMode.OFF
			val builder = baseClient.newBuilder().apply {
				addNetworkInterceptor(CacheLimitInterceptor())
				addInterceptor(commonHeadersInterceptor)
			}
			if (poolInstalled) {
				replaceRoutingInterceptor(builder, PoolCategory.SOURCES_IMAGES)
			}
			val client = builder.build()
			if (poolInstalled) {
				PooledClients.registerTier(PoolCategory.SOURCES_IMAGES, client)
			}
			return client
		}

		/**
		 * Media/playback tier. Re-pinned to ProxyProvider's static selector, so
		 * the transport here is the same static selector as everywhere else;
		 * whether video may use the pool is decided by the VIDEO category switch,
		 * read per request, not by the transport.
		 */
		@Provides
		@Singleton
		@VideoHttpClient
		fun provideVideoHttpClient(
			@BaseHttpClient baseClient: OkHttpClient,
			commonHeadersInterceptor: CommonHeadersInterceptor,
			proxyProvider: ProxyProvider,
			settings: AppSettings,
		): OkHttpClient {
			val poolInstalled = settings.poolMode != PoolMode.OFF
			val builder = baseClient.newBuilder().apply {
				proxySelector(proxyProvider.selector)
				addNetworkInterceptor(CacheLimitInterceptor())
				addInterceptor(commonHeadersInterceptor)
			}
			if (poolInstalled) {
				replaceRoutingInterceptor(builder, PoolCategory.VIDEO)
			}
			val client = builder.build()
			if (poolInstalled) {
				PooledClients.registerTier(PoolCategory.VIDEO, client)
			}
			return client
		}

		/**
		 * Every tier derives from the base client, so it inherits the base tier's
		 * routing interceptor. A derived tier swaps it for one carrying its own
		 * category: keeping both would mean every source request was also judged
		 * as an app-service request.
		 */
		private fun replaceRoutingInterceptor(builder: OkHttpClient.Builder, category: PoolCategory) {
			builder.interceptors().removeAll { it is ProxyPoolRoutingInterceptor }
			builder.interceptors().add(0, ProxyPoolRoutingInterceptor(category))
		}

	}
}
