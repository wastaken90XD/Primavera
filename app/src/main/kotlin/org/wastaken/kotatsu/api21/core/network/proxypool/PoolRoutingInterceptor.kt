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
 * Proxy pool (Task C) - commit 6/7: THE per-tier routing interceptor.
 *
 * One instance is installed PER TIER by NetworkModule (base = app
 * services, manga = sources & images, video = video), outermost in each
 * derived tier's chain, and only when poolMode != OFF at client build -
 * the same apply-at-build precedent as the SSL bypass. The inert check
 * runs INSIDE as belt-and-braces:
 *  - mode/scheme/tier gate first: tier VIDEO passes through
 *    unconditionally (the Task-B "pool never plays media" guarantee,
 *    enforced in-engine so the spec's per-tier install is safe), pool
 *    snapshot OFF (amendment 3), inert (static proxy under
 *    FALLBACK/ALWAYS - CHAINED is the exception, its gateway IS the
 *    static proxy - or SSL bypass), non-HTTPS -> plain proceed, exactly
 *    as if the pool were not installed (amendment 1);
 *  - ProxyPoolController.planFor() yields the ordered routes; the plan is
 *    [Direct] alone whenever the host is excluded (wsrv.nl/worker, manual
 *    list, login cookies) or the pool has nothing to offer;
 *  - RETRY RULE (6/7 spec): GET/HEAD without a body may walk the plan
 *    (Range requests are GETs and therefore retry-safe); POST and other
 *    non-idempotent methods take ONLY the first planned route - never a
 *    retry, never a second route;
 *  - a ViaProxy attempt executes on the pooled client for that route, a
 *    ViaChain attempt on the pooled relay client carrying the relay
 *    secret + chain token (one client per distinct route, LRU 32, derived
 *    from the CALLING TIER's client so tier headers ride pooled requests);
 *  - CANCEL RULE (6/7 spec): while a pooled attempt runs, a watchdog
 *    owned by the controller scope polls the OUTER call's isCanceled()
 *    every WATCHDOG_STEP_MS and cancels the inner call the moment the
 *    outer one dies - ExoPlayer cancels reads on every seek, so the
 *    inner pooled call must die at the same instant (checked before each
 *    attempt too; the job is bounded by the attempt's lifetime, this is
 *    not a 4.4 timer);
 *  - 429/503 are returned untouched from any route: they NEVER rotate
 *    the proxy (origin answers, not transport failures);
 *  - LIMITS (6/7 spec): at most MAX_PROXIES_PER_REQUEST pooled attempts
 *    per request; MAX_CHALLENGES_PER_REQUEST challenge answers counted
 *    per request (more challenges just skip the remaining pool routes -
 *    the plan's Direct tail still runs); AUTO COOKIE rule: after the
 *    first failed/challenged attempt, every subsequent pooled retry is
 *    re-sent WITHOUT the Cookie header (anonymous, one transformation),
 *    the direct path is never altered;
 *  - every outcome lands in the controller (event-driven learning).
 *
 * Revertability: this file + its NetworkModule install calls remove the
 * entire request-path integration; the prepared client stack stays valid
 * (every tier keeps proxyProvider.selector regardless).
 */
class PoolRoutingInterceptor(
	private val settings: AppSettings,
	private val tier: ProxyPoolController.PoolTier,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val mode = ProxyPoolController.effectiveMode()
		if (mode == PoolMode.OFF) {
			return chain.proceed(request)
		}
		// amendment 5 item 5: the category switches replace the hard
		// exclusions - every tier participates unless its switch says no
		val categoryOn = when (tier) {
			ProxyPoolController.PoolTier.BASE -> settings.poolCatServices
			ProxyPoolController.PoolTier.MANGA -> settings.poolCatSources
			ProxyPoolController.PoolTier.VIDEO -> settings.poolCatVideo
		}
		if (!categoryOn) {
			return chain.proceed(request)
		}
		// plain HTTP rides the pool only when the dedicated switch is on
		if (request.url.scheme != "https" && !settings.poolPlainHttp) {
			return chain.proceed(request)
		}
		val host = request.url.host.lowercase()
		val plan = ProxyPoolController.planFor(host, mode)
		if (plan.size <= 1) {
			return chain.proceed(request) // Direct only (or single planned route = Direct)
		}
		val retryable = (request.method == "GET" || request.method == "HEAD") && request.body == null
		val attempts = if (retryable) plan else plan.take(1)
		var lastError: IOException? = null
		var proxiedAttempts = 0 // limit: max proxies per request
		var challenges = 0 // limit: challenge counting
		var retried = false // auto-cookie rule state (retry-without-cookies)
		for (route in attempts) {
			if (route != ProxyPoolController.RoutePlan.Direct &&
				(proxiedAttempts >= MAX_PROXIES_PER_REQUEST || challenges >= MAX_CHALLENGES_PER_REQUEST)
			) {
				continue // pool-route/challenge budget spent; the plan's Direct tail still runs
			}
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
						retried = true
						if (!retryable) {
							throw e // single planned attempt is all a POST gets
						}
					}
				}

				is ProxyPoolController.RoutePlan.ViaProxy,
				is ProxyPoolController.RoutePlan.ViaChain,
				-> {
					proxiedAttempts++
					// amendment 5: "Cookies on proxied requests" wheel -
					// STRIP always strips on pooled attempts (the request
					// COPY only - the direct path's request object is never
					// touched); SEND never strips; AUTO keeps the 10a rule:
					// once any attempt of this request has failed or been
					// challenged, pooled retries go anonymous, exactly once
					val stripCookie = request.header("Cookie") != null &&
						(settings.poolCookiesMode == PoolCookiesMode.STRIP ||
							(settings.poolCookiesMode == PoolCookiesMode.AUTO && retried))
					val outbound = if (stripCookie) {
						request.newBuilder().removeHeader("Cookie").build()
					} else {
						request
					}
					try {
						val response = executeViaPooled(chain, route, outbound)
						if (response.code == 429 || response.code == 503) {
							// origin answered through the proxy: keep the
							// answer, never rotate (spec 5.6)
							return response
						}
						if (PoolChallengeDetector.isChallenge(response)) {
							// challenge page through the pool (header-first,
							// capped-peek helper fallback; response never
							// consumed): challenged exit rotates the route
							response.close()
							ProxyPoolController.reportChallenge(host, route)
							challenges++
							retried = true
							continue // the loop-top budget check skips the rest once spent
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
						retried = true
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
	 *  with the outer-call cancel watchdog: the inner call is canceled the
	 *  moment the outer one dies (ExoPlayer seeks cancel reads - the pooled
	 *  read must not survive its owner). */
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

	/** The client the running call belongs to, so the pooled clone keeps the
	 *  tier's headers/limits. RealCall's `client` is a public val on the
	 *  okhttp internal; the base client is the documented fallback. */
	private fun tierClientOf(chain: Interceptor.Chain): OkHttpClient {
		val call = chain.call() as? RealCall
		return call?.client ?: ProxyPoolController.baseClientOrNull()
			?: throw IllegalStateException("proxy pool: no tier client available")
	}

	/** Sentinel: the relay was gone when a planned chain attempt ran. Not a
	 *  route failure - the interceptor skips the route without reporting. */
	private class RelayUnavailable : IOException("proxy pool: relay not running for chain route")

	private companion object {
		private const val WATCHDOG_STEP_MS = 200L

		/** 6/7 spec limits: at most this many pooled attempts per request,
		 *  regardless of how long the planned route list is. */
		private const val MAX_PROXIES_PER_REQUEST = 5

		/** 6/7 spec limits: after this many challenge answers on one
		 *  request, remaining pool routes are skipped (Direct still runs). */
		private const val MAX_CHALLENGES_PER_REQUEST = 2
	}
}
