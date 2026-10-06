package org.wastaken.kotatsu.api21.core.network.proxypool

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyListFetcher.ProxyEntry
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyListFetcher.Scheme
import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proxy pool (Task C) - commit 2/7 rework: health checking and host
 * verification (spec 4.6.1/4.6.2/4.6.4), event driven (4.4).
 *
 *  - Direct candidate checks sample at most DIRECT_SAMPLE_CAP (150) random
 *    candidates per pass, at most PARALLELISM (6) probes in parallel; the
 *    per-candidate timeout is USER-SET (settings, default 5 s) with a fixed
 *    formula call ceiling (2*timeout + 1 s) for the whole attempt. No
 *    schedule decides WHEN a check runs - the controller triggers passes on
 *    user action, bootstrap, and exhaustion signals only (4.4).
 *  - A candidate passes ONLY if it completes a full HTTPS request through
 *    itself to the probe URL with NORMAL certificate verification and a
 *    success-class status. Any certificate error makes the candidate
 *    ineligible here, because the health client uses the app's real TLS
 *    context (the explicit 24h ban for cert errors seen on production
 *    traffic is enforced one layer up - unchanged rule).
 *  - Chain probes run through the relay as ISOLATED token-bound chains
 *    (amendment-4 groundwork): each probe binds its own token to
 *    (gateway, candidate-main), so six parallel probes never share a
 *    binding, and the hop-level failure classes (amendment 9) appear in the
 *    ProxyPool log straight from the relay.
 *  - Host verification (4.6.4): GET https://host (Range-free, the page head
 *    is what challenge pages answer), a candidate passes iff status is
 *    200..299 AND the response is not a Cloudflare challenge
 *    (PoolChallengeDetector, amendment 6). Sample cap 60 per host.
 *  - The health transport is cookie-less (CookieJar.NO_COOKIES) and uses no
 *    outside authenticator: health probes never leak the user's session
 *    state to a proxy.
 *
 * Persistence: healthy entries are kept as a small plain-text state file in
 * the cache directory. The state is TRUSTED UNTIL A SUCCESSFUL PASS
 * REPLACES IT (event driven 4.4): no age gate anywhere. The STATE_MAX_AGE_MS
 * constant survives only to keep the legacy controller compiling; it is
 * removed together with the controller in commit 3/7.
 *
 * Revertability: deleting this file (with its two controller call sites)
 * removes the component; no other component changes behavior.
 */
object ProxyHealthChecker {

	private const val TAG = "ProxyPool"
	private const val PARALLELISM = 6
	private const val DIRECT_SAMPLE_CAP = 150
	private const val CHAIN_SAMPLE_CAP = 120
	private const val HOST_VERIFY_CAP = 60
	private const val MAX_PER_HOST_KEEP = 5
	private const val STATE_FILE = "proxy_pool_state.txt"
	private const val STATE_VERSION = 1

	/** Generic-liveness pass: 2xx/3xx (class doc rule). */
	private val GENERIC_SUCCESS_RANGE = 200..399

	/** Host-verify pass: strictly 200..299 per spec 4.6.4. */
	private val VERIFY_SUCCESS_RANGE = 200..299

	const val DEFAULT_TIMEOUT_S = 5
	const val DEFAULT_CHAIN_TIMEOUT_S = 8

	/** Legacy-only: the legacy controller still gates its LRU refresh on age;
	 *  removed with the controller in commit 3/7. */
	@Deprecated("event-driven rework: state is trusted until replaced")
	const val STATE_MAX_AGE_MS = 30 * 60 * 1_000L

	data class HealthyProxy(val entry: ProxyEntry, val latencyMs: Long)

	data class HealthReport(
		val candidatesTotal: Int,
		val sampled: Int,
		val checkedAtMs: Long,
		val healthy: List<HealthyProxy>,
		// class-simple name per failed candidate is intentionally NOT kept:
		// a mass of probe failures is not diagnostics we want in support dumps
		val error: String?,
	)

	// region public passes

