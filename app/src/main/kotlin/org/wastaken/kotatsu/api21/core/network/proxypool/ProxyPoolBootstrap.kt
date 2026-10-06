package org.wastaken.kotatsu.api21.core.network.proxypool

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import org.wastaken.kotatsu.api21.core.network.proxy.ProxyType
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyListFetcher.ProxyEntry
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyListFetcher.Scheme
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug

/**
 * The bootstrap cycle: how the pool gets from "nothing" to "a working chain",
 * without a single timer.
 *
 * Gateway choice, in order:
 *  1. the user's static proxy, if one is configured - and only if it passes the
 *     same health check as a list candidate (a proxy the user typed in is not
 *     automatically trusted to be a gateway);
 *  2. the user's bootstrap proxy list;
 *  3. the healthiest proxies found in the public lists.
 *
 * Then, with a gateway in hand:
 *  - the lists are fetched a SECOND time through the gateway, and that copy
 *    REPLACES the on-disk state. A censor that serves this device a different
 *    list than it serves a foreign exit is exactly the case the pool exists for;
 *  - every candidate is health-checked as the main proxy of a chain through that
 *    gateway (see ProxyHealthChecker.probeChain), so "healthy" means the whole
 *    chain works, not that the proxy answers when dialed directly;
 *  - the best chain is published to the relay.
 *
 * Without a gateway the cycle falls back to single-proxy routing and KEEPS that
 * state indefinitely: there is no timeout, no expiry and no retry clock. It is
 * replaced the moment a later cycle (user refresh, or the failure latch) finds a
 * gateway. That is deliberate - a pool that quietly gives up after N minutes is
 * indistinguishable from a pool that never worked.
 *
 * The relay's life cycle lives here too: it is started when chain mode is on and
 * stopped as soon as it is not, so a disabled chain leaves no listener behind.
 */
object ProxyPoolBootstrap {

	private const val TAG = "ProxyPool"

	/**
	 * One full cycle. Never throws: a missing gateway or a failed list fetch is
	 * reported in the returned summary line instead.
	 */
	suspend fun runCycle(baseClient: OkHttpClient, context: Context, settings: AppSettings): String {
		val gateway = findGateway(baseClient, settings)
		if (gateway == null) {
			ProxyPoolState.publishGateway(null)
			val summary = refreshSingles(baseClient, context, settings)
			Log.i(TAG, "bootstrap: no gateway available; single-proxy routing ($summary)")
			return "no gateway; $summary"
		}
		ProxyPoolState.publishGateway(gateway)
		val relay = ensureRelay(settings)
		return try {
			val parsed = ProxyListFetcher.fetch(baseClient, settings.poolLists, gateway)
			val report = ProxyHealthChecker.check(
				baseClient = baseClient,
				candidates = parsed.entries,
				testUrl = settings.poolTestUrl,
				maxHealthy = settings.poolMaxHealthy,
				gateway = gateway,
				timeoutMs = settings.poolChainTimeoutSeconds * 1000,
			)
			if (report.error == null) {
				// the gateway-fetched list replaces the on-disk copy
				ProxyHealthChecker.saveToCache(context.cacheDir, report)
				ProxyPoolState.publishHealth(report)
			}
			val best = report.healthy.firstOrNull()
			if (best == null) {
				Log.w(TAG, "bootstrap: gateway ${gateway.host} is up but no chain through it passed")
				"gateway ${gateway.host}, no healthy chain yet"
			} else {
				relay.route = ChainRoute(gateway, best.entry.toEndpoint())
				Log.i(
					TAG,
					"bootstrap: ${report.healthy.size} healthy chains via gateway=${gateway.host} " +
						"main=${best.entry.host}",
				)
				"${report.healthy.size} healthy chains via ${gateway.host}"
			}
		} catch (e: Exception) {
			e.printStackTraceDebug()
			"chain bootstrap failed: ${e.javaClass.simpleName}"
		}
	}

