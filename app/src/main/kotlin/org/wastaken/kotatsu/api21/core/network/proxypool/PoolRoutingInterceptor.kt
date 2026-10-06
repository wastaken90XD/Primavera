package org.wastaken.kotatsu.api21.core.network.proxypool

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.connection.RealCall
import org.wastaken.kotatsu.api21.core.exceptions.CloudFlareException
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import java.io.IOException

/**
 * Proxy pool (Task C) - commit 3/7: the ONE routing interceptor
 * (amendments 1+4+7). Installed on the base client as the FIRST
 * application interceptor; the video tier removes it (the pool is never
 * used for media playback, the Task-B guarantee kept).
 *
 * Per request:
 *  - mode/scheme-gate first: pool snapshots OFF (amendment 3), inert
 *    (static proxy under FALLBACK/ALWAYS - CHAINED is the exception, its
 *    gateway IS the static proxy - or SSL bypass), non-HTTPS -> plain
 *    proceed, exactly as if the pool were not installed (amendment 1);
 *  - ProxyPoolController.planFor() yields the ordered routes; the plan is
 *    [Direct] alone whenever the host is excluded (wsrv.nl/worker, manual
 *    list, login cookies) or the pool has nothing to offer;
 *  - GET/HEAD without a body may walk the whole plan (Range requests are
 *    GETs and therefore retry-safe, amendment 7); POST and other
 *    non-idempotent methods take ONLY the first planned route - never a
 *    retry, never a second route;
 *  - a ViaProxy attempt executes on the pooled client for that route, a
 *    ViaChain attempt (5/7) on the pooled relay client carrying the relay
 *    secret + chain token (one client per distinct route, LRU 32, derived
 *    from the CALLING TIER's client - the tier is read from the call
 *    itself so manga-tier headers ride pooled requests too);
 *  - while a pooled attempt runs, a controller-owned watchdog polls the
 *    OUTER call's isCanceled() every WATCHDOG_STEP_MS and cancels the
 *    inner call the moment the outer one dies (amendment 7: ExoPlayer
 *    seeks cancel reads; this is per-attempt plumbing, event-bounded by
 *    the attempt's lifetime - not a 4.4 timer);
 *  - 429/503 are returned untouched from any route: they NEVER rotate the
 *    proxy (spec 5.6: origin answers, not transport failures);
 *  - every outcome lands in the controller (event-driven learning).
 *
 * Revertability: deleting this file + its NetworkModule line removes the
 * entire request-path integration; the prepared client stack stays valid
 * (every tier keeps proxyProvider.selector regardless).
 */
class PoolRoutingInterceptor(
	@Suppress("unused") private val settings: AppSettings,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val mode = ProxyPoolController.effectiveMode()
		if (mode == PoolMode.OFF || ProxyPoolController.isInert()) {
			return chain.proceed(request)
		}
		if (request.url.scheme != "https") {
			return chain.proceed(request) // pool carries HTTPS only
		}
		val host = request.url.host.lowercase()
		val plan = ProxyPoolController.planFor(host, mode)
		if (plan.size <= 1) {
			return chain.proceed(request) // Direct only (or single planned route = Direct)
		}
		val retryable = (request.method == "GET" || request.method == "HEAD") && request.body == null
		val attempts = if (retryable) plan else plan.take(1)
		var lastError: IOException? = null
		for (route in attempts) {
			when (route) {
				ProxyPoolController.RoutePlan.Direct -> {
					try {
						val response = chain.proceed(request)
						ProxyPoolController.reportOutcome(host, route, null)
						return response
					} catch (e: IOException) {
						if (e is CloudFlareException) {
							// the tier chain's CloudFlareInterceptor escalated
							// a challenge; the solver path owns this, never a
							// route failure (CloudFlareException IS an
							// IOException, so this guard must run first)
							throw e
						}
						ProxyPoolController.reportOutcome(host, route, e)
						lastError = e
						if (!retryable) {
							throw e // single planned attempt is all a POST gets
						}
					}
				}

				is ProxyPoolController.RoutePlan.ViaProxy,
				is ProxyPoolController.RoutePlan.ViaChain,
				-> {
					try {
						val response = executeViaPooled(chain, route, request)
						if (response.code == 429 || response.code == 503) {
							// origin answered through the proxy: keep the
							// answer, never rotate (spec 5.6)
							return response
						}
						if (PoolChallengeDetector.isChallenge(response)) {
							// 4/7: a challenge page arrived through the pool
							// (header-first, capped-peek helper fallback; the
							// response was never consumed). A challenged
							// exit rotates to the next route...
							response.close()
							ProxyPoolController.reportChallenge(host, route)
							continue
						}
						ProxyPoolController.reportOutcome(host, route, null)
						return response
					} catch (e: RelayUnavailable) {
						// relay died between planning and execution: skip the
						// chain route WITHOUT a ledger mark - the proxy is
						// not at fault for our own lifecycle
						continue
					} catch (e: IOException) {
						ProxyPoolController.reportOutcome(host, route, e)
						lastError = e
						if (!retryable) {
							throw e
						}
						// next route
					}
				}
			}
		}
		throw lastError ?: IOException("proxy pool: all planned routes failed for $host")
	}

	/** Executes one pooled attempt (direct-pool route OR relay chain route)
	 *  with the outer-call cancel watchdog. */
	private fun executeViaPooled(
		chain: Interceptor.Chain,
		route: ProxyPoolController.RoutePlan,
		request: Request,
	): Response {
		if (chain.call().isCanceled()) {
			throw IOException("Canceled")
		}
		val tier = tierClientOf(chain)
		val pooled: OkHttpClient = when (route) {
			is ProxyPoolController.RoutePlan.ViaProxy ->
				ProxyPoolController.pooled(tier, route.proxy.entry)

			is ProxyPoolController.RoutePlan.ViaChain ->
				ProxyPoolController.pooledChain(tier, route.token) ?: throw RelayUnavailable()

			ProxyPoolController.RoutePlan.Direct ->
				throw IllegalArgumentException("executeViaPooled called with Direct")
		}
		val inner = pooled.newCall(request)
		val outer = chain.call()
		val watchdog = ProxyPoolController.scope.launch {
			while (true) {
				delay(WATCHDOG_STEP_MS)
				if (outer.isCanceled()) {
					inner.cancel()
					break
				}
			}
		}
		try {
			return inner.execute()
		} finally {
			watchdog.cancel()
		}
	}

	/** Sentinel: the relay was gone when a planned chain attempt ran. Not a
	 *  route failure - the interceptor skips the route without reporting. */
	private class RelayUnavailable : IOException("proxy pool: relay not running for chain route")

	/** The client the running call belongs to, so the pooled clone keeps the
	 *  tier's headers/limits. RealCall's `client` is a public val on the
	 *  okhttp internal; the base client is the documented fallback. */
	private fun tierClientOf(chain: Interceptor.Chain): OkHttpClient {
		val call = chain.call() as? RealCall
		return call?.client ?: ProxyPoolController.baseClientOrNull()
			?: throw IllegalStateException("proxy pool: no tier client available")
	}

	private companion object {
		private const val WATCHDOG_STEP_MS = 200L
	}
}