	/**
	 * Direct candidate pass over the generic test URL. Never throws: a broken
	 * probe URL or an unreachable endpoint reports [HealthReport.error].
	 */
	suspend fun check(
		baseClient: OkHttpClient,
		candidates: List<ProxyEntry>,
		testUrl: String,
		maxHealthy: Int,
		timeoutS: Int = DEFAULT_TIMEOUT_S,
	): HealthReport = coroutineScope {
		if (!testUrl.startsWith("https://")) {
			return@coroutineScope HealthReport(candidates.size, 0, 0L, emptyList(), "testUrl is not https")
		}
		val sample = candidates.shuffled().take(DIRECT_SAMPLE_CAP)
		val probe = Request.Builder().url(testUrl).head().build()
		val healthy = probeAll(sample) { entry ->
			probeDirect(baseClient, entry, probe, timeoutS, GENERIC_SUCCESS_RANGE)
		}
		Log.i(
			TAG,
			"health: ${candidates.size} candidates, ${sample.size} sampled, ${healthy.size} healthy (cap $maxHealthy)",
		)
		HealthReport(candidates.size, sample.size, System.currentTimeMillis(), healthy.take(maxHealthy), null)
	}

	/**
	 * Direct candidate pass against one specific host (4.6.4 first choice
	 * when a host first needs the pool). Pass = 2xx + not a challenge.
	 */
	suspend fun verifyForHostDirect(
		baseClient: OkHttpClient,
		candidates: List<ProxyEntry>,
		host: String,
		timeoutS: Int = DEFAULT_TIMEOUT_S,
	): HealthReport = coroutineScope {
		val sample = candidates.shuffled().take(HOST_VERIFY_CAP)
		val probe = Request.Builder().url("https://$host/").get().build()
		val healthy = probeAll(sample) { entry ->
			// spec 4.6.4: a host-verify candidate passes ONLY on 200..299
			// (unlike the generic liveness pass, which accepts 2xx/3xx)
			probeDirect(baseClient, entry, probe, timeoutS, VERIFY_SUCCESS_RANGE)
		}
		Log.i(TAG, "verify host=$host: ${sample.size} sampled, ${healthy.size} passed (keep $MAX_PER_HOST_KEEP)")
		HealthReport(candidates.size, sample.size, System.currentTimeMillis(), healthy.take(MAX_PER_HOST_KEEP), null)
	}

	/**
	 * Chain pass over the generic test URL: each candidate becomes the MAIN
	 * hop through [gateway], isolated per token (spec 4.6.2 steps d-e,
	 * "chains are tested through the gateway").
	 */
	suspend fun checkChains(
		relay: ProxyChainRelay,
		baseClient: OkHttpClient,
		gateway: ProxyChainRelay.GatewayBinding,
		candidates: List<ProxyEntry>,
		testUrl: String,
		maxChains: Int,
		timeoutS: Int = DEFAULT_CHAIN_TIMEOUT_S,
	): HealthReport = coroutineScope {
		if (!relay.isRunning) {
			return@coroutineScope HealthReport(candidates.size, 0, 0L, emptyList(), "relay not running")
		}
		if (!testUrl.startsWith("https://")) {
			return@coroutineScope HealthReport(candidates.size, 0, 0L, emptyList(), "testUrl is not https")
		}
		val sample = candidates.shuffled().take(CHAIN_SAMPLE_CAP)
		val probe = Request.Builder().url(testUrl).head().build()
		val healthy = probeAll(sample) { entry ->
			probeChained(relay, baseClient, gateway, entry, probe, timeoutS, GENERIC_SUCCESS_RANGE)
		}
		Log.i(TAG, "chain health: ${sample.size} sampled, ${healthy.size} healthy (cap $maxChains)")
		HealthReport(candidates.size, sample.size, System.currentTimeMillis(), healthy.take(maxChains), null)
	}

	/**
	 * Chain pass against one specific host (4.6.4 second choice: when no
	 * DIRECT candidate passes for that host, test the chains).
	 */
	suspend fun verifyChainsForHost(
		relay: ProxyChainRelay,
		baseClient: OkHttpClient,
		gateway: ProxyChainRelay.GatewayBinding,
		candidates: List<ProxyEntry>,
		host: String,
		timeoutS: Int = DEFAULT_CHAIN_TIMEOUT_S,
	): HealthReport = coroutineScope {
		if (!relay.isRunning) {
			return@coroutineScope HealthReport(candidates.size, 0, 0L, emptyList(), "relay not running")
		}
		val sample = candidates.shuffled().take(HOST_VERIFY_CAP)
		val probe = Request.Builder().url("https://$host/").get().build()
		val healthy = probeAll(sample) { entry ->
			probeChained(relay, baseClient, gateway, entry, probe, timeoutS, VERIFY_SUCCESS_RANGE)
		}
		Log.i(TAG, "verify-chains host=$host: ${sample.size} sampled, ${healthy.size} passed (keep $MAX_PER_HOST_KEEP)")
		HealthReport(candidates.size, sample.size, System.currentTimeMillis(), healthy.take(MAX_PER_HOST_KEEP), null)
	}

