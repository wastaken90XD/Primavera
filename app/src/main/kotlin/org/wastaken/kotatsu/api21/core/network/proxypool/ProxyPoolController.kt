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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.wastaken.kotatsu.api21.core.network.CloudFlareInterceptor
import org.wastaken.kotatsu.api21.core.network.cookies.CloudflareFilteringCookieJar
import org.wastaken.kotatsu.api21.core.network.disableCertificateVerification
import org.wastaken.kotatsu.api21.core.network.installExtraCertificates
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyHealthChecker.HealthyProxy
import org.wastaken.kotatsu.api21.core.network.proxy.ProxyProvider
import org.wastaken.kotatsu.api21.core.network.proxy.ProxyType
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetAddress
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

enum class PoolMode {
	/** Default. The routing interceptor passes every request through. */
	OFF,

	/** Direct route is tried first; pool routes are the fallback. */
	FALLBACK,

	/** Pool routes (chains of chainLength hops) are tried first. */
	ALWAYS,
}

/** Amendment 5 item 4: certificate checks on proxied requests. */
enum class PoolCertChecks {
	/** Inherit the tier TLS setup whole (trust-all included when the app
	 *  SSL bypass is on). The pool is never inert because of that. */
	FOLLOW_APP,

	/** Platform + bundled-roots helper on the pooled client. */
	ALWAYS_VERIFY,

	/** The app's disableCertificateVerification on the pooled client
	 *  only - no second trust-all implementation anywhere. */
	ALWAYS_IGNORE,
}

/** Ordinary (non-Cloudflare) cookies on proxied requests. */
enum class PoolCookiesMode {
	/** Strip the Cookie header on pool retries after the first failure. */
	AUTO,

	SEND,

	STRIP,
}

/** Cloudflare cookies on proxied requests (the amendment-5 filter). */
enum class PoolCfCookies {
	/** The filtering jar: direct path is the only writer of CF cookies. */
	STRIP,

	SEND,
}

/** Amendment 5 item 2f: fetch lists through a proxy. */
enum class PoolFetchLists {
	NEVER,

	/** Default: direct, disk and mirrors first, one proxy pass only when
	 *  nothing else produced a candidate. */
	IF_DIRECT_FAILS,

	/** Lists always ride a proxy before anything direct is tried. */
	ALWAYS,
}

/**
 * Proxy pool - amendment 5 (open chaining, restrictions became switches).
 *
 * WHAT CHANGED against the 1/7-7/7 engine:
 *  - No inert rules anywhere: the pool works with a static proxy set (the
 *    static proxy is just ONE possible chain hop, offered in the list) and
 *    with the app SSL bypass on (pooled clients inherit the tier TLS setup
 *    when Certificate checks = FOLLOW_APP).
 *  - Restriction switches (default = not restricted): plain HTTP rides the
 *    pool too (On), login-cookie skip (Off), category switches for
 *    Sources/images, Video and App services (all On), Cloudflare-cookie
 *    filter STRIP/SEND, Never-use/Always-use host lists (never wins).
 *  - Certificates: ALWAYS_VERIFY runs the same installExtraCertificates
 *    helper as the base client; ALWAYS_IGNORE reuses the app's existing
 *    disableCertificateVerification. No certificate code is edited and no
 *    second trust-all exists. With verification on, a proxy causing a
 *    certificate error is banned until Clear bans; with it off it is not.
 *  - Everything else stands: no timers, event-driven ledger, GET/HEAD-only
 *    retries, cancel watchdog, <=32 pooled clients, host-only logging.
 *
 * 10a scope: this commit carries the removal of the inert rules and every
 *  restriction switch + the certificate setting. Open N-hop chaining, the
 *  selection machinery and the list screen land in commits b/c/d.
 */
object ProxyPoolController {

	/**
	 * 6/7 per-tier routing: which client tier an installed
	 *  PoolRoutingInterceptor belongs to. A tier is pooled when its
	 *  category switch is On (amendment 5 item 5c); default is On for
	 *  all three - the old hard "video never pooled" rule became the
	 *  user's choice.
	 */
	enum class PoolTier { BASE, MANGA, VIDEO }

	private const val TAG = "ProxyPool"
	private const val EVAL_LOG_CAP = 64
	private const val HOST_STATE_CAP = 64
	private const val POOLED_CLIENT_CAP = 32
	private const val PROXY_FAIL_DROP_STREAK = 5
	private const val POOL_PLAN_CAP = 7

	/** How many assembled routes a single host's chainverify examines at
	 *  most (4.6.4 second choice; the same cap shape as the direct host
	 *  verify's HOST_VERIFY_CAP but route-side). */
	private const val MAX_HOST_CHAIN_ROUTES = 24

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
	 *  ViaProxy/ViaChain run through pooled clients pinned to the route. */
	sealed interface RoutePlan {
		data object Direct : RoutePlan
		data class ViaProxy(val proxy: HealthyProxy) : RoutePlan

		/** 5/7: the relay-token-bound chain (gateway + this main proxy). */
		data class ViaChain(val proxy: HealthyProxy, val token: String) : RoutePlan
	}

	/** Snapshot consumed by the settings status line (categories:
	 *  route healthy, relay chains built, challenged hosts, gateway
	 *  state - defaults keep the screen compiling). */
	data class Status(
		val healthy: List<HealthyProxy>,
		val refreshing: Boolean,
		val lastSummary: String,
		val chainsCount: Int = 0,
		val challengedCount: Int = 0,
		val gatewayLabel: String = "",
	)

