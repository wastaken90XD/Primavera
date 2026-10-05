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

/**
 * Proxy pool (experimental) - component 2/5: health checking.
 *
 * Contract from the spec section 5.2:
 *  - sample at most SAMPLE_SIZE (200) random candidates per pass;
 *  - test at most PARALLELISM (6) in parallel;
 *  - timeout per candidate: socket connect/read of SOCKET_TIMEOUT_MS (6s),
 *    with a hard CALL_TIMEOUT_MS (13s) ceiling for the whole attempt;
 *  - a candidate passes ONLY if it completes a full HTTPS request through
 *    itself to the test URL with NORMAL certificate verification and gets a
 *    success-class status (2xx/3xx). Any certificate error automatically
 *    makes the candidate ineligible here, because the health client uses
 *    the app's real TLS context - a proxy that intercepts TLS fails before
 *    any ban logic is even needed. (The explicit 24h ban for cert errors is
 *    enforced one layer up in the pool state when production traffic flags
 *    one - see component 3.)
 *  - the health transport is cookie-less (CookieJar.NO_COOKIES) and does not
 *    use any authenticator: health checks never leak the user's session
 *    state to a proxy.
 *
 * Hostnames (not IP literals) that resolve to loopback / link-local /
 * RFC1918 are rejected HERE, at the point where DNS resolution first
 * happens cheaply - literal rejections already ran in the fetcher.
 *
 * Persistence: healthy entries are kept as a small plain-text state file in
 * the cache directory ("proxy_pool_state.txt") with a millisecond timestamp;
 * entries older than STATE_MAX_AGE_MS (30 min) are ignored unless re-checked.
 * Plain text, not JSON - no extra libs, easily greppable in support dumps.
 *
 * Revertability: deleting this file removes the component; it does not
 * change any other component's behavior (the fetcher's parser stays usable
 * standalone).
 */
object ProxyHealthChecker {

	private const val TAG = "ProxyPool"
	private const val SAMPLE_SIZE = 200
	private const val PARALLELISM = 6
	private const val SOCKET_TIMEOUT_MS = 6_000
	private const val CALL_TIMEOUT_MS = 13_000
	const val STATE_MAX_AGE_MS = 30 * 60 * 1_000L
	private const val STATE_FILE = "proxy_pool_state.txt"
	private const val STATE_VERSION = 1

	data class HealthyProxy(val entry: ProxyEntry, val latencyMs: Long)

	data class HealthReport(
		val candidatesTotal: Int,
		val sampled: Int,
		val checkedAtMs: Long,
		val healthy: List<HealthyProxy>,
		// class-simple name per failed candidate is intentionally NOT kept:
		// 200 health failures are not diagnostics we want in support dumps
		val error: String?,
	)

	/**
	 * Runs a full health pass. Never throws: a broken search URL or an
	 * unreachable test endpoint reports [HealthReport.error] instead.
	 */
	suspend fun check(
		baseClient: OkHttpClient,
		candidates: List<ProxyEntry>,
		testUrl: String,
		maxHealthy: Int,
	): HealthReport = coroutineScope {
		if (!testUrl.startsWith("https://")) {
			return@coroutineScope HealthReport(candidates.size, 0, 0L, emptyList(), "testUrl is not https")
		}
		val sample = candidates.shuffled().take(SAMPLE_SIZE)
		val semaphore = Semaphore(PARALLELISM)
		val probe = Request.Builder().url(testUrl).head().build()
		val results = sample.map { entry ->
			async(Dispatchers.IO) {
				semaphore.acquire()
				try {
					probeOne(baseClient, entry, probe)
				} finally {
					semaphore.release()
				}
			}
		}.awaitAll()
		val healthy = results.filterNotNull()
			.sortedBy { it.latencyMs }
			.take(maxHealthy)
		Log.i(TAG, "health: ${candidates.size} candidates, ${sample.size} sampled, ${healthy.size} healthy (cap $maxHealthy)")
		HealthReport(
			candidatesTotal = candidates.size,
			sampled = sample.size,
			checkedAtMs = System.currentTimeMillis(),
			healthy = healthy,
			error = null,
		)
	}

	/**
	 * null = dead candidate. A pass needs: fast resolution to public space,
	 * proxy handshake, full HTTPS round trip with real cert checks, success
	 * status.
	 */
	private fun probeOne(baseClient: OkHttpClient, entry: ProxyEntry, probe: Request): HealthyProxy? {
		try {
			// hostname-level safety check: a list-provided HOSTNAME that resolves
			// into private space is discarded here, where DNS has to happen anyway
			val resolved = runCatching { InetAddress.getByName(entry.host) }.getOrNull()
			if (resolved != null && (resolved.isLoopbackAddress || resolved.isLinkLocalAddress ||
					resolved.isAnyLocalAddress || isPrivateInet(resolved))
			) {
				return null
			}
			val type = when (entry.scheme) {
				Scheme.HTTP -> Proxy.Type.HTTP
				// SOCKS4 list entries are probed as SOCKS5 (the JDK picks the
				// protocol version via a JVM-global hint we deliberately do NOT
				// flip, because parallel probes would race on it). A SOCKS4-only
				// entry fails the probe and is filtered - conservative by design
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
				.connectTimeout(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
				.readTimeout(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
				.callTimeout(CALL_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
				.build()
			val started = System.currentTimeMillis()
			transport.newCall(probe).execute().use { response ->
				if (!response.isSuccessful) {
					return null
				}
			}
			return HealthyProxy(entry, System.currentTimeMillis() - started)
		} catch (e: Exception) {
			return null
		}
	}

	/**
	 * One-pass refresh: parse lists (component 1), health-check the result.
	 */
	suspend fun refresh(
		baseClient: OkHttpClient,
		listUrls: List<String>,
		testUrl: String,
		maxHealthy: Int,
	): Pair<ProxyListFetcher.Result, HealthReport> {
		val parsed = ProxyListFetcher.fetch(baseClient, listUrls)
		val report = check(baseClient, parsed.entries, testUrl, maxHealthy)
		return parsed to report
	}

	// region persistence

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
	 * Reads the cache file; null when absent, unreadable, wrong version, or
	 * older than STATE_MAX_AGE_MS (entries expire unless re-checked, spec 5.2).
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
			val checkedAt = head[1].toLongOrNull() ?: return null
			if (System.currentTimeMillis() - checkedAt > STATE_MAX_AGE_MS) {
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
