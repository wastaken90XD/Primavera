package org.wastaken.kotatsu.api21.core.network.proxypool

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyPoolController.FailureClass
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import java.io.IOException

/**
 * Proxy pool (experimental) - component 4/5: the two request-path interceptors.
 * Both are installed from the same single NetworkModule hook site.
 *
 * [ProxyPoolRetryInterceptor] (application interceptor, registered FIRST so
 * it wraps the full chain):
 *  - only when the pool is active (mode on, non-inert), https-only, and the
 *    request is a cache-safe GET/HEAD without a body (spec 5.4 "only
 *    idempotent requests");
 *  - on a post-request reset-class IOException, retries up to MAX_ATTEMPTS
 *    total attempts; each new attempt re-enters route selection, so the
 *    selector's rotation moves it to a different healthy proxy;
 *  - HTTP 429/503 is returned untouched - those NEVER rotate the proxy
 *    (spec 5.6: they are origin answers, not transport failures);
 *  - per-host outcome logging (amendment 7): mode, failure class and the
 *    final outcome (succeeded direct / succeeded via proxy / failed through
 *    all) via the paired route interceptor for success and here for
 *    exhaustion.
 *
 * [PoolRouteInterceptor] (network interceptor; runs after Connect, so the
 * ROUTE IS KNOWN):
 *  - when the leg's route is one of the pool's healthy proxies
 *    (sa matches a healthy entry - static-proxy routes never match),
 *    it strips Cloudflare cookies (cf_* / __cf*) from the outgoing Cookie
 *    header AND from the response's Set-Cookie so a clearance minted for
 *    the proxy's IP cannot sideline the shared jar (amendment 2 - the
 *    "filtering cookie jar" behavior realized at the only point OkHttp
 *    exposes route information);
 *  - logs per-leg success/failure attribution (direct vs pool) with the
 *    failure class for failures. Direct-path successes for unmarked hosts
 *    are NOT logged (per-request log flood guard for image-heavy tiers).
 */
class ProxyPoolRetryInterceptor(
	private val settings: AppSettings,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (settings.poolMode == PoolMode.OFF || ProxyPoolController.isInert()) {
			return chain.proceed(request)
		}
		val host = request.url.host.lowercase()
		if (request.url.scheme != "https" || ProxyPoolController.isHardExcluded(host, settings)) {
			return chain.proceed(request)
		}
		// idempotent requests only (spec 5.4): GET/HEAD with no body
		val method = request.method
		if ((method != "GET" && method != "HEAD") || request.body != null) {
			return chain.proceed(request)
		}
		var attempt = 0
		var lastClass = FailureClass.OTHER
		while (true) {
			try {
				val response = chain.proceed(request)
				if (response.code == 429 || response.code == 503) {
					// 429/503 never rotates the proxy (spec 5.6)
					return response
				}
				return response
			} catch (e: IOException) {
				attempt++
				lastClass = ProxyPoolController.classifyFailure(e)
				if (lastClass != FailureClass.RESET || attempt >= MAX_ATTEMPTS) {
					Log.w(
						TAG,
						"host=$host mode=${settings.poolMode} outcome=failed-through-all " +
							"attempts=$attempt last=$lastClass",
					)
					throw e
				}
				Log.i(
					TAG,
					"host=$host post-request reset; retry ${attempt + 1}/$MAX_ATTEMPTS " +
						"(new route rotation may pick another proxy)",
				)
			}
		}
	}

	private companion object {
		private const val TAG = "ProxyPool"
		private const val MAX_ATTEMPTS = 3
	}
}

class PoolRouteInterceptor(
	private val settings: AppSettings,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (ProxyPoolController.isInert() || request.url.scheme != "https") {
			return chain.proceed(request)
		}
		val connection = chain.connection() ?: return chain.proceed(request)
		val route = connection.route()
		val socketAddress = route.socketAddress()
		val onPool = ProxyPoolController.isPoolProxyAddress(socketAddress)
		val host = request.url.host.lowercase()
		val adjusted = if (onPool) request.stripCloudflareCookieHeader() else request
		return try {
			val response = chain.proceed(adjusted)
			if (onPool) {
				ProxyPoolController.keyOfProxyAddress(socketAddress)?.let {
					ProxyPoolController.reportProxySuccess(it)
				}
				Log.i(TAG, "host=$host mode=${settings.poolMode} outcome=success via pool proxy")
				response.stripCloudflareSetCookies()
			} else if (ProxyPoolController.isMarkedHost(host)) {
				// evidence for the learned-stickiness narrative (spec 5.3)
				Log.i(TAG, "host=$host mode=${settings.poolMode} outcome=success direct (marked host)")
				response
			} else {
				response
			}
		} catch (e: IOException) {
			val cls = ProxyPoolController.classifyFailure(e)
			Log.i(
				TAG,
				"host=$host mode=${settings.poolMode} leg failed ($cls) via ${if (onPool) "pool" else "direct"}",
			)
			throw e
		}
	}

	/** Keeps every cookie except cf_* / __cf* when the route is a pool proxy (amendment 2). */
	private fun Request.stripCloudflareCookieHeader(): Request {
		val header = header("Cookie") ?: return this
		val kept = header.split(';')
			.map { it.trim() }
			.filter { pair ->
				val name = pair.substringBefore('=').lowercase()
				!ProxyPoolController.isCloudflareCookie(name)
			}
		val builder = newBuilder().removeHeader("Cookie")
		if (kept.isNotEmpty()) {
			builder.header("Cookie", kept.joinToString("; "))
		}
		return builder.build()
	}

	/** cf_* cookies minted through a pool proxy never reach the shared jar. */
	private fun Response.stripCloudflareSetCookies(): Response {
		val setCookies = headers("Set-Cookie")
		if (setCookies.isEmpty()) {
			return this
		}
		val kept = setCookies.filter { value ->
			val name = value.substringBefore('=').trim().lowercase()
			!ProxyPoolController.isCloudflareCookie(name)
		}
		if (kept.size == setCookies.size) {
			return this
		}
		return newBuilder()
			.removeHeader("Set-Cookie")
			.apply { kept.forEach { addHeader("Set-Cookie", it) } }
			.build()
	}

	private companion object {
		private const val TAG = "ProxyPool"
	}
}