	enum class LoginRule(val label: String) {
		BOORU_ACCOUNT("booru account"),
		REMEMBER_TOKEN("remember-me"),
		AUTH_TOKEN("auth token"),
		WORDPRESS("wordpress login"),
		PHPBB("phpBB login"),
	}

	data class HostVerdict(
		val host: String,
		val loginRule: LoginRule?,
		val cfManaged: Boolean,
		val cookieCount: Int,
	)

	/** Event-only per-host routing memory (no timestamps anywhere). */
	private class HostRouteState {
		@Volatile var poolFirst: Boolean = false

		@Volatile var verified: List<HealthyProxy> = emptyList()

		/** 5/7: chains verified FOR THIS HOST (4.6.4 second choice), each
		 *  already token-bound at the relay when stored. */
		@Volatile var verifiedChains: List<HealthyProxy> = emptyList()

		@Volatile var verifying: Boolean = false
		val failedKeys = HashSet<String>() // all access under synchronized(this)
	}

	@Volatile
	private var settingsRef: AppSettings? = null

	@Volatile
	private var contextRef: WeakReference<Context>? = null

	/** Pinned at attach (amendment 3): OFF<->any-mode needs an app
	 *  restart; Fallback/Always (and every amendment-5 switch) read live. */
	@Volatile
	private var modeSnapshot: PoolMode = PoolMode.OFF

	@Volatile
	var status: Status = Status(emptyList(), false, "never refreshed")
		private set

	private val hostStates = ConcurrentHashMap<String, HostRouteState>()
	private val droppedProxyKeys = mutableSetOf<String>() // session-only; cleared by a successful pass

	/** Amendment 5 4e: proxies banned by a certificate-error while
	 *  Certificate checks != ALWAYS_IGNORE. User-cleared only (Clear
	 *  bans lands with the list screen in commit d). */
	private val bannedProxyKeys = mutableSetOf<String>()

	/** Hosts whose pooled route produced a Cloudflare challenge (4/7). */
	private val challengedHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

	@Volatile
	private var candidates: List<ProxyListFetcher.ProxyEntry> = emptyList()

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

	@Volatile
	private var baseClientRef: WeakReference<OkHttpClient>? = null

	/** Static-proxy provider, read-only (its authenticator powers the
	 *  bootstrap's gateway-transport list fetch; ProxyProvider itself is
	 *  untouched). */
	@Volatile
	private var proxyProviderRef: WeakReference<ProxyProvider>? = null

	// region chains / relay (5/7; generalised to open N-hop chains in 10b)

	@Volatile
	private var relay: ProxyChainRelay? = null

	@Volatile
	private var gateway: ProxyChainRelay.GatewayBinding? = null

	@Volatile
	private var chainHealthy: List<HealthyProxy> = emptyList()

	/** entry key -> relay token; bindings are re-issued on every chain set
	 *  change and for every per-host verified chain. */
	private val chainTokens = ConcurrentHashMap<String, String>()
	private val relayLock = Any()
	private val relayTokenSeq = AtomicInteger(1)

	// endregion