	/**
	 * Transition shim for the legacy controller (removed in 3/7): fetch the
	 * lists on the legacy path, then run the direct pass.
	 */
	@Deprecated("legacy refresh path; superseded by the new controller in commit 3/7")
	suspend fun refresh(
		baseClient: OkHttpClient,
		listUrls: List<String>,
		testUrl: String,
		maxHealthy: Int,
	): Pair<ProxyListFetcher.Result, HealthReport> {
		@Suppress("DEPRECATION")
		val parsed = ProxyListFetcher.fetch(baseClient, listUrls)
		val report = check(baseClient, parsed.entries, testUrl, maxHealthy)
		return parsed to report
	}

	// endregion

	// region probes

	private val probeTokenSeq = AtomicInteger(0)

	private suspend fun probeAll(
		sample: List<ProxyEntry>,
		probe: (ProxyEntry) -> HealthyProxy?,
	): List<HealthyProxy> = coroutineScope {
		val semaphore = Semaphore(PARALLELISM)
		sample.map { entry ->
			async(Dispatchers.IO) {
				semaphore.acquire()
				try {
					probe(entry)
				} finally {
					semaphore.release()
				}
			}
		}.awaitAll().filterNotNull().sortedBy { it.latencyMs }
	}

	/**
	 * null = dead candidate. A direct pass needs: fast resolution to public
	 * space, proxy handshake, full HTTPS round trip with real cert checks,
	 * success status (and "not a challenge" on host-verify probes).
	 */
	private fun probeDirect(
		baseClient: OkHttpClient,
		entry: ProxyEntry,
		probe: Request,
		timeoutS: Int,
		successRange: IntRange,
	): HealthyProxy? {
		try {
			// hostname-level safety check: a list-provided HOSTNAME that
			// resolves into private space is discarded here, where DNS has to
			// happen anyway (the fetcher rejects literals only)
			val resolved = runCatching { InetAddress.getByName(entry.host) }.getOrNull()
			if (resolved != null && (resolved.isLoopbackAddress || resolved.isLinkLocalAddress ||
					resolved.isAnyLocalAddress || isPrivateInet(resolved))
			) {
				return null
			}
			val type = when (entry.scheme) {
				Scheme.HTTP -> Proxy.Type.HTTP
				// SOCKS4 list entries are probed as SOCKS5 (the JDK picks the
				// protocol version via a JVM-global hint we deliberately do
				// NOT flip, because parallel probes would race on it). A
				// SOCKS4-only entry fails the probe and is filtered -
				// conservative by design. Chain probes do not have this
				// limitation: the relay speaks SOCKS4a natively.
				Scheme.SOCKS4, Scheme.SOCKS5 -> Proxy.Type.SOCKS
			}
			val proxy = Proxy(type, InetSocketAddress.createUnresolved(entry.host, entry.port))
			val selector = object : ProxySelector() {
				override fun select(uri: URI?): List<Proxy> = listOf(proxy)
				override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
			}
			val transport = baseClient.newBuilder()
				.proxySelector(selector)
				.proxyAuthenticator(Authenticator.NONE)
				.cookieJar(CookieJar.NO_COOKIES)
				.connectTimeout(timeoutS.toLong(), TimeUnit.SECONDS)
				.readTimeout(timeoutS.toLong(), TimeUnit.SECONDS)
				.callTimeout((timeoutS * 2 + 1).toLong(), TimeUnit.SECONDS)
				.build()
			val started = System.currentTimeMillis()
			transport.newCall(probe).execute().use { response ->
				if (response.code !in successRange) {
					return null
				}
				if (probe.method == "GET" && PoolChallengeDetector.isChallenge(response)) {
					return null
				}
			}
			return HealthyProxy(entry, System.currentTimeMillis() - started)
		} catch (e: Exception) {
			return null
		}
	}

