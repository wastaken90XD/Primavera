package org.wastaken.kotatsu.api21.core.network.proxypool

import android.util.Log
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The pool's only request-path component. Installed as the OUTERMOST application
 * interceptor of each tier that has the pool enabled, one instance per category
 * (base tier = app services, manga tier = sources and images, video tier =
 * video), so the category is a property of the tier and never guessed from the
 * request.
 *
 * It is installed only when the mode is not OFF, at client build time, which is
 * why switching to or from OFF needs an app restart. Everything else - the mode,
 * the category switches, the never-use list - is read per request and applies
 * live.
 *
 * How a pooled attempt works: the request is handed to a POOLED client
 * (see [PooledClients]) and the response is returned directly, WITHOUT calling
 * chain.proceed(). The rest of this chain therefore never runs for a pooled
 * request, which is what keeps CloudFlareInterceptor off proxy attempts and
 * keeps the rate limiter running exactly once.
 *
 * Retries: GET and HEAD only, Range requests included, never anything with a
 * body - so a POST is neither proxied nor retried. 429 and 503 are origin
 * answers and are returned untouched: they never rotate the proxy.
 *
 * Cancellation: a pooled request runs on a second OkHttp call, and cancelling
 * the outer call does not cancel it by itself. [PoolCallCancellation] watches
 * in-flight pairs and cancels the inner call, which matters because ExoPlayer
 * cancels its reads on every seek - without this, every seek would leave a
 * proxied download running to completion.
 */
