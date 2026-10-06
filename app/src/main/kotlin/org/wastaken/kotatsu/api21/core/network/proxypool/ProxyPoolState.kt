package org.wastaken.kotatsu.api21.core.network.proxypool

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyHealthChecker.HealthReport
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyHealthChecker.HealthyProxy
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug
import java.lang.ref.WeakReference
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Event-driven pool state. Replaces the previous selector model.
 *
 * There is not one wall-clock rule in here: no 10-minute host marks, no 24-hour
 * certificate bans, no 30-minute staleness. State moves only on events -
 * a failed attempt, a successful attempt, a hop failure reported by the relay, a
 * user action - plus refreshes that are themselves triggered by demand or by a
 * failure latch. Anything that used to expire now has to be cleared, which is
 * why the settings screen has Clear pool / Clear bans / Clear learned hosts.
 *
 * What it owns:
 *  - the healthy set from the last health pass, and which gateway that pass ran
 *    through;
 *  - strikes per proxy (a proxy leaves the rotation at the strike threshold and
 *    stays out until a refresh or Clear pool);
 *  - certificate bans (persist until Clear bans);
 *  - learned hosts: hosts whose direct route keeps failing, which FALLBACK then
 *    sends pool-first; they persist until the user clears them or until the
 *    pool has failed for that host [poolHostChainFailureLimit] times;
 *  - sticky per-host route assignment, rotated only after a user-set number of
 *    requests (0 = stay on the same route).
 */
object ProxyPoolState {

	private const val TAG = "ProxyPool"

	/** Transport failures seen before an automatic refresh is latched in. */
	private const val REFRESH_LATCH = 5

	@Volatile
	private var settingsRef: AppSettings? = null
	private var contextRef: WeakReference<Context>? = null
	private var baseClientRef: WeakReference<OkHttpClient>? = null

	@Volatile
	var healthy: List<HealthyProxy> = emptyList()
		private set

	@Volatile
	var gateway: ProxyEndpoint? = null

	@Volatile
	var relayPort: Int = 0

	@Volatile
	private var summary = "not initialized"

	@Volatile
	private var refreshing = false

	/** proxyKey -> consecutive transport failures. */
	private val strikes = ConcurrentHashMap<String, Int>()

	/** proxyKey -> banned after a certificate error, until Clear bans. */
	private val certBans = ConcurrentHashMap<String, Boolean>()

	/** host -> direct failures seen; crossing the threshold learns the host. */
	private val directFailures = ConcurrentHashMap<String, Int>()

	/** host -> pool failures seen while learned; crossing the limit unlearns it. */
	private val learnedHosts = ConcurrentHashMap<String, Int>()

	/** host -> Cloudflare challenges served through the pool. */
	private val challengeCounts = ConcurrentHashMap<String, Int>()

	private val assignments = ConcurrentHashMap<String, PoolRoute>()
	private val useCounts = ConcurrentHashMap<String, Int>()
	private val rotation = AtomicInteger(0)
	private val failureLatch = AtomicInteger(0)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val refreshMutex = Mutex()

	/** Called from NetworkModule when the base client is built, and only when the pool is installed. */
	fun attach(context: Context, settings: AppSettings, baseClient: OkHttpClient) {
		contextRef = WeakReference(context.applicationContext)
		settingsRef = settings
		baseClientRef = WeakReference(baseClient)
		scope.launch {
			val cached = ProxyHealthChecker.loadFromCache(context.applicationContext.cacheDir)
			if (cached != null && healthy.isEmpty()) {
				healthy = cached
				summary = "loaded ${cached.size} cached entries"
				Log.i(TAG, "cold start: ${cached.size} cached healthy entries")
			}
			if (settings.poolChainEnabled) {
				// chain mode needs a gateway before it can serve anything
				refreshAsync(force = false)
			}
		}
	}

	// region gates

	val mode: PoolMode
		get() = settingsRef?.poolMode ?: PoolMode.OFF

	/** Per-attempt connect timeout for the existing (direct) route. */
	fun directTimeoutMs(): Int = (settingsRef?.poolDirectTimeoutSeconds ?: 5) * 1000

	/** Per-attempt timeout for a candidate dialed through a gateway. */
	fun chainTimeoutMs(): Int = (settingsRef?.poolChainTimeoutSeconds ?: 8) * 1000