	/** Chain probe: the candidate is the MAIN hop through [gateway], bound
	 *  under its own token so parallel probes stay isolated (relay 2/7). */
	private fun probeChained(
		relay: ProxyChainRelay,
		baseClient: OkHttpClient,
		gateway: ProxyChainRelay.GatewayBinding,
		entry: ProxyEntry,
		probe: Request,
		timeoutS: Int,
		successRange: IntRange,
	): HealthyProxy? {
		val token = "probe-${probeTokenSeq.incrementAndGet()}"
		return try {
			relay.bindChainToken(
				token,
				ProxyChainRelay.ChainBundle(
					gateway = gateway,
					main = ProxyChainRelay.Hop(entry.scheme, entry.host, entry.port),
				),
			)
			val relayProxy = Proxy(
				Proxy.Type.HTTP,
				InetSocketAddress.createUnresolved("127.0.0.1", relay.port),
			)
			val selector = object : ProxySelector() {
				override fun select(uri: URI?): List<Proxy> = listOf(relayProxy)
				override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
			}
			val transport = baseClient.newBuilder()
				.proxySelector(selector)
				.proxyAuthenticator { _, response ->
					// the only hook that may set CONNECT headers in OkHttp:
					// relay secret + per-probe chain token (amendment 4/8)
					response.request.newBuilder()
						.header("Proxy-Authorization", relay.buildAuthHeader())
						.header(ProxyChainRelay.HEADER_CHAIN_TOKEN, token)
						.build()
				}
				.cookieJar(CookieJar.NO_COOKIES)
				.connectTimeout(timeoutS.toLong(), TimeUnit.SECONDS)
				.readTimeout(timeoutS.toLong(), TimeUnit.SECONDS)
				.callTimeout((timeoutS * 3 + 1).toLong(), TimeUnit.SECONDS) // chain = 2 extra handshakes
				.build()
			val started = System.currentTimeMillis()
			transport.newCall(probe).execute().use { response ->
				if (response.code !in successRange) {
					return null
				}
				if (probe.method == "GET" && PoolChallengeDetector.isChallenge(response)) {
					return null
				}
			}
			HealthyProxy(entry, System.currentTimeMillis() - started)
		} catch (e: Exception) {
			null
		} finally {
			relay.bindChainToken(token, null)
		}
	}

	// endregion

	// region persistence (trusted until a successful pass replaces it; no age gate)

	suspend fun saveToCache(cacheDir: File, report: HealthReport) {
		try {
			val file = File(cacheDir, STATE_FILE)
			val lines = buildString(report.healthy.size * 40) {
				append(STATE_VERSION).append(' ').append(System.currentTimeMillis()).append('\n')
				for (h in report.healthy) {
					val e = h.entry
					append(e.scheme.ordinal).append(' ').append(e.host).append(' ')
						.append(e.port).append(' ').append(h.latencyMs).append('\n')
				}
			}
			file.writeText(lines)
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	/**
	 * Reads the state file; null when absent, unreadable, or wrong version.
	 * Event-driven rework: stored entries are trusted until a successful
	 * pass replaces them - staleness is handled by RE-probing on the next
	 * controller-triggered pass, never by wall-clock age.
	 */
	fun loadFromCache(cacheDir: File): List<HealthyProxy>? {
		return try {
			val file = File(cacheDir, STATE_FILE)
			if (!file.isFile) {
				return null
			}
			val lines = file.readLines()
			val head = lines.firstOrNull()?.split(' ') ?: return null
			if (head.size < 2 || head[0].toIntOrNull() != STATE_VERSION) {
				return null
			}
			lines.drop(1).mapNotNull { line ->
				val parts = line.split(' ')
				if (parts.size < 4) return@mapNotNull null
				val scheme = parts[0].toIntOrNull()?.let { schemeOrd ->
					Scheme.entries.getOrNull(schemeOrd)
				} ?: return@mapNotNull null
				val port = parts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
				val latency = parts[3].toLongOrNull() ?: Long.MAX_VALUE
				HealthyProxy(ProxyEntry(scheme, parts[1], port), latency)
			}.takeIf { it.isNotEmpty() }
		} catch (e: Exception) {
			e.printStackTraceDebug()
			null
		}
	}

	// endregion

	/** Keep private: duplicated (not shared with the fetcher) so each component
	 *  stays revertable in isolation. */
	private fun isPrivateInet(addr: InetAddress): Boolean {
		val a = addr.address
		if (a.size != 4) {
			return false // v6 literals were already filtered at fetch time
		}
		val o0 = a[0].toInt() and 0xFF
		val o1 = a[1].toInt() and 0xFF
		return o0 == 0 || o0 == 10 ||
			(o0 == 172 && o1 in 16..31) ||
			(o0 == 192 && o1 == 168) ||
			o0 == 127 ||
			(o0 == 169 && o1 == 254)
	}
}
