package org.wastaken.kotatsu.api21.core.network.proxypool

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.wastaken.kotatsu.api21.core.network.CloudFlareInterceptor
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyHealthChecker.HealthyProxy
import org.wastaken.kotatsu.api21.core.network.proxy.ProxyType
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

/** Operating mode of the experimental proxy pool (pref value = enum name).
 *  Chained routing arrives with the bootstrap commit (5/7); the values here
 *  are exactly the ones the legacy screen already knows. */
enum class PoolMode {
	/** Default. The routing interceptor passes every request through. */
	OFF,

	/** Direct route is tried first; pool proxies are the fallback. */
	FALLBACK,

	/** Pool proxies are tried first; direct is only the fail-open tail. */
	ALWAYS,
}

const val POOL_DEFAULT_MAX_HEALTHY = 20
const val POOL_MAX_HEALTHY_CAP = 100

/** HEAD-probed through each candidate during health checks; must answer HEAD with 2xx. */
const val POOL_DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

/** Verified 2026-10-04 (fetch_page, list formats + line counts + freshness). */
const val POOL_DEFAULT_LISTS =
	"https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/http.txt\n" +
		"https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt\n" +
		"https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/all.txt"

/**
 * Narrow login-cookie rule (user-confirmed 2026-10-05). Exact names and
 * prefixes REUSE the account-cookie notion of the parsers' BooruParser
 * (authCookieNames = ["user_id","pass_hash"], Philomena adds "remember_web"),
 * without pulling parser internals into app code. Anonymous session cookies
 * (PHPSESSID, _*_session, _danbooru_session, ...) NEVER match: they exist for
 * anonymous visitors and must not exclude hosts from the pool.
 */
enum class LoginRule(val logLabel: String) {
	BOORU_ACCOUNT("login-booru"),      // user_id / pass_hash / password_hash / login
	REMEMBER_TOKEN("login-remember"),  // remember_login/-me/-webtoken/remember_web
	AUTH_TOKEN("login-token"),         // auth_token/access_token/api_token/connect.sid
	WORDPRESS("login-wordpress"),      // wordpress_logged_in_*
	PHPBB("login-phpbb"),              // phpbb3_*
}

/**
 * Proxy pool (Task C) - commit 3/7: the controller, rebuilt event-driven.
 *
 * WHAT DIED WITH THE OLD CONTROLLER (amendment 2, all in this commit):
 *  - ProxyPoolSelector (old transport-level wrapper): amendment 1 demands
 *    proxyProvider.selector stays THE transport selector on every tier;
 *  - ProxyPoolRetryInterceptor + PoolRouteInterceptor (old attempt plumbing):
 *    replaced by ONE PoolRoutingInterceptor + pooled clients;
 *  - every old TIME-BASED rule: 10-minute host marks (MARK_WINDOW/TTL),
 *    the 24h cert ban, refresh age gates (STATE_MAX_AGE_MS), and the retry
 *    counters behind them. The state below flips on EVENTS only (4.4):
 *      * direct reset failure       -> poolFirst(host) = true
 *      * direct success             -> poolFirst(host) = false
 *      * proxy failure streak >= 5  -> proxy dropped from plans
 *      * TLS-class via a proxy      -> proxy dropped immediately
 *      * host's pool set exhausted  -> ONE re-verify of that host; one
 *        refresh pass when the global set runs dry
 *    Dropped proxies re-enter only through the next successful health
 *    pass - the honest event-driven replacement for the 24h wall-clock ban.
 *
 * THE NEW MODEL (amendments 1+4):
 *  - tier clients keep proxyProvider.selector untouched; routing is decided
 *    PER REQUEST by PoolRoutingInterceptor, which executes pool attempts
 *    through POOLED CLIENTS (one per distinct route, LRU of 32, derived
 *    from the calling tier's client via newBuilder() - see pooled());
 *  - pooled clients share the tier's interceptors / dispatcher /
 *    connectionPool; ONLY CloudFlareInterceptor and the routing interceptor
 *    itself are removed (amendment 4; RateLimitInterceptor was verified
 *    stateless per-response, so inheriting it changes nothing);
 *  - health state is created by event-triggered passes only: app start
 *    (disk copy, trusted until replaced), the user's refresh action, pool
 *    exhaustion, and the pool being asked for a route it does not have yet.
 */