	/** Mode plus this tier's category switch. Read per request, so it applies live. */
	fun isApplicable(category: PoolCategory): Boolean {
		val s = settingsRef ?: return false
		if (s.poolMode == PoolMode.OFF) {
			return false
		}
		return when (category) {
			PoolCategory.SOURCES_IMAGES -> s.poolCategorySources
			PoolCategory.VIDEO -> s.poolCategoryVideo
			PoolCategory.APP_SERVICES -> s.poolCategoryAppServices
		}
	}

	/**
	 * Hosts the pool must never touch: the wsrv.nl image proxy, the user's
	 * Cloudflare-worker relay, and anything on the manual never-use list.
	 */
	fun isNeverUse(host: String): Boolean {
		val h = host.lowercase()
		if (h == "wsrv.nl" || h.endsWith(".wsrv.nl")) {
			return true
		}
		val s = settingsRef ?: return false
		if (s.wsrvWorkerEnabled) {
			val workerHost = runCatching { s.wsrvWorkerUrl.toHttpUrlOrNull()?.host }.getOrNull()
			if (workerHost != null && workerHost.equals(h, ignoreCase = true)) {
				return true
			}
		}
		val neverUse = s.poolNeverUseHosts
		if (neverUse.isEmpty()) {
			return false
		}
		return h in neverUse || neverUse.any { h.endsWith(".$it") }
	}

	/** True while this host is marked pool-first because its direct route fails. */
	fun isLearnedHost(host: String): Boolean = learnedHosts.containsKey(host)

	/** Read per request, so changing it applies without a restart. */
	fun cookieMode(): PoolCookieMode = settingsRef?.poolCookieMode ?: PoolCookieMode.AUTO

	/**
	 * True once a host has been served too many Cloudflare challenges through the
	 * pool: the proxies are the problem, not the route, so the host stops using
	 * the pool until a refresh or Clear pool.
	 */
	fun isChallengeBlocked(host: String): Boolean {
		val limit = settingsRef?.poolChallengeLimit ?: 2
		return (challengeCounts[host] ?: 0) >= limit
	}

	// endregion

	// region routing

	/**
	 * The route to try next for [host], or null when the pool has nothing to
	 * offer. Sticky per host: the same route is reused until it disappears from
	 * the healthy set or the user's rotate-after count is reached, so one host
	 * does not spray its requests over a dozen exit IPs.
	 */
	fun nextRoute(host: String): PoolRoute? {
		syncRelay()
		val routes = availableRoutes()
		if (routes.isEmpty()) {
			// demand-triggered, not clock-triggered: an empty pool asks for a
			// refresh at most once per pass (refreshAsync is mutex-guarded)
			refreshAsync(force = false)
			return null
		}
		val rotateAfter = settingsRef?.poolRotateAfterRequests ?: 0
		val current = assignments[host]
		if (current != null && routes.any { it.key == current.key }) {
			if (rotateAfter <= 0) {
				return current
			}
			val used = (useCounts[host] ?: 0) + 1
			if (used < rotateAfter) {
				useCounts[host] = used
				return current
			}
			useCounts.remove(host)
		}
		val index = rotation.getAndIncrement().mod(routes.size)
		val picked = routes[index]
		assignments[host] = picked
		useCounts.remove(host)
		return picked
	}

	private fun availableRoutes(): List<PoolRoute> {
		val out = ArrayList<PoolRoute>(healthy.size + 1)
		val gw = gateway
		val port = relayPort
		if (gw != null && port != 0 && isUsable(gw.key)) {
			out += PoolRoute.RelayRoute(port, relayCredential())
		}
		for (h in healthy) {
			val endpoint = h.entry.toEndpoint()
			if (!isUsable(endpoint.key)) {
				continue
			}
			out += PoolRoute.Single(endpoint)
		}
		return out
	}

	private fun isUsable(proxyKey: String): Boolean {
		if (certBans.containsKey(proxyKey)) {
			return false
		}
		val threshold = settingsRef?.poolStrikeThreshold ?: 3
		return (strikes[proxyKey] ?: 0) < threshold
	}

	private fun relayCredential(): String {
		return relayRef?.get()?.credential() ?: ""
	}

	@Volatile
	private var relayRef: WeakReference<ProxyChainRelay>? = null

	/** The relay the chain routes dial, if one is running. */
	fun relay(): ProxyChainRelay? = relayRef?.get()

	/** Called by the bootstrap with the result of a health pass. */
	internal fun publishHealth(report: HealthReport) {
		if (report.error != null) {
			return
		}
		healthy = report.healthy
		// a fresh healthy set is a fresh start for challenge counting: the exits
		// that were refused are usually not in it any more
		challengeCounts.clear()
	}