class ProxyPoolRoutingInterceptor(
	private val category: PoolCategory,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (!isPoolable(request)) {
			return chain.proceed(request)
		}
		val host = request.url.host.lowercase()
		return when (ProxyPoolState.mode) {
			PoolMode.OFF -> chain.proceed(request)
			PoolMode.FALLBACK -> if (ProxyPoolState.isLearnedHost(host)) {
				poolFirst(chain, request, host)
			} else {
				directFirst(chain, request, host)
			}

			PoolMode.ALWAYS -> poolFirst(chain, request, host)
		}
	}

	/**
	 * Only https GET/HEAD on a host the pool is allowed to touch. A POST is never
	 * proxied, so it can never be retried either.
	 */
	private fun isPoolable(request: Request): Boolean {
		if (!ProxyPoolState.isApplicable(category)) {
			return false
		}
		if (request.url.scheme != "https") {
			return false
		}
		val method = request.method
		if (method != "GET" && method != "HEAD") {
			return false
		}
		val host = request.url.host.lowercase()
		return !ProxyPoolState.isNeverUse(host) && !ProxyPoolState.isChallengeBlocked(host)
	}

	// region the two orders

	private fun directFirst(chain: Interceptor.Chain, request: Request, host: String): Response {
		try {
			val response = chain
				.withConnectTimeout(ProxyPoolState.directTimeoutMs(), TimeUnit.MILLISECONDS)
				.proceed(request)
			if (isOriginAnswer(response.code)) {
				return response // 429/503: the origin answered, never a reason to rotate
			}
			// A direct success changes nothing: a learned host stays learned until
			// the user clears it or the pool fails for it (that is the point of
			// learning it - the direct route works for everyone else anyway).
			return response
		} catch (e: IOException) {
			if (chain.call().isCanceled()) {
				throw e
			}
			val cls = ProxyPoolState.classifyFailure(e)
			if (!cls.isTransportFailure) {
				throw e
			}
			ProxyPoolState.reportDirectFailure(host, cls)
			Log.i(TAG, "host=$host direct failed ($cls); trying the pool")
		}
		return viaPool(chain, request, host)
	}

	private fun poolFirst(chain: Interceptor.Chain, request: Request, host: String): Response {
		try {
			return viaPool(chain, request, host)
		} catch (e: IOException) {
			if (chain.call().isCanceled()) {
				throw e
			}
			// fail-open tail: the pool is a workaround, never a hard dependency
			Log.i(TAG, "host=$host pool exhausted (${e.javaClass.simpleName}); falling back to the existing route")
			return chain.proceed(request)
		}
	}

	// endregion

	/**
	 * Runs the request through pooled clients until one answers, the category's
	 * attempt budget is used up, or the pool runs out of routes.
	 */
	private fun viaPool(chain: Interceptor.Chain, request: Request, host: String): Response {
		val outer = chain.call()
		var lastError: IOException? = null
		var attempts = 0
		var challenges = 0
		val tried = HashSet<String>(MAX_POOL_ATTEMPTS)
		while (attempts < MAX_POOL_ATTEMPTS) {
			if (outer.isCanceled()) {
				throw lastError ?: IOException("canceled")
			}
			val route = ProxyPoolState.nextRoute(host) ?: break
			// one attempt per distinct route: re-dialing a route that just failed
			// (a chain keeps its route key while its main proxy rotates) only
			// spends the budget on the same exit twice
			if (!tried.add(route.key)) {
				break
			}
			val client = PooledClients.clientFor(category, route) ?: break
			attempts++
			val inner = client.newCall(request)
			PoolCallCancellation.track(outer, inner)
			try {
				val response = inner.execute()
				if (isOriginAnswer(response.code)) {
					return response
				}
				if (CloudflareChallenge.isChallenge(response)) {
					// the chain worked, the exit IP was refused: close it, count it
					// and try the next route instead of handing back a wall page
					response.close()
					ProxyPoolState.reportChallenge(host)
					challenges++
					continue
				}
				ProxyPoolState.reportSuccess(host, route)
				if (ProxyPoolState.isLearnedHost(host)) {
					Log.i(TAG, "host=$host outcome=success via ${route.key}")
				}
				return response
			} catch (e: TooManyRequestExceptions) {
				// 429 from the origin: an answer, not a transport failure, so it
				// must not rotate the proxy (it is an IOException subclass, hence
				// the dedicated catch in front of the generic one)
				throw e
			} catch (e: IOException) {
				if (outer.isCanceled()) {
					throw e
				}
				val cls = ProxyPoolState.classifyFailure(e)
				ProxyPoolState.reportRouteFailure(host, route, cls)
				lastError = e
				Log.i(
					TAG,
					"host=$host via ${route.key} failed ($cls), attempt $attempts/$MAX_POOL_ATTEMPTS",
				)
			} finally {
				PoolCallCancellation.untrack(outer)
			}
		}
		throw lastError ?: if (challenges > 0) {
			IOException("Cloudflare challenge through $challenges pool route(s)")
		} else {
			IOException("proxy pool has no usable route")
		}
	}

	private fun isOriginAnswer(code: Int): Boolean = code == 429 || code == 503

	private companion object {
		private const val TAG = "ProxyPool"

		/** Pool attempts per request; the rest of the healthy set is left alone. */
		private const val MAX_POOL_ATTEMPTS = 3
	}
}

/**
 * Cancels the inner pooled call when the outer call is cancelled.
 *
 * There is no callback for "this call was cancelled" on [Call], and an
 * interceptor blocking in execute() cannot poll, so one daemon thread sweeps the
 * in-flight pairs. The sweep is a no-op while nothing is in flight, and the
 * object is only ever touched when the pool is installed.
 */
internal object PoolCallCancellation {

	private const val SWEEP_INTERVAL_MS = 200L

	private val active = ConcurrentHashMap<Call, Call>()

	private val sweeper = Executors.newSingleThreadScheduledExecutor { runnable ->
		Thread(runnable, "pool-call-cancel").apply { isDaemon = true }
	}

	init {
		sweeper.scheduleWithFixedDelay(
			{
				try {
					sweep()
				} catch (ignored: Exception) {
					// a throwing sweep must not kill the schedule
				}
			},
			SWEEP_INTERVAL_MS,
			SWEEP_INTERVAL_MS,
			TimeUnit.MILLISECONDS,
		)
	}

	fun track(outer: Call, inner: Call) {
		active[outer] = inner
	}

	fun untrack(outer: Call) {
		active.remove(outer)
	}

	private fun sweep() {
		if (active.isEmpty()) {
			return
		}
		for (entry in active.entries) {
			if (entry.key.isCanceled()) {
				entry.value.cancel()
				active.remove(entry.key)
			}
		}
	}
}
