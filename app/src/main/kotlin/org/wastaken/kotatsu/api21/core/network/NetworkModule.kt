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
import org.wastaken.kotatsu.api21.core.network.proxypool.PoolMode
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
			// PoolRoutingInterceptor sits OUTERMOST per tier (6/7 spec): one
			// instance installed on THIS tier, only when the pool is built
			// with a non-OFF mode (same apply-at-build precedent as the SSL
			// bypass); Off<->any-mode applies after an app restart
			// (amendment 3; note lives on the settings screen). Each pooled
			// attempt becomes its own call on a pooled client that inherits
			// everything below except CloudFlare + this interceptor
			// (amendment 4).
			installPoolRouting(this, settings, ProxyPoolController.PoolTier.BASE)
			addInterceptor(GZipInterceptor())
			addInterceptor(CloudFlareInterceptor())
			addInterceptor(RateLimitInterceptor())
			if (BuildConfig.DEBUG) {
				addInterceptor(CurlLoggingInterceptor())
			}
		}.build().also { client ->
			// Controller attach: pins the build-time pool mode (amendment 3)
			// and loads the saved health state. Cheap, off-thread. 5/7: the
			// provider rides along (read-only) so the bootstrap can fetch
			// lists through the static-proxy gateway and CHAINED can bind
			// it as the relay gateway.
			ProxyPoolController.attach(contextProvider.get(), settings, client, proxyProvider)
		}

		@Provides
		@Singleton
		@MangaHttpClient
		fun provideMangaHttpClient(
			@BaseHttpClient baseClient: OkHttpClient,
			commonHeadersInterceptor: CommonHeadersInterceptor,
			settings: AppSettings,
		): OkHttpClient = baseClient.newBuilder().apply {
			// 6/7 spec: per-tier routing interceptor, outermost on this tier
			installPoolRouting(this, settings, ProxyPoolController.PoolTier.MANGA)
			addNetworkInterceptor(CacheLimitInterceptor())
			addInterceptor(commonHeadersInterceptor)
		}.build()

		/**
		 * Media/playback tier (6/7 spec): the spec's per-tier install gets a
		 * VIDEO-tagged routing interceptor here (outermost on this tier),
		 * and the VIDEO tag makes the interceptor pass every request
		 * through in-engine - the Task-B guarantee ("the pool never plays
		 * media") is enforced by the tier tag, not by interceptor removal,
		 * plus ProxyProvider's static selector stays re-pinned. Belt and
		 * braces remains: without the tag the old removal still applied.
		 * The static-proxy authenticator is inherited from the base builder.
		 */
		@Provides
		@Singleton
		@VideoHttpClient
		fun provideVideoHttpClient(
			@BaseHttpClient baseClient: OkHttpClient,
			commonHeadersInterceptor: CommonHeadersInterceptor,
			proxyProvider: ProxyProvider,
			settings: AppSettings,
		): OkHttpClient = baseClient.newBuilder().apply {
			proxySelector(proxyProvider.selector)
			installPoolRouting(this, settings, ProxyPoolController.PoolTier.VIDEO)
			addNetworkInterceptor(CacheLimitInterceptor())
			addInterceptor(commonHeadersInterceptor)
		}.build()

		/** 6/7 spec per-tier install: removes any routing interceptor
		 *  inherited from the base builder, then - only when the pool was
		 *  built with a non-OFF mode (same apply-at-build precedent as the
		 *  SSL bypass) - inserts one tagged instance OUTERMOST on the given
		 *  tier's chain. Mode flips between OFF and anything else therefore
		 *  wait for restart, which amendment 3's status line already says. */
		private fun installPoolRouting(
			builder: OkHttpClient.Builder,
			settings: AppSettings,
			tier: ProxyPoolController.PoolTier,
		) {
			builder.interceptors().removeAll { it is PoolRoutingInterceptor }
			if (settings.poolMode != PoolMode.OFF) {
				builder.interceptors().add(0, PoolRoutingInterceptor(settings, tier))
			}
		}

	}
}