	internal fun publishGateway(endpoint: ProxyEndpoint?) {
		gateway = endpoint
	}

	/**
	 * Stops the relay and forgets the chain. Called as soon as chain mode is off -
	 * checked on every routing decision, not on a timer - and by Clear pool, so a
	 * disabled chain never leaves a listener behind.
	 */
	fun stopRelay() {
		val relay = relayRef?.get()
		if (relay != null && relay.isRunning) {
			relay.stop()
			Log.i(TAG, "relay stopped")
		}
		if (relayPort != 0) {
			PooledClients.remove(PoolRoute.RelayRoute(relayPort, "").key)
		}
		relayPort = 0
		gateway = null
	}

	/** Live check, so switching chain mode off closes the relay without a restart. */
	private fun syncRelay() {
		if (settingsRef?.poolChainEnabled == true) {
			return
		}
		if (relayPort != 0 || relayRef?.get()?.isRunning == true) {
			stopRelay()
		}
	}

	/** Publishes the relay the chain routes dial, and listens for hop failures. */
	fun attachRelay(relay: ProxyChainRelay) {
		relayRef = WeakReference(relay)
		relayPort = relay.port
		relay.failureListener = ProxyChainRelay.FailureListener { failure, chain, targetHost ->
			reportHopFailure(failure, chain, targetHost)
		}
	}

	// endregion

	// region outcome reporting

	/** A direct attempt failed with a transport-class error. */
	fun reportDirectFailure(host: String, cls: FailureClass) {
		if (!cls.isTransportFailure) {
			return
		}
		val n = (directFailures[host] ?: 0) + 1
		directFailures[host] = n
		noteTransportFailure()
		val threshold = settingsRef?.poolStrikeThreshold ?: 3
		if (n >= threshold && !learnedHosts.containsKey(host)) {
			learnedHosts[host] = 0
			Log.i(TAG, "host=$host learned: pool-first after $n direct $cls failures")
		}
	}

	fun reportSuccess(host: String, route: PoolRoute) {
		when (route) {
			is PoolRoute.Single -> strikes.remove(route.endpoint.key)
			is PoolRoute.RelayRoute -> currentChain()?.let {
				strikes.remove(it.gateway.key)
				strikes.remove(it.main.key)
			}
		}
	}

	/** A pooled attempt failed. Strikes the member, and may unlearn the host. */
	fun reportRouteFailure(host: String, route: PoolRoute, cls: FailureClass) {
		when (route) {
			is PoolRoute.Single -> strike(route.endpoint.key, cls, host)
			is PoolRoute.RelayRoute -> {
				// the relay reports WHICH hop failed; without that we would blame
				// the whole chain and drop good proxies
				currentChain()?.let {
					strike(it.main.key, cls, host)
				}
			}
		}
		noteTransportFailure()
		val learned = learnedHosts[host]
		if (learned != null) {
			val n = learned + 1
			val limit = settingsRef?.poolHostChainFailureLimit ?: 3
			if (n >= limit) {
				learnedHosts.remove(host)
				directFailures.remove(host)
				Log.i(TAG, "host=$host unlearned: the pool failed $n times for it")
			} else {
				learnedHosts[host] = n
			}
		}
	}

	/** Hop-level failure from the relay: blame the member that actually failed. */
	fun reportHopFailure(failure: HopFailure, chain: ChainRoute, targetHost: String) {
		val proxyKey = when (failure) {
			HopFailure.GATEWAY_UNREACHABLE,
			HopFailure.GATEWAY_REFUSED_MAIN,
			-> chain.gateway.key

			HopFailure.MAIN_HANDSHAKE_FAILED,
			HopFailure.TARGET_CONNECT_FAILED,
			-> chain.main.key
		}
		strike(proxyKey, FailureClass.OTHER, targetHost)
		noteTransportFailure()
	}

	private fun strike(proxyKey: String, cls: FailureClass, host: String) {
		if (cls == FailureClass.TLS) {
			certBans[proxyKey] = true
			Log.w(TAG, "proxy banned after a certificate error seen for host=$host (until Clear bans)")
			return
		}
		val n = (strikes[proxyKey] ?: 0) + 1
		strikes[proxyKey] = n
		val threshold = settingsRef?.poolStrikeThreshold ?: 3
		if (n >= threshold) {
			Log.w(TAG, "proxy left the rotation after $n strikes (last failure for host=$host)")
		}
	}