	/** Single-proxy pass, used when there is no gateway. */
	suspend fun refreshSingles(baseClient: OkHttpClient, context: Context, settings: AppSettings): String {
		val (parsed, report) = ProxyHealthChecker.refresh(
			baseClient = baseClient,
			listUrls = settings.poolLists,
			testUrl = settings.poolTestUrl,
			maxHealthy = settings.poolMaxHealthy,
			gateway = null,
			timeoutMs = settings.poolDirectTimeoutSeconds * 1000,
			callTimeoutMs = ProxyHealthChecker.DEFAULT_CALL_TIMEOUT_MS,
		)
		if (report.error == null) {
			ProxyHealthChecker.saveToCache(context.cacheDir, report)
			ProxyPoolState.publishHealth(report)
		}
		val listsOk = parsed.reports.count { it.error == null }
		return "${report.healthy.size} healthy of ${report.sampled} sampled " +
			"(${report.candidatesTotal} entries, $listsOk/${parsed.reports.size} lists OK)"
	}

	/**
	 * The gateway must pass the same health check as any candidate: a static proxy
	 * the user typed in is a preference, not a proof.
	 */
	private suspend fun findGateway(baseClient: OkHttpClient, settings: AppSettings): ProxyEndpoint? {
		val candidates = ArrayList<ProxyEndpoint>()
		staticGateway(settings)?.let { candidates += it }
		candidates += bootstrapProxies(settings)
		for (healthy in ProxyPoolState.healthy) {
			candidates += healthy.entry.toEndpoint()
		}
		for (candidate in candidates.distinctBy { it.key }) {
			if (passesHealthCheck(baseClient, settings, candidate)) {
				return candidate
			}
		}
		return null
	}

	private suspend fun passesHealthCheck(
		baseClient: OkHttpClient,
		settings: AppSettings,
		endpoint: ProxyEndpoint,
	): Boolean {
		val report = ProxyHealthChecker.check(
			baseClient = baseClient,
			candidates = listOf(endpoint.toEntry()),
			testUrl = settings.poolTestUrl,
			maxHealthy = 1,
			gateway = null,
			timeoutMs = settings.poolDirectTimeoutSeconds * 1000,
		)
		return report.healthy.isNotEmpty()
	}

	private fun staticGateway(settings: AppSettings): ProxyEndpoint? {
		val type = settings.proxyType
		if (type == ProxyType.DIRECT || type == ProxyType.MTPROTO) {
			return null
		}
		val address = settings.proxyAddress?.trim()
		if (address.isNullOrEmpty()) {
			return null
		}
		val port = settings.proxyPort
		if (port !in 1..0xFFFF) {
			return null
		}
		val scheme = when (type) {
			ProxyType.SOCKS4 -> Scheme.SOCKS4
			ProxyType.SOCKS5 -> Scheme.SOCKS5
			else -> Scheme.HTTP
		}
		return ProxyEndpoint(scheme, address, port)
	}

	/** User-provided gateway candidates, parsed by the same parser as the lists. */
	private fun bootstrapProxies(settings: AppSettings): List<ProxyEndpoint> {
		return settings.poolBootstrapProxies.mapNotNull { line ->
			ProxyListFetcher.parseLine(line, Scheme.HTTP)?.toEndpoint()
		}
	}

	/** Starts the relay if it is not running yet; keeps an existing one and its port. */
	private fun ensureRelay(settings: AppSettings): ProxyChainRelay {
		ProxyPoolState.relay()?.let { existing ->
			if (existing.isRunning) {
				return existing
			}
		}
		val timeoutMs = settings.poolRelayTimeoutSeconds * 1000
		val relay = ProxyChainRelay(connectTimeoutMs = timeoutMs, requestTimeoutMs = timeoutMs)
		val port = relay.start()
		ProxyPoolState.attachRelay(relay)
		Log.i(TAG, "relay listening on 127.0.0.1:$port (loopback only, CONNECT only, secret per run)")
		return relay
	}
}

/** Back to the parser's entry type, for the health checker. */
fun ProxyEndpoint.toEntry(): ProxyEntry = ProxyEntry(scheme, host, port)