	/**
	 * Called once from NetworkModule when the base client is built (single
	 * hook). Pins the build-time mode snapshot (amendment 3), then makes the
	 * disk state available immediately - it is trusted until the next
	 * successful pass replaces it (event driven, 4.4).
	 */
	fun attach(
		context: Context,
		settings: AppSettings,
		baseClient: OkHttpClient,
		proxyProvider: ProxyProvider,
	) {
		contextRef = WeakReference(context.applicationContext)
		settingsRef = settings
		baseClientRef = WeakReference(baseClient)
		proxyProviderRef = WeakReference(proxyProvider)
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

	/** The base client the routing interceptor derives from when the calling
	 *  call's own client cannot be read (defensive fallback only). */
	fun baseClientOrNull(): OkHttpClient? = baseClientRef?.get()

	// region per-request planning (cheap, no I/O)

	/**
	 * Ordered attempt plan for one host. The routing interceptor already
	 * filtered the free cases (mode/scheme/tier-category); everything else -
	 * the wsrv.nl hard exclusion, Never-use/Always use lists, the optional
	 * login-cookie skip - is decided here. Amendment 5: no inert branches.
	 */
	fun planFor(host: String, mode: PoolMode): List<RoutePlan> {
		if (mode == PoolMode.OFF) {
			return DIRECT_PLAN
		}
		val settings = settingsRef ?: return DIRECT_PLAN
		if (isHardExcluded(host, settings) ||
			isForbiddenManually(host, settings.poolForbiddenHosts)
		) {
			return DIRECT_PLAN // never-use list wins over always-use (item 5f)
		}
		val verdict = cookieVerdict(host)
		if (verdict.loginRule != null && settings.poolSkipLoginHosts) {
			// item 5b: the narrow login-cookie rule runs ONLY under the switch
			return DIRECT_PLAN
		}
		// a pool that is asked for a route it does not have is the EVENT
		// that launches candidate gathering (4.4: on demand, no age gate)
		if (status.healthy.isEmpty() && chainHealthy.isEmpty() && !status.refreshing) {
			refreshAsync(force = false)
		}
		val pool = poolRoutesFor(host)
		val st = stateOf(host)
		val alwaysUse = isListed(host, settings.poolAlwaysUseHosts)
		return when (mode) {
			PoolMode.OFF -> DIRECT_PLAN
			PoolMode.FALLBACK ->
				if (st.poolFirst || host in challengedHosts || alwaysUse) {
					// learned reset / observed challenge / always-use host:
					// pool goes first
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

	/** Chains planned for one host: per-host verified chains first, then
	 *  the global chain-healthy set, all rotated, all with relay tokens
	 *  already bound at bind-time. Open N-hop assignment replaces the
	 *  one-gateway model in 10b. */
	private fun chainRoutesFor(host: String): List<RoutePlan.ViaChain> {
		val st = stateOf(host)
		val out = ArrayList<RoutePlan.ViaChain>(POOL_PLAN_CAP)
		synchronized(st) {
			for (h in st.verifiedChains) {
				if (out.size >= POOL_PLAN_CAP) break
				val key = proxyKeyOf(h.entry)
				if (key !in st.failedKeys && key !in droppedProxyKeys && key !in bannedProxyKeys) {
					chainTokens[key]?.let { out += RoutePlan.ViaChain(h, it) }
				}
			}
		}
		val global = chainHealthy
		if (out.isEmpty() && global.isNotEmpty()) {
			val n = minOf(POOL_PLAN_CAP, global.size)
			val start = if (global.size <= 1) 0 else rotation.getAndIncrement() % global.size
			for (i in 0 until n) {
				val h = global[(start + i) % global.size]
				val key = proxyKeyOf(h.entry)
				if (key in droppedProxyKeys || key in bannedProxyKeys) continue
				chainTokens[key]?.let { out += RoutePlan.ViaChain(h, it) }
			}
		}
		return out
	}

	/** Verified-for-this-host proxies first (minus failed/dropped/banned),
	 *  then the global healthy set, small O(1) rotation. Challenged hosts
	 *  additionally get the chain-healthy routes appended as the second
	 *  choice (4.6.4). */
	private fun poolRoutesFor(host: String): List<RoutePlan> {
		val st = stateOf(host)
		val out = ArrayList<RoutePlan>(POOL_PLAN_CAP)
		synchronized(st) {
			for (h in st.verified) {
				if (out.size >= POOL_PLAN_CAP) break
				val key = proxyKeyOf(h.entry)
				if (key !in st.failedKeys && key !in droppedProxyKeys && key !in bannedProxyKeys) {
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
				if (key in failed || key in droppedProxyKeys || key in bannedProxyKeys) continue
				if (out.any { routeProxyKey(it) == key }) continue
				out += RoutePlan.ViaProxy(h)
			}
		}
		// challenged hosts get the chains appended AFTER the direct-pool
		// routes as the deeper option (4/7 mark)
		if (host in challengedHosts) {
			for (c in chainRoutesFor(host)) {
				if (out.size >= POOL_PLAN_CAP) break
				if (out.any { routeProxyKey(it) == routeProxyKey(c) }) continue
				out += c
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

	/** Item 5f: the always-use host list (suffix rules like the never-use
	 *  list; never-use is checked first in planFor and wins). */
	fun isListed(host: String, list: Set<String>): Boolean {
		if (list.isEmpty()) {
			return false
		}
		val h = host.lowercase()
		return h in list || list.any { h.endsWith(".$it") }
	}

	/**
	 * Never routed through the pool, no matter the mode or the switches:
	 * the wsrv.nl image proxy and the user's Cloudflare-worker relay
	 * (stop condition: the wsrv.nl feature is not touched by amendment 5).
	 * Semantics unchanged from the old controller.
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

	private fun isCloudflareCookie(nameLower: String): Boolean =
		CloudflareFilteringCookieJar.matches(nameLower)

	private fun logEvaluation(v: HostVerdict) {
		val parts = buildList {
			if (v.loginRule != null) add("login=${v.loginRule.label}")
			if (v.cfManaged) add("cf-managed")
			if (isEmpty()) add("plain")
		}
		val summary = parts.joinToString(",")
		val key = "${v.host}=$summary"
		// rule labels + counts + host only - NEVER cookie names/values/URLs
		val prev = lastEvalLog.put(v.host, key)
		if (prev == null || prev != key) {
			Log.i(TAG, "cookies host=${v.host} cookies=${v.cookieCount} $summary")
		}
		if (lastEvalLog.size > EVAL_LOG_CAP) {
			lastEvalLog.keys.firstOrNull { it != v.host }?.let { lastEvalLog.remove(it) }
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
					challengedHosts.remove(host) // direct 2xx clears the challenge mark
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

			is RoutePlan.ViaProxy, is RoutePlan.ViaChain -> {
				val key = proxyKeyOf(routeProxy(route).entry)
				if (failure == null) {
					proxyFailStreak.remove(key)
					stateOf(host).poolFirst = true
					touchManualPickSuccess(key)
				} else {
					if (route is RoutePlan.ViaChain && relay?.isRunning != true) {
						// relay died between attempt and report: rebuild it
						// (bind-time rebind) and take NO ledger mark - the
						// proxy is not at fault for our own lifecycle
						val gwNow = gateway
						val r = gwNow?.let { ensureRelay() }
						if (r != null && gwNow != null) {
							rebindChainSet(r, gwNow, chainHealthy)
						}
						Log.i(TAG, "chain attempt outcome ignored: relay was not running (restarted=${r != null})")
						return
					}
					val cls = classifyFailure(failure)
					val st = stateOf(host)
					synchronized(st) { st.failedKeys += key }
					val streak = (proxyFailStreak[key] ?: 0) + 1
					proxyFailStreak[key] = streak
					Log.i(TAG, "host=$host ${route.label()} failed ($cls) streak=$streak")
					if (cls == FailureClass.TLS) {
						// Amendment 5 item 4e: a certificate error through a
						// proxy is a BAN (user-cleared) - but only when the
						// client verifies certificates at all. With Always
						// ignore there is no ban reason.
						if (settingsRef?.poolCertChecks != PoolCertChecks.ALWAYS_IGNORE) {
							if (bannedProxyKeys.add(key)) {
								Log.w(TAG, "proxy banned after certificate error (host=$host); Clear bans re-enables it")
							}
						}
					}
					if (cls == FailureClass.TLS || streak >= PROXY_FAIL_DROP_STREAK) {
						// event-driven session drop until a successful pass
						// re-habilitates the proxy
						if (droppedProxyKeys.add(key)) {
							Log.w(
								TAG,
								"proxy dropped from plans after " +
									(if (cls == FailureClass.TLS) "TLS error" else "$streak failures") +
									" (host=$host)",
							)
						}
					}
					touchManualPickFailure(key, cls)
					ensureVerifiedAsync(host)
					if (remainingHealthy() == 0 && chainHealthy.isEmpty() && !status.refreshing) {
						// exhaustion event => one refresh pass (4.4)
						refreshAsync(force = false)
					}
				}
			}
		}
	}

	private fun routeProxy(route: RoutePlan): HealthyProxy = when (route) {
		is RoutePlan.ViaProxy -> route.proxy
		is RoutePlan.ViaChain -> route.proxy
		RoutePlan.Direct -> throw IllegalArgumentException("direct route has no proxy")
	}

	/** Route key for dedup scans over mixed-route plan lists (null for Direct). */
	private fun routeProxyKey(route: RoutePlan): String? = when (route) {
		is RoutePlan.ViaProxy -> proxyKeyOf(route.proxy.entry)
		is RoutePlan.ViaChain -> proxyKeyOf(route.proxy.entry)
		RoutePlan.Direct -> null
	}

	private fun RoutePlan.label(): String = when (this) {
		RoutePlan.Direct -> "direct"
		is RoutePlan.ViaProxy -> "pool attempt"
		is RoutePlan.ViaChain -> "chain attempt"
	}

	/** Amendment 5 6f hooks: manual picks/strikes arrive with commit c;
	 *  these no-op hooks keep the outcome path write-once. */
	private fun touchManualPickSuccess(key: String) = Unit

	private fun touchManualPickFailure(key: String, cls: FailureClass) = Unit

	// region relay / gateway plumbing (5/7 single-gateway chains; 10b opens
	// it to user-built N-hop chains and re-homes the static proxy as an
	// optional hop)

	/**
	 * The chain gateway from the CURRENT settings: the user's static proxy,
	 * when its type can act as a relay hop (HTTP / SOCKS4 / SOCKS5). HTTPS
	 * and MTPROTO static proxies cannot chain - the relay speaks plain
	 * CONNECT to its gateway - and are deliberately NOT coerced. Host-only
	 * logging; credentials ride the binding, never the log.
	 */
	private fun resolveChainGateway(s: AppSettings): ProxyChainRelay.GatewayBinding? {
		val scheme = when (s.proxyType) {
			ProxyType.HTTP -> ProxyListFetcher.Scheme.HTTP
			ProxyType.SOCKS4 -> ProxyListFetcher.Scheme.SOCKS4
			ProxyType.SOCKS5 -> ProxyListFetcher.Scheme.SOCKS5
			ProxyType.DIRECT -> return null
			else -> {
				Log.w(TAG, "static proxy type ${s.proxyType} is not chain-capable (no gateway binding)")
				return null
			}
		}
		val host = s.proxyAddress?.trim()?.takeUnless { it.isEmpty() } ?: return null
		val port = s.proxyPort.takeIf { it in 1..65535 } ?: return null
		return ProxyChainRelay.GatewayBinding(
			hop = ProxyChainRelay.Hop(scheme, host, port),
			username = s.proxyLogin,
			password = s.proxyPassword,
			sourceLabel = "static",
		)
	}

	private fun ensureRelay(): ProxyChainRelay? = synchronized(relayLock) {
		relay?.takeIf { it.isRunning } ?: ProxyChainRelay().also {
			it.start()
			relay = it
			Log.i(TAG, "relay started for chain bootstrap on port ${it.port}")
		}
	}

	/** Event-driven stop: chains dead AND no chain-capable route needs the
	 *  relay => its worker threads are freed (4.4 h semantics kept). */
	private fun maybeStopRelay() {
		if (chainHealthy.isNotEmpty()) {
			return
		}
		synchronized(relayLock) {
			relay?.takeIf { chainHealthy.isEmpty() }?.let {
				it.stop()
				relay = null
				synchronized(chainTokens) { chainTokens.clear() }
				Log.i(TAG, "relay stopped: no chains alive")
			}
		}
	}

	/** Amendment 5: the static proxy as an ordinary chain HOP (item 3b).
	 *  Type and credentials come from the ProxyProvider settings, read at
	 *  bind time - the relay never sees ProxyProvider itself. Returns null
	 *  when the static type can't act as a relay hop. */
	private fun staticProxyHop(s: AppSettings): ProxyChainRelay.Hop? {
		val scheme = when (s.proxyType) {
			ProxyType.HTTP -> ProxyListFetcher.Scheme.HTTP
			ProxyType.SOCKS4 -> ProxyListFetcher.Scheme.SOCKS4
			ProxyType.SOCKS5 -> ProxyListFetcher.Scheme.SOCKS5
			ProxyType.DIRECT -> return null
			else -> return null // HTTPS/MTPROTO can't chain (plain CONNECT to hop 1)
		}
		val host = s.proxyAddress?.trim()?.takeUnless { it.isEmpty() } ?: return null
		val port = s.proxyPort.takeIf { it in 1..65535 } ?: return null
		return ProxyChainRelay.Hop(scheme, host, port, s.proxyLogin, s.proxyPassword)
	}

	/** Bind (or re-bind) one assembled ROUTE at the relay under the entry
	 *  key's token; identical keys reuse the token. Returns the token. */
	private fun bindChainRoute(
		r: ProxyChainRelay,
		entry: ProxyListFetcher.ProxyEntry,
		route: ProxyChainRelay.Route,
	): String {
		val key = proxyKeyOf(entry)
		synchronized(chainTokens) {
			val token = chainTokens[key] ?: "c" + relayTokenSeq.getAndIncrement().toString(16)
			chainTokens[key] = token
			r.bindRouteToken(token, route)
			return token
		}
	}

	/** Reissue bindings for the new chain-healthy set; tokens of entries
	 *  that fell out are unbound unless still needed by a per-host
	 *  verified chain. */
	private fun rebindChainSet(
		r: ProxyChainRelay,
		newRoutes: Map<String, ProxyChainRelay.Route>,
	) {
		synchronized(chainTokens) {
			for ((key, route) in newRoutes) {
				val entry = chainsOfKeys[key] ?: continue
				val token = chainTokens[key] ?: "c" + relayTokenSeq.getAndIncrement().toString(16)
				chainTokens[key] = token
				r.bindRouteToken(token, route)
			}
			val stale = chainTokens.keys.filter { it !in newRoutes.keys }
			for (key in stale) {
				val token = chainTokens.remove(key) ?: continue
				r.bindRouteToken(token, null)
				chainsOfKeys.remove(key)
			}
		}
	}

	/** key -> entry, the inverse produced by buildChainCandidates. */
	private val chainsOfKeys = ConcurrentHashMap<String, ProxyListFetcher.ProxyEntry>()

	/**
	 * Build one assembled route per chain candidate (item 2): the candidate
	 * is the EXIT. Hop 1 (and possibly a middle hop) come from the policy:
	 * the static proxy when configured, otherwise found healthy proxies -
	 * rotated so parallel chains don't share hop 1. Length is clipped to
	 * 1..3 by the chainLength setting; every hop stays distinct by key
	 * (item 2b Amazon).
	 */
	private fun buildChainCandidates(
		entries: List<ProxyListFetcher.ProxyEntry>,
		healthy: List<HealthyProxy>,
		staticHop: ProxyChainRelay.Hop?,
		chainLength: Int,
		maxRoutes: Int,
	): Map<String, ProxyChainRelay.Route> {
		if (chainLength < 2 || entries.isEmpty()) return emptyMap()
		val healthyEntries = healthy.map { it.entry }.distinctBy { proxyKeyOf(it) }
		val out = LinkedHashMap<String, ProxyChainRelay.Route>(maxRoutes * 2)
		val start = if (healthyEntries.size <= 1) 0 else rotation.getAndIncrement() % healthyEntries.size
		var filled = 0
		entriesLoop@ for ((i, exit) in entries.withIndex()) {
			if (filled >= maxRoutes) break
			val hops = ArrayList<ProxyChainRelay.Hop>(3)
			if (staticHop != null) {
				hops += staticHop
			}
			// middle hops: found healthy proxies, distinct from the exit
			// and from each other (Auto-mode basis; selection rules layer in 10c)
			var added = 0
			var cursor = start
			while (hops.size < chainLength - 1 && added < healthyEntries.size) {
				val cand = healthyEntries[cursor % healthyEntries.size]
				cursor++
				added++
				if (proxyKeyOf(cand) == proxyKeyOf(exit)) continue
				if (staticHop != null && cand.host.equals(staticHop.host, true) && cand.port == staticHop.port) continue
				if (hops.any { it.host.equals(cand.host, true) && it.port == cand.port }) continue
				hops += ProxyChainRelay.Hop(cand.scheme, cand.host, cand.port)
			}
			if (hops.size < chainLength - 1) {
				// not enough distinct live middle hops for the requested
				// length: this length can not be honored right now (the
				// chain preview on the list surfaces this as its reason)
				continue
			}
			hops += ProxyChainRelay.Hop(exit.scheme, exit.host, exit.port)
			out[proxyKeyOf(exit)] = ProxyChainRelay.Route(hops)
			chainsOfKeys[proxyKeyOf(exit)] = exit
			filled++
		}
		return out
	}

	/**
	 * The static-proxy transport for the bootstrap's gateway fetch: the
	 * provider's selector + authenticator (read-only reuse; HTTPS-typed
	 * static proxies fetch fine here even though they can't chain). Null
	 * when no static proxy is configured. 10b re-aims this at the
	 * "fetch lists through a proxy" setting (any alive proxy, not only
	 * the static one).
	 */
	private fun gatewayTransportOrNull(base: OkHttpClient): OkHttpClient? {
		val s = settingsRef ?: return null
		if (s.proxyType == ProxyType.DIRECT) {
			return null
		}
		val addrOk = !s.proxyAddress.isNullOrBlank() && s.proxyPort in 1..65535
		if (!addrOk) {
			return null
		}
		val provider = proxyProviderRef?.get() ?: return null
		return base.newBuilder()
			.proxySelector(provider.selector)
			.proxyAuthenticator(provider.authenticator)
			.build()
	}

	/** Amendment 5 2d/2f: "a gateway is just the first hop that passes a
	 *  check, and the static proxy is only one possible source of it".
	 *  The list-fetch transport therefore prefers the static proxy when
	 *  configured, otherwise the fastest ALIVE pool proxy. */
	private fun fetchProxyTransportOrNull(base: OkHttpClient): OkHttpClient? {
		gatewayTransportOrNull(base)?.let { return it }
		val pick = status.healthy.firstOrNull {
			val key = proxyKeyOf(it.entry)
			key !in droppedProxyKeys && key !in bannedProxyKeys
		} ?: return null
		val type = when (pick.entry.scheme) {
			ProxyListFetcher.Scheme.HTTP -> Proxy.Type.HTTP
			ProxyListFetcher.Scheme.SOCKS4, ProxyListFetcher.Scheme.SOCKS5 -> Proxy.Type.SOCKS
		}
		val proxy = Proxy(type, InetSocketAddress.createUnresolved(pick.entry.host, pick.entry.port))
		return base.newBuilder()
			.proxySelector(object : ProxySelector() {
				override fun select(uri: URI?): List<Proxy> = listOf(proxy)
				override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
			})
			.proxyAuthenticator(Authenticator.NONE)
			.build()
	}

	// endregion

	// region refresh orchestration (event-driven entry points only)

	/**
	 * One candidate-gathering + health pass. Callers: the user's refresh
	 * action (force = true), pool-asked-for-an-empty-set, exhaustion. Never
	 * runs on a timer; re-entrance guarded by the mutex.
	 */
	fun refreshAsync(force: Boolean) {
		if (refreshMutex.isLocked && !force) {
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
				val chainGw = resolveChainGateway(s)
				gateway = chainGw

				// ---- list bootstrap (amendment 8 order; 10b re-aims the
				// gateway step at "Fetch lists through a proxy", item 2f):
				// 1) direct, 2) disk/mirrors (inside the fetcher), 3) one
				// proxy pass - through the static proxy if set, else the
				// fastest alive pool proxy. ALWAYS runs the proxy pass
				// first; NEVER rides direct only.
				val fetchMode = s.poolFetchLists
				var listsResult: ProxyListFetcher.Result? = null
				if (fetchMode == PoolFetchLists.ALWAYS) {
					fetchProxyTransportOrNull(base)?.let { transport ->
						Log.i(TAG, "list bootstrap: fetching lists through a proxy (ALWAYS setting)")
						listsResult = relabelProxyPass(
							ProxyListFetcher.fetchLists(transport, s.poolLists, s.poolMirrors, ctx.cacheDir),
						)
					}
				}
				if (listsResult == null || listsResult.entries.isEmpty()) {
					listsResult = ProxyListFetcher.fetchLists(
						// the cycle's transport is a HARD-LOCKED direct clone
						// of the base client: list downloads never cross the
						// pool (skipping this pass under ALWAYS requires a
						// proxy transport we did not have)
						base.newBuilder()
							.proxySelector(DIRECT_SELECTOR)
							.proxyAuthenticator(Authenticator.NONE)
							.build(),
						s.poolLists,
						s.poolMirrors,
						ctx.cacheDir,
					)
				}
				if (fetchMode == PoolFetchLists.IF_DIRECT_FAILS && listsResult.entries.isEmpty()) {
					fetchProxyTransportOrNull(base)?.let { transport ->
						Log.i(TAG, "list bootstrap: direct+disk+mirror produced 0 candidates; one pass through a proxy")
						listsResult = relabelProxyPass(
							ProxyListFetcher.fetchLists(transport, s.poolLists, s.poolMirrors, ctx.cacheDir),
						)
					}
				}
				candidates = listsResult.entries
				val report = ProxyHealthChecker.check(
					base, listsResult.entries, s.poolTestUrl, s.poolMaxHealthy, s.poolTimeoutDirectS,
				)
				if (report.error == null) {
					ProxyHealthChecker.saveToCache(ctx.cacheDir, report)
					droppedProxyKeys.clear() // fresh healthy set: session drops re-earn themselves
				}

				// ---- chain stage (amendment 5 open chains): one assembled
				// route per candidate - static proxy as an OPTIONAL hop 1,
				// found healthy proxies filling the rest. The end-to-end
				// route is what gets chained-probed through the relay.
				var chainReport: ProxyHealthChecker.HealthReport? = null
				if (s.poolChainLength >= 2 && listsResult.entries.isNotEmpty()) {
					val staticHop = staticProxyHop(s)
					val routeMap = buildChainCandidates(
						listsResult.entries,
						report.healthy,
						staticHop,
						s.poolChainLength,
						s.poolMaxHealthy * 3,
					)
					if (routeMap.isNotEmpty()) {
						val pairs = routeMap.mapNotNull { (key, route) ->
							chainsOfKeys[key]?.let { entry -> entry to route }
						}
						val r = ensureRelay()
						if (r != null) {
							chainReport = ProxyHealthChecker.checkOpenRoutes(
								r, base, pairs,
								Request.Builder().url(s.poolTestUrl).head().build(),
								s.poolMaxHealthy, s.poolTimeoutChainS,
							)
							chainHealthy = chainReport.healthy
							val healthyKeys = chainReport.healthy
								.mapTo(HashSet()) { proxyKeyOf(it.entry) }
							rebindChainSet(
								r,
								routeMap.filterKeys { it in healthyKeys },
							)
						}
					} else {
						chainHealthy = emptyList()
					}
				} else {
					chainHealthy = emptyList()
				}
				maybeStopRelay()

				val summary = buildSummary(listsResult, report, chainReport)
				status = Status(
					report.healthy, false, summary,
					chainsCount = chainHealthy.size,
					challengedCount = challengedHosts.size,
					gatewayLabel = if (chainGw != null) "static" else "none",
				)
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

	/** Provenance without honesty loss (amendment 8): a list pass that
	 *  crossed a proxy gets GATEWAY as its source label. */
	private fun relabelProxyPass(result: ProxyListFetcher.Result): ProxyListFetcher.Result =
		result.copy(
			reports = result.reports.map { rep ->
				if (rep.error == null) rep.copy(source = ProxyListFetcher.ListSource.GATEWAY) else rep
			},
		)

	private fun buildSummary(
		lists: ProxyListFetcher.Result,
		health: ProxyHealthChecker.HealthReport,
		chains: ProxyHealthChecker.HealthReport?,
	): String {
		val direct = lists.reports.count { it.error == null && it.source == ProxyListFetcher.ListSource.DIRECT }
		val disk = lists.reports.count { it.error == null && it.source == ProxyListFetcher.ListSource.DISK }
		val mirror = lists.reports.count { it.error == null && it.source == ProxyListFetcher.ListSource.MIRROR }
		val viaGateway = lists.reports.count { it.error == null && it.source == ProxyListFetcher.ListSource.GATEWAY }
		val failed = lists.reports.count { it.error != null }
		return buildString {
			append("${health.healthy.size} healthy of ${health.sampled} sampled ")
			append("(${health.candidatesTotal} candidates; lists: $direct direct/$disk disk/$mirror mirror")
			if (viaGateway > 0) {
				append("/$viaGateway gateway")
			}
			if (failed > 0) {
				append(", $failed failed")
			}
			append(")")
			if (chains != null) {
				append("; chains: ${chains.healthy.size} healthy of ${chains.sampled} sampled")
			}
		}
	}

	/** Human-readable status for the settings screen (event-only wording).
	 *  Categories: route healthy, relay chains, challenged hosts, gateway
	 *  state (the full screen reads them from Status fields). */
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
			if (status.chainsCount > 0) {
				append("; ${status.chainsCount} chains")
			}
			if (status.challengedCount > 0) {
				append("; ${status.challengedCount} challenged")
			}
			if (bannedProxyKeys.isNotEmpty()) {
				append("; ${bannedProxyKeys.size} banned")
			}
		}
	}

	// endregion

	// region host verification (event-triggered, never timed)

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
				val banned = bannedProxyKeys
				val dropped = droppedProxyKeys
				val verified = report.healthy.filter {
					val key = proxyKeyOf(it.entry)
					key !in banned && key !in dropped
				}
				synchronized(st) {
					st.verified = verified
					st.failedKeys.clear() // freshly verified: per-host ledger resets
				}
				Log.i(TAG, "host=$host verify: ${verified.size} proxies for the pool plan")
				// 4.6.4: a host whose direct proxies all fail gets its
				// dedicated pass through the OPEN chain verifier when a
				// relay is up; passing chains are relay-bound immediately
				// (bind-time, not plan-time)
				if (verified.isEmpty() && s.poolChainLength >= 2) {
					val r = relay?.takeIf { it.isRunning }
					if (r != null) {
						val staticHop = staticProxyHop(s)
						val routeMap = buildChainCandidates(
							snapshot, status.healthy, staticHop,
							s.poolChainLength, MAX_HOST_CHAIN_ROUTES,
						)
						val pairs = routeMap.mapNotNull { (key, route) ->
							chainsOfKeys[key]?.let { entry -> entry to route }
						}
						if (pairs.isNotEmpty()) {
							val chainReport = ProxyHealthChecker.verifyOpenRoutesForHost(
								r, base, pairs, host, s.poolTimeoutChainS,
							)
							val chains = chainReport.healthy.filter {
								val key = proxyKeyOf(it.entry)
								key !in banned && key !in dropped
							}
							for (h in chains) {
								routeMap[proxyKeyOf(h.entry)]
									?.let { route -> bindChainRoute(r, h.entry, route) }
							}
							synchronized(st) {
								st.verifiedChains = chains
							}
							Log.i(TAG, "host=$host chain verify: ${chains.size} chains for the chain plan")
						}
					}
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
			} finally {
				st.verifying = false
			}
		}
	}

	private fun remainingHealthy(): Int =
		status.healthy.count {
			val key = proxyKeyOf(it.entry)
			key !in droppedProxyKeys && key !in bannedProxyKeys
		}

	/**
	 * A pooled route answered with a Cloudflare challenge (4/7, detected by
	 * PoolChallengeDetector - header first, capped-body helper fallback).
	 * The host is marked challenged (pool-first ordering), and the
	 * offending proxy is skipped FOR THIS HOST only: it answered well at
	 * transport level, the exit IP is what got challenged.
	 */
	fun reportChallenge(host: String, route: RoutePlan) {
		val proxy = when (route) {
			is RoutePlan.ViaProxy -> route.proxy
			is RoutePlan.ViaChain -> route.proxy
			RoutePlan.Direct -> return
		}
		val key = proxyKeyOf(proxy.entry)
		val st = stateOf(host)
		synchronized(st) { st.failedKeys += key }
		if (challengedHosts.add(host)) {
			Log.i(TAG, "host=$host answered a Cloudflare challenge via pool route: marked challenged")
		}
		ensureVerifiedAsync(host)
	}

	fun isChallenged(host: String): Boolean = host in challengedHosts

	/** How many proxies are currently banned by certificate errors
	 *  (item 4e). Clear-bans action lands with the list screen (10d). */
	fun bannedCount(): Int = bannedProxyKeys.size

	/** Clear-bans re-enables every cert-banned proxy (item 4e). */
	fun clearBans() {
		if (bannedProxyKeys.isNotEmpty()) {
			val n = bannedProxyKeys.size
			bannedProxyKeys.clear()
			Log.i(TAG, "user cleared $n certificate bans")
		}
	}

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

	private fun proxyKeyOf(entry: ProxyListFetcher.ProxyEntry): String = entry.toString()

	// endregion

	// region pooled clients (amendment 4)

	/**
	 * The pooled client for one route and one tier: tierClient.newBuilder()
	 * with a pinned route selector, shared interceptors minus CloudFlare and
	 * the routing interceptor itself, explicit Authenticator.NONE (relay
	 * routes carry the relay secret instead), the Cloudflare-cookie policy
	 * from the amendment-5 setting, and the amendment-5 certificate policy
	 * (applyPoolTls). LRU of 32, keyed by (tier identity, route).
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
				.cookieJar(poolCookieJarFor(tier))
				.apply {
					interceptors().removeAll {
						it is PoolRoutingInterceptor || it is CloudFlareInterceptor
					}
				}
				.applyPoolTls()
				.build()
			pooledClients[key] = client
			return client
		}
	}

	/**
	 * The pooled CHAIN client for one token-bound chain route - same
	 * derivation rules as [pooled], but pinned to the loopback relay and
	 * carrying the relay secret + chain token in its proxy authenticator
	 * (per-route client, never per-request; amendment 4). A restarted relay
	 * gets a new port, so the port is part of the LRU key.
	 */
	fun pooledChain(tier: OkHttpClient, token: String): OkHttpClient? {
		val port = relayPortOrNull() ?: return null
		val authHeader = relayAuthHeaderOrNull() ?: return null
		val key = System.identityHashCode(tier) to "chain:$token@$port"
		synchronized(pooledLock) {
			pooledClients[key]?.let { return it }
			val relayProxy = Proxy(
				Proxy.Type.HTTP,
				InetSocketAddress(InetAddress.getLoopbackAddress(), port),
			)
			val selector = object : ProxySelector() {
				override fun select(uri: URI?): List<Proxy> = listOf(relayProxy)
				override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
			}
			val authenticator = Authenticator { _, response ->
				val request = response.request
				if (request.header("Proxy-Authorization") != null) {
					// relay already challenged this connection once: never loop
					null
				} else {
					request.newBuilder()
						.header("Proxy-Authorization", authHeader)
						.header(ProxyChainRelay.HEADER_CHAIN_TOKEN, token)
						.build()
				}
			}
			val client = tier.newBuilder()
				.proxySelector(selector)
				.proxyAuthenticator(authenticator)
				.cookieJar(poolCookieJarFor(tier))
				.apply {
					interceptors().removeAll {
						it is PoolRoutingInterceptor || it is CloudFlareInterceptor
					}
				}
				.applyPoolTls()
				.build()
			pooledClients[key] = client
			return client
		}
	}

	/** Amendment 5 item 5d: the Cloudflare-cookie filter is a SETTING now.
	 *  STRIP keeps the amendment-5 filtering jar (direct path is the only
	 *  writer of CF cookies); SEND passes the shared jar through for
	 *  Cloudflare names too. Ordinary cookie behavior is handled by the
	 *  interceptor's Cookie-header modes, not the jar. */
	private fun poolCookieJarFor(tier: OkHttpClient) =
		when (settingsRef?.poolCfCookies ?: PoolCfCookies.STRIP) {
			PoolCfCookies.STRIP -> CloudflareFilteringCookieJar(tier.cookieJar)
			PoolCfCookies.SEND -> tier.cookieJar
		}

	/** Task C 4.10.2 / amendment 5 item 4: pooled and chain clients get
	 *  their TLS policy from ONE helper. FOLLOW_APP inherits the tier
	 *  setup whole - trust-all included when the app SSL bypass is on
	 *  (the pool is NEVER inert because of it; amendment 5 4a). ALWAYS_VERIFY
	 *  runs the same installExtraCertificates helper as the base client.
	 *  ALWAYS_IGNORE reuses the app's disableCertificateVerification helper
	 *  on the pooled client only. No existing cert code is edited; no
	 *  second trust-all exists. A GC'd context skips the extras install
	 *  and keeps the tier's inherited sockets. */
	private fun OkHttpClient.Builder.applyPoolTls(): OkHttpClient.Builder {
		when (settingsRef?.poolCertChecks ?: PoolCertChecks.FOLLOW_APP) {
			PoolCertChecks.FOLLOW_APP -> Unit // inherit by design
			PoolCertChecks.ALWAYS_VERIFY -> contextRef?.get()?.let { installExtraCertificates(it) }
			PoolCertChecks.ALWAYS_IGNORE -> disableCertificateVerification()
		}
		return this
	}

	fun relayPortOrNull(): Int? = relay?.takeIf { it.isRunning }?.port

	fun relayAuthHeaderOrNull(): String? = relay?.takeIf { it.isRunning }?.buildAuthHeader()

	private val DIRECT_PLAN = listOf(RoutePlan.Direct)
}