object ProxyPoolController {

	private const val TAG = "ProxyPool"
	private const val EVAL_LOG_CAP = 64
	private const val HOST_STATE_CAP = 64
	private const val POOLED_CLIENT_CAP = 32
	private const val PROXY_FAIL_DROP_STREAK = 5
	private const val POOL_PLAN_CAP = 7

	private val LOGIN_EXACT: Map<String, LoginRule> = buildMap {
		listOf("user_id", "pass_hash", "password_hash", "login")
			.forEach { put(it, LoginRule.BOORU_ACCOUNT) }
		listOf("remember_login", "remember_me", "remember_webtoken", "remember_web")
			.forEach { put(it, LoginRule.REMEMBER_TOKEN) }
		listOf("auth_token", "access_token", "api_token", "connect.sid")
			.forEach { put(it, LoginRule.AUTH_TOKEN) }
	}
	private const val WORDPRESS_PREFIX = "wordpress_logged_in_"
	private const val PHPBB_PREFIX = "phpbb3_"

	/** One attempt of a request plan. Direct runs on the calling chain;
	 *  ViaProxy runs through the pooled client pinned to the entry. */
	sealed interface RoutePlan {
		data object Direct : RoutePlan
		data class ViaProxy(val proxy: HealthyProxy) : RoutePlan
	}

	/** Verdict for one host on one pool evaluation. */
	data class HostVerdict(
		val host: String,
		/** non-null => host must stay DIRECT (anonymous-traffic-only rule) */
		val loginRule: LoginRule?,
		/** Cloudflare cookies present: proxied traffic gets cf_* filtered */
		val hasCloudflare: Boolean,
		val cookieCount: Int,
	)

	/** Snapshot consumed by the settings status line. */
	data class Status(
		val healthy: List<HealthyProxy>,
		val refreshing: Boolean,
		val lastSummary: String,
	)

	/** Event-only per-host routing memory (no timestamps anywhere). */
	private class HostRouteState {
		@Volatile var poolFirst: Boolean = false

		@Volatile var verified: List<HealthyProxy> = emptyList()

		@Volatile var verifying: Boolean = false
		val failedKeys = HashSet<String>() // all access under synchronized(this)
	}

	@Volatile
	private var settingsRef: AppSettings? = null

	@Volatile
	private var contextRef: WeakReference<Context>? = null

	@Volatile
	private var baseClientRef: WeakReference<OkHttpClient>? = null

	/** The mode the pool was BUILT with (amendment 3: Off<->any-mode shows a
	 *  "takes effect after an app restart" sentence on the settings screen,
	 *  mirroring the SSL-bypass precedent; Fallback/Always flips live). */
	@Volatile
	private var modeSnapshot: PoolMode = PoolMode.OFF

	@Volatile
	var status = Status(emptyList(), false, "not initialized")
		private set

	@Volatile
	private var candidates: List<ProxyListFetcher.ProxyEntry> = emptyList()

	private val hostStates = ConcurrentHashMap<String, HostRouteState>()
	private val droppedProxyKeys = ConcurrentHashMap.newKeySet<String>()
	private val proxyFailStreak = ConcurrentHashMap<String, Int>()
	private val lastEvalLog = ConcurrentHashMap<String, String>()
	private val rotation = AtomicInteger(0)
	internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val refreshMutex = Mutex()

