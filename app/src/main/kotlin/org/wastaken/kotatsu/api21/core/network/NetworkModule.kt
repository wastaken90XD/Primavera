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
import org.wastaken.kotatsu.api21.core.network.proxypool.PoolRoutingInterceptor
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyPoolController
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

		@Provides
		@Singleton
		@BaseHttpClient
		fun provideBaseHttpClient(
			@ApplicationContext contextProvider: Provider<Context>,
			cache: Cache,
			cookieJar: CookieJar,
			settings: AppSettings,
			proxyProvider: ProxyProvider,
		): OkHttpClient = OkHttpClient.Builder().apply {
			assertNotInMainThread()
			connectTimeout(20, TimeUnit.SECONDS)
			readTimeout(60, TimeUnit.SECONDS)
			writeTimeout(20, TimeUnit.SECONDS)
			cookieJar(cookieJar)
			// Proxy pool rework (Task C 3/7, amendment 1): the transport
			// selector is ProxyProvider.selector, ALWAYS - the old wrapping
			// ProxyPoolSelector is gone. The pool now routes per request via
			// PoolRoutingInterceptor + pooled clients; with the pool OFF the
			// interceptor is a pure pass-through, so this builder is exactly
			// what a pool-less build would see (off = off).
			proxySelector(proxyProvider.selector)
			proxyAuthenticator(proxyProvider.authenticator)
			dns(DoHManager(cache, settings))
			if (settings.isSSLBypassEnabled) {
				disableCertificateVerification()
			} else {
				installExtraCertificates(contextProvider.get())
			}
			cache(cache)
			// PoolRoutingInterceptor sits OUTERMOST (registered first): each
			// direct attempt proceeds down the normal chain; each pooled
			// attempt becomes its own call on a pooled client that inherits
			// everything below except CloudFlare + this interceptor
			// (amendment 4). Build-time wiring: Off<->any-mode applies after
			// an app restart (amendment 3; note lives on the settings screen).
			addInterceptor(PoolRoutingInterceptor(settings))
			addInterceptor(GZipInterceptor())
			addInterceptor(CloudFlareInterceptor())
			addInterceptor(RateLimitInterceptor())
			if (BuildConfig.DEBUG) {
				addInterceptor(CurlLoggingInterceptor())
			}
		}.build().also { client ->
			// Controller attach: pins the build-time pool mode (amendment 3)
			// and loads the saved health state. Cheap, off-thread.
			ProxyPoolController.attach(contextProvider.get(), settings, client)
		}

		@Provides
		@Singleton
		@MangaHttpClient
		fun provideMangaHttpClient(
			@BaseHttpClient baseClient: OkHttpClient,
			commonHeadersInterceptor: CommonHeadersInterceptor,
		): OkHttpClient = baseClient.newBuilder().apply {
			addNetworkInterceptor(CacheLimitInterceptor())
			addInterceptor(commonHeadersInterceptor)
		}.build()

		/**
		 * Media/playback tier: same shape as the manga tier (cache limit +
		 * common headers) but re-pinned to ProxyProvider's static selector,
		 * so the experimental proxy pool is never used for video (spec 5.7 /
		 * Task-B guarantee). The routing interceptor is REMOVED here even
		 * though every mode also skip-filters media hosts: belt and braces,
		 * zero behavior ambiguity for ExoPlayer/AV streams. The static-proxy
		 * authenticator is inherited from the base builder.
		 */
		@Provides
		@Singleton
		@VideoHttpClient
		fun provideVideoHttpClient(
			@BaseHttpClient baseClient: OkHttpClient,
			commonHeadersInterceptor: CommonHeadersInterceptor,
			proxyProvider: ProxyProvider,
		): OkHttpClient = baseClient.newBuilder().apply {
			proxySelector(proxyProvider.selector)
			interceptors().removeAll { it is PoolRoutingInterceptor }
			addNetworkInterceptor(CacheLimitInterceptor())
			addInterceptor(commonHeadersInterceptor)
		}.build()

	}
}