	private fun currentChain(): ChainRoute? = relayRef?.get()?.route

	/**
	 * A pooled response was a Cloudflare challenge. Not a transport failure: the
	 * chain worked, the exit IP was refused. Counted per host, because hammering
	 * more proxies at a host that challenges them only burns proxies.
	 */
	fun reportChallenge(host: String) {
		val n = (challengeCounts[host] ?: 0) + 1
		challengeCounts[host] = n
		val limit = settingsRef?.poolChallengeLimit ?: 2
		if (n >= limit) {
			Log.w(TAG, "host=$host stopped using the pool: $n Cloudflare challenges through it")
		} else {
			Log.i(TAG, "host=$host served a Cloudflare challenge through the pool ($n/$limit)")
		}
	}

	// endregion

	// region failure classification

	/**
	 * Transport failure classes. [isTransportFailure] decides whether a failure is
	 * a reason to try another route at all: an origin answer or a programming
	 * error is not.
	 */
	enum class FailureClass(val isTransportFailure: Boolean) {
		RESET(true),
		TIMEOUT(true),
		TLS(true),
		REFUSED(true),
		UNREACHABLE(true),
		OTHER(false),
	}

	fun classifyFailure(t: Throwable?): FailureClass {
		var current: Throwable? = t
		while (current != null) {
			when (current) {
				// order matters: SSLHandshakeException is an SSLException, and
				// ConnectException is a SocketException
				is SSLHandshakeException -> {
					val message = current.message ?: ""
					return if (message.contains("reset", true)) FailureClass.RESET else FailureClass.TLS
				}

				is SSLException -> return FailureClass.TLS
				is ConnectException -> {
					val message = current.message ?: ""
					return if (message.contains("refused", true)) {
						FailureClass.REFUSED
					} else {
						FailureClass.UNREACHABLE
					}
				}

				is SocketTimeoutException -> return FailureClass.TIMEOUT
				is SocketException -> {
					val message = current.message ?: ""
					if (message.contains("reset", true) || message.contains("abort", true)) {
						return FailureClass.RESET
					}
				}

				is UnknownHostException -> return FailureClass.UNREACHABLE
			}
			current = current.cause
		}
		return FailureClass.OTHER
	}

	private fun noteTransportFailure() {
		if (failureLatch.incrementAndGet() >= REFRESH_LATCH) {
			failureLatch.set(0)
			refreshAsync(force = false)
		}
	}

	// endregion

	// region refresh

	val isRefreshing: Boolean
		get() = refreshing

	fun refreshAsync(force: Boolean) {
		val s = settingsRef ?: return
		val client = baseClientRef?.get() ?: return
		val ctx = contextRef?.get() ?: return
		if (refreshing && !force) {
			return
		}
		scope.launch {
			if (force) {
				refreshMutex.lock()
			} else if (!refreshMutex.tryLock()) {
				return@launch
			}
			try {
				refreshing = true
				summary = if (s.poolChainEnabled) {
					ProxyPoolBootstrap.runCycle(client, ctx, s)
				} else {
					stopRelay()
					ProxyPoolBootstrap.refreshSingles(client, ctx, s)
				}
				Log.i(TAG, "refresh done: $summary")
			} catch (e: Exception) {
				e.printStackTraceDebug()
				summary = "refresh failed: ${e.javaClass.simpleName}"
			} finally {
				refreshing = false
				refreshMutex.unlock()
			}
		}
	}

	/** Human-readable status for the settings screen. */
	fun statusLine(mode: PoolMode): String = when (mode) {
		PoolMode.OFF -> "Off"
		else -> buildString {
			append(summary)
			if (refreshing) {
				append(" (refreshing...)")
			}
		}
	}

	// endregion

	// region user actions

	fun clearPool() {
		stopRelay()
		healthy = emptyList()
		strikes.clear()
		challengeCounts.clear()
		assignments.clear()
		useCounts.clear()
		learnedHosts.clear()
		directFailures.clear()
		failureLatch.set(0)
		PooledClients.clear()
		refreshAsync(force = true)
	}

	fun clearBans() {
		certBans.clear()
		strikes.clear()
		Log.i(TAG, "certificate bans and strikes cleared")
	}

	fun clearLearnedHosts() {
		learnedHosts.clear()
		directFailures.clear()
		assignments.clear()
		Log.i(TAG, "learned hosts cleared")
	}

	// endregion
}