	/** LRU of pooled clients, keyed per (tier client, route). Amendment 4:
	 *  never per-request, at most POOLED_CLIENT_CAP, eldest evicted. */
	private val pooledClients = object : LinkedHashMap<Pair<Int, String>, OkHttpClient>(
		POOLED_CLIENT_CAP, 0.75f, true,
	) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, String>, OkHttpClient>?): Boolean =
			size > POOLED_CLIENT_CAP
	}
	private val pooledLock = Any()

	/**
	 * Called once from NetworkModule when the base client is built (single
	 * hook). Pins the build-time mode snapshot (amendment 3), then makes the
	 * disk state available immediately - it is trusted until the next
	 * successful pass replaces it (event driven, 4.4).
	 */
	fun attach(context: Context, settings: AppSettings, baseClient: OkHttpClient) {
		contextRef = WeakReference(context.applicationContext)
		settingsRef = settings
		baseClientRef = WeakReference(baseClient)
		modeSnapshot = settings.poolMode
		scope.launch {
			val cached = ProxyHealthChecker.loadFromCache(context.applicationContext.cacheDir)
			if (cached != null) {
				status = Status(cached, false, "loaded ${cached.size} saved entries")
				Log.i(TAG, "start: ${cached.size} saved healthy entries (trusted until replaced)")
			}
		}
	}

	/** The mode requests see RIGHT NOW: the build snapshot gates off<->on
	 *  (restart sentence on the screen); Fallback/Always read live. Turning
	 *  the pool OFF at runtime passes requests through from now on - active
	 *  pooled calls finish - while full teardown still waits for restart. */
	fun effectiveMode(): PoolMode {
		if (modeSnapshot == PoolMode.OFF) {
			return PoolMode.OFF
		}
		return settingsRef?.poolMode ?: PoolMode.OFF
	}

	/**
	 * Amendment 1+3: the pool is INERT while the user has a static proxy
	 * configured (proxyType != DIRECT) or SSL bypass is enabled. Both read
	 * live on purpose: flipping them kills pooling immediately rather than
	 * silently mixing models for one host.
	 */
	fun isInert(): Boolean {
		val s = settingsRef ?: return true
		if (s.proxyType != ProxyType.DIRECT || s.isSSLBypassEnabled) {
			return true
		}
		return false
	}

	/** The base client the routing interceptor derives from when the calling
	 *  call's own client cannot be read (defensive fallback only). */
	fun baseClientOrNull(): OkHttpClient? = baseClientRef?.get()

	// region per-request planning (cheap, no I/O)

	/**
	 * Ordered attempt plan for one host. The routing interceptor already
	 * filtered the free cases (mode/scheme); everything else - hard
	 * exclusions, the manual list, login cookies - is decided here.
	 */
	fun planFor(host: String, mode: PoolMode): List<RoutePlan> {
		if (mode == PoolMode.OFF || isInert()) {
			return DIRECT_PLAN
		}
		val settings = settingsRef ?: return DIRECT_PLAN
		if (isHardExcluded(host, settings) ||
			isForbiddenManually(host, settings.poolForbiddenHosts)
		) {
			return DIRECT_PLAN
		}
		val verdict = cookieVerdict(host)
		if (verdict.loginRule != null) {
			return DIRECT_PLAN // anonymous traffic only (kept rule)
		}
		// a pool that is asked for a route it does not have is the EVENT
		// that launches candidate gathering (4.4: on demand, no age gate)
		if (status.healthy.isEmpty() && !status.refreshing) {
			refreshAsync(force = false)
		}
		val pool = poolRoutesFor(host)
		return when (mode) {
			PoolMode.OFF -> DIRECT_PLAN
			PoolMode.FALLBACK ->
				if (stateOf(host).poolFirst) {
					pool + RoutePlan.Direct
				} else {
					listOf(RoutePlan.Direct) + pool
				}

			PoolMode.ALWAYS ->
				if (pool.isEmpty()) {
					Log.w(TAG, "ALWAYS with empty pool for host=$host: direct fail-open tail only")
					DIRECT_PLAN
				} else {
					pool + RoutePlan.Direct
				}
		}
	}

	/** Verified-for-this-host proxies first (minus failed/dropped), then the
	 *  global healthy set, small O(1) rotation. */
	private fun poolRoutesFor(host: String): List<RoutePlan.ViaProxy> {
		val st = stateOf(host)
		val out = ArrayList<RoutePlan.ViaProxy>(POOL_PLAN_CAP)
		synchronized(st) {
			for (h in st.verified) {
				if (out.size >= POOL_PLAN_CAP) break
				val key = proxyKeyOf(h.entry)
				if (key !in st.failedKeys && key !in droppedProxyKeys) {
					out += RoutePlan.ViaProxy(h)
				}
			}
		}
		val healthy = status.healthy
		if (healthy.isNotEmpty() && out.size < POOL_PLAN_CAP) {
			val start = if (healthy.size <= 1) 0 else rotation.getAndIncrement() % healthy.size
			val failed: Set<String> = synchronized(st) { HashSet(st.failedKeys) }
			for (i in 0 until healthy.size) {
				if (out.size >= POOL_PLAN_CAP) break
				val h = healthy[(start + i) % healthy.size]
				val key = proxyKeyOf(h.entry)
				if (key in failed || key in droppedProxyKeys) continue
				if (out.any { proxyKeyOf(it.proxy.entry) == key }) continue
				out += RoutePlan.ViaProxy(h)
			}
		}
		return out
	}

	private fun stateOf(host: String): HostRouteState {
		val existing = hostStates[host]
		if (existing != null) {
			return existing
		}
		if (hostStates.size >= HOST_STATE_CAP) {
			// event-bounded memory: evict an arbitrary different entry once
			// the cap is reached (it re-verifies on demand; logged once)
			val victim = hostStates.keys.firstOrNull { it != host }
			if (victim != null) {
				hostStates.remove(victim)
				Log.i(TAG, "host-state cap reached; evicted state for $victim")
			}
		}
		return hostStates.getOrPut(host) { HostRouteState() }
	}

	/** Cookie-based verdict. Reads the SHARED jar snapshot; never mutates it. */
	fun cookieVerdict(host: String): HostVerdict {
		val url = "https://$host/".toHttpUrlOrNull()
		val jar = baseClientRef?.get()?.cookieJar
		val cookies: List<Cookie> = if (url != null && jar != null) {
			runCatching { jar.loadForRequest(url) }.getOrElse { emptyList() }
		} else {
			emptyList()
		}
		var login: LoginRule? = null
		var cf = false
		for (c in cookies) {
			val n = c.name.lowercase()
			if (login == null) {
				login = LOGIN_EXACT[n] ?: when {
					n.startsWith(WORDPRESS_PREFIX) -> LoginRule.WORDPRESS
					n.startsWith(PHPBB_PREFIX) -> LoginRule.PHPBB
					else -> null
				}
			}
			if (!cf && isCloudflareCookie(n)) {
				cf = true
			}
		}
		val verdict = HostVerdict(host, login, cf, cookies.size)
		logEvaluation(verdict)
		return verdict
	}

	fun isForbiddenManually(host: String, forbidden: Set<String>): Boolean {
		if (forbidden.isEmpty()) {
			return false
		}
		val h = host.lowercase()
		return h in forbidden || forbidden.any { h.endsWith(".$it") }
	}

	/**
	 * Never routed through the pool, no matter the mode: the wsrv.nl image
	 * proxy and the user's Cloudflare-worker relay (spec: the pool must never
	 * touch the wsrv.nl image-proxy path). Semantics unchanged from the old
	 * controller (missed in the first 3/7 write; CI caught it).
	 */
	fun isHardExcluded(host: String, settings: AppSettings): Boolean {
		if (host == "wsrv.nl" || host.endsWith(".wsrv.nl")) {
			return true
		}
		if (settings.wsrvWorkerEnabled) {
			val workerHost = runCatching { settings.wsrvWorkerUrl.toHttpUrlOrNull()?.host }.getOrNull()
			if (workerHost != null && workerHost.equals(host, ignoreCase = true)) {
				return true
			}
		}
		return false
	}

	/**
	 * Cloudflare-managed cookies (amendment 5 name set): exact cf_clearance
	 * and __cf_bm, contains cfuvid, prefixes cf_chl / cf_ / _cf. NEVER
	 * matches csrftoken / XSRF-TOKEN / anything not CF-owned. The 4/7
	 * filtering jar ships the storage integration + the non-overwrite
	 * proof; the pooled clients already filter with this exact matcher.
	 */
	fun isCloudflareCookie(nameLower: String): Boolean =
		nameLower == "cf_clearance" || nameLower == "__cf_bm" ||
			nameLower.contains("cfuvid") ||
			nameLower.startsWith("cf_chl") ||
			nameLower.startsWith("cf_") ||
			nameLower.startsWith("_cf")

	fun proxyKeyOf(entry: ProxyListFetcher.ProxyEntry): String = "host=${entry.host} port=${entry.port}"

	private fun logEvaluation(v: HostVerdict) {
		val line = when {
			v.loginRule != null -> "excluded=${v.loginRule.logLabel} cookies=${v.cookieCount}"
			v.hasCloudflare -> "cleared (cf cookies filtered on pooled routes) cookies=${v.cookieCount}"
			else -> "cleared cookies=${v.cookieCount}"
		}
		val prev = lastEvalLog.put(v.host, line)
		if (prev != line) {
			Log.i(TAG, "host=${v.host} $line")
		}
		if (lastEvalLog.size > EVAL_LOG_CAP) {
			lastEvalLog.clear() // bounded memory; transitions simply re-log
		}
	}

	// endregion — never store cookie names/values from the jar: logEvaluation
	// is the ONLY point that observes verdicts, and it logs the matched RULE
	// LABEL + cookie count + host (logging amendment of the cookie rules).

	// region outcome feedback (events only, no clocks)

	/** The routing interceptor reports every attempt outcome here. */
	fun reportOutcome(host: String, route: RoutePlan, failure: IOException?) {
		when (route) {
			RoutePlan.Direct -> {
				if (failure == null) {
					val st = hostStates[host] ?: return
					if (st.poolFirst) {
						st.poolFirst = false
						Log.i(TAG, "host=$host direct success: pool-first flag cleared")
					}
				} else if (classifyFailure(failure) == FailureClass.RESET) {
					val st = stateOf(host)
					if (!st.poolFirst) {
						st.poolFirst = true
						Log.i(TAG, "host=$host direct reset: pool-first from now on")
					}
					ensureVerifiedAsync(host)
				}
			}

			is RoutePlan.ViaProxy -> {
				val key = proxyKeyOf(route.proxy.entry)
				if (failure == null) {
					proxyFailStreak.remove(key)
					stateOf(host).poolFirst = true
				} else {
					val cls = classifyFailure(failure)
					val st = stateOf(host)
					synchronized(st) { st.failedKeys += key }
					val streak = (proxyFailStreak[key] ?: 0) + 1
					proxyFailStreak[key] = streak
					Log.i(TAG, "host=$host pool attempt failed ($cls) streak=$streak")
					if (cls == FailureClass.TLS || streak >= PROXY_FAIL_DROP_STREAK) {
						// event-driven replacement for the old 24h ban: the
						// proxy leaves the plans until a successful pass
						// re-habilitates it
						if (droppedProxyKeys.add(key)) {
							Log.w(
								TAG,
								"proxy dropped from plans after " +
									(if (cls == FailureClass.TLS) "TLS error" else "$streak failures") +
									" (host=$host)",
							)
						}
					}
					ensureVerifiedAsync(host)
					if (remainingHealthy() == 0 && !status.refreshing) {
						// exhaustion event => one refresh pass (4.4)
						refreshAsync(force = false)
					}
				}
			}
		}
	}

	/** Event trigger: host has no (unfailed) verified proxy => verify it,
	 *  once at a time, using candidates from the latest list pass. */
	private fun ensureVerifiedAsync(host: String) {
		val st = stateOf(host)
		if (st.verifying) {
			return
		}
		val base = baseClientRef?.get() ?: return
		val s = settingsRef ?: return
		val snapshot = candidates.ifEmpty {
			// no candidates yet: the refresh event below creates them, and
			// the host verify rides along when they arrive (no second
			// verifier is launched from here)
			refreshAsync(force = false)
			return
		}
		st.verifying = true
		scope.launch {
			try {
				val report = ProxyHealthChecker.verifyForHostDirect(
					base, snapshot, host, s.poolTimeoutDirectS,
				)
				val dropped = droppedProxyKeys
				val verified = report.healthy.filter { proxyKeyOf(it.entry) !in dropped }
				synchronized(st) {
					st.verified = verified
					st.failedKeys.clear() // freshly verified: per-host ledger resets
				}
				Log.i(TAG, "host=$host verify: ${verified.size} proxies for the pool plan")
			} catch (e: Exception) {
				e.printStackTraceDebug()
			} finally {
				st.verifying = false
			}
		}
	}

	private fun remainingHealthy(): Int =
		status.healthy.count { proxyKeyOf(it.entry) !in droppedProxyKeys }

	/** Connection-level classification used by interceptor + controller. */
	fun classifyFailure(t: Throwable?): FailureClass {
		var cur: Throwable? = t
		while (cur != null) {
			when (cur) {
				is SSLHandshakeException -> {
					val msg = cur.message ?: ""
					return if (msg.contains("reset", true)) FailureClass.RESET else FailureClass.TLS
				}

				is SocketException -> {
					val msg = cur.message ?: ""
					if (msg.contains("reset", true) || msg.contains("abort", true)) {
						return FailureClass.RESET
					}
				}

				is SocketTimeoutException -> return FailureClass.TIMEOUT
			}
			cur = cur.cause
		}
		return FailureClass.OTHER
	}

	enum class FailureClass { RESET, TIMEOUT, TLS, OTHER }

	// endregion

	// region refresh orchestration (event-driven entry points only)

	/**
	 * One candidate-gathering + health pass. Callers: the user's refresh
	 * action (force = true), pool-asked-for-an-empty-set, exhaustion. Never
	 * runs on a timer; re-entrance guarded by the mutex.
	 */
	fun refreshAsync(force: Boolean) {
		if (isInert()) {
			return
		}
		val s = settingsRef ?: return
		val base = baseClientRef?.get() ?: return
		val ctx = contextRef?.get() ?: return
		scope.launch {
			if (force) {
				refreshMutex.lock()
			} else if (!refreshMutex.tryLock()) {
				return@launch
			}
			try {
				status = status.copy(refreshing = true)
				val listsResult = ProxyListFetcher.fetchLists(
					// the cycle's transport is a HARD-LOCKED direct clone of
					// the base client: list downloads never cross the pool
					base.newBuilder()
						.proxySelector(DIRECT_SELECTOR)
						.proxyAuthenticator(Authenticator.NONE)
						.build(),
					s.poolLists,
					s.poolMirrors,
					ctx.cacheDir,
				)
				candidates = listsResult.entries
				val report = ProxyHealthChecker.check(
					base, listsResult.entries, s.poolTestUrl, s.poolMaxHealthy, s.poolTimeoutDirectS,
				)
				if (report.error == null) {
					ProxyHealthChecker.saveToCache(ctx.cacheDir, report)
					droppedProxyKeys.clear() // fresh healthy set: drops re-earn themselves
				}
				val summary = buildSummary(listsResult, report)
				status = Status(report.healthy, false, summary)
				Log.i(TAG, "refresh done: $summary")
			} catch (e: Exception) {
				e.printStackTraceDebug()
				status = status.copy(refreshing = false, lastSummary = "refresh failed: ${e.javaClass.simpleName}")
			} finally {
				refreshMutex.unlock()
			}
		}
	}

	private val DIRECT_SELECTOR = object : ProxySelector() {
		override fun select(uri: URI?) = listOf(Proxy.NO_PROXY)
		override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
	}

	private fun buildSummary(
		lists: ProxyListFetcher.Result,
		health: ProxyHealthChecker.HealthReport,
	): String {
		val direct = lists.reports.count { it.error == null && it.source == ProxyListFetcher.ListSource.DIRECT }
		val disk = lists.reports.count { it.error == null && it.source == ProxyListFetcher.ListSource.DISK }
		val mirror = lists.reports.count { it.error == null && it.source == ProxyListFetcher.ListSource.MIRROR }
		val failed = lists.reports.count { it.error != null }
		return "${health.healthy.size} healthy of ${health.sampled} sampled " +
			"(${health.candidatesTotal} candidates; lists: $direct direct/$disk disk/$mirror mirror" +
			(if (failed > 0) ", $failed failed" else "") + ")"
	}

	/** Human-readable status for the settings screen (event-only wording). */
	fun statusLine(mode: PoolMode): String {
		if (modeSnapshot == PoolMode.OFF) {
			return if (settingsRef?.poolMode == PoolMode.OFF) {
				"Off"
			} else {
				"Off (mode change applies after an app restart)"
			}
		}
		if (effectiveMode() == PoolMode.OFF) {
			return "Passes through live; restart completes the switch to Off"
		}
		return buildString {
			append(status.lastSummary)
			if (status.refreshing) {
				append("; checking...")
			}
		}
	}

	// endregion

	// region pooled clients (amendment 4)

	/**
	 * The pooled client for one route and one tier: tierClient.newBuilder()
	 * with a pinned route selector, shared interceptors minus CloudFlare and
	 * the routing interceptor itself, explicit Authenticator.NONE (relay
	 * routes will carry the relay secret instead, starting with 5/7), and
	 * the pooled cookie wrapper (CF-managed cookies filtered; amendment 5
	 * matcher). LRU of 32, keyed by (tier identity, route).
	 */
	fun pooled(tier: OkHttpClient, entry: ProxyListFetcher.ProxyEntry): OkHttpClient {
		val key = System.identityHashCode(tier) to entry.toString()
		synchronized(pooledLock) {
			pooledClients[key]?.let { return it }
			val type = when (entry.scheme) {
				ProxyListFetcher.Scheme.HTTP -> Proxy.Type.HTTP
				ProxyListFetcher.Scheme.SOCKS4, ProxyListFetcher.Scheme.SOCKS5 -> Proxy.Type.SOCKS
			}
			val routeProxy = Proxy(type, InetSocketAddress.createUnresolved(entry.host, entry.port))
			val selector = object : ProxySelector() {
				override fun select(uri: URI?): List<Proxy> = listOf(routeProxy)
				override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
			}
			val client = tier.newBuilder()
				.proxySelector(selector)
				.proxyAuthenticator(Authenticator.NONE)
				.cookieJar(PoolCookieWrapper(tier.cookieJar))
				.apply {
					interceptors().removeAll {
						it is PoolRoutingInterceptor || it is CloudFlareInterceptor
					}
				}
				.build()
			pooledClients[key] = client
			return client
		}
	}

	/**
	 * Pooled-route cookie behavior (amendment 5 name set, from day one of
	 * the new model): CF-managed cookies are NEVER sent through pool
	 * proxies (the proxy would impersonate the cleared IP) and NEVER saved
	 * from pooled responses (a proxied response can never overwrite a CF
	 * cookie the DIRECT path minted). Every non-CF cookie passes both ways
	 * - sessions (PHPSESSID etc.) keep working through the pool. The 4/7
	 * filtering-jar commit formalizes the storage side and the proof.
	 */
	class PoolCookieWrapper(
		private val delegate: CookieJar,
	) : CookieJar {
		override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
			delegate.saveFromResponse(
				url,
				cookies.filterNot { isCloudflareCookie(it.name.lowercase()) },
			)
		}

		override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
			delegate.loadForRequest(url)
				.filterNot { isCloudflareCookie(it.name.lowercase()) }
	}

	private val DIRECT_PLAN = listOf(RoutePlan.Direct)
}

