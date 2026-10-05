package org.wastaken.kotatsu.api21.core.network.proxypool

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyHealthChecker.HealthReport
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyHealthChecker.HealthyProxy
import org.wastaken.kotatsu.api21.core.network.proxy.ProxyType
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

/** Operating mode of the experimental proxy pool (pref value = enum name). */
enum class PoolMode {
	/** Default. The pool selector behaves as if not installed. */
	OFF,

	/** Direct route is tried first; pool routes are offered as fallback. */
	FALLBACK,

	/** Pool routes are offered first and direct is only the fail-open tail. */
	ALWAYS,
}

const val POOL_DEFAULT_MAX_HEALTHY = 20
const val POOL_MAX_HEALTHY_CAP = 100

/** HEAD-probed through each candidate during health checks; must answer HEAD with 2xx. */
const val POOL_DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

/** Verified 2026-10-04 (list formats + line counts + freshness in the 1/5 commit message). */
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

object ProxyPoolController {

	private const val TAG = "ProxyPool"
	private const val MARK_WINDOW_MS = 10 * 60_000L
	private const val MARK_TTL_MS = 10 * 60_000L
	private const val MARK_RESET_THRESHOLD = 2
	private const val CERT_BAN_TTL_MS = 24 * 60 * 60_000L
	private const val EVAL_LOG_CAP = 64

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

	/** Verdict for one host on one pool evaluation. */
	data class HostVerdict(
		val host: String,
		/** non-null => host must stay DIRECT (spec 3.6) */
		val loginRule: LoginRule?,
		/** Cloudflare cookies present: proxied requests get cf_* stripped (amendment: pool still allowed) */
		val hasCloudflare: Boolean,
		val cookieCount: Int,
	)

	private data class HostMark(val untilMs: Long, val reason: String, val failures: Int)

	/** Snapshot consumed by the selector and the settings status line. */
	data class Status(
		val healthy: List<HealthyProxy>,
		val checkedAtMs: Long,
		val refreshing: Boolean,
		val lastSummary: String,
	)

	@Volatile
	private var settingsRef: AppSettings? = null

	@Volatile
	private var contextRef: WeakReference<Context>? = null

	@Volatile
	private var baseClientRef: WeakReference<OkHttpClient>? = null

	@Volatile
	var status = Status(emptyList(), 0L, false, "not initialized")
		private set

	private val marks = ConcurrentHashMap<String, HostMark>()
	private val resetEvents = ConcurrentHashMap<String, ArrayDeque<Long>>()
	private val certBans = ConcurrentHashMap<String, Long>() // proxyKey -> bannedUntilMs
	private val proxyFailStreak = ConcurrentHashMap<String, Int>()
	private val lastEvalLog = ConcurrentHashMap<String, String>()
	private val rotation = AtomicInteger(0)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val refreshMutex = Mutex()

	/**
	 * Called once from NetworkModule when the base client is built (single hook).
	 * Loads persisted healthy entries (<=30 min old) so the pool is usable on
	 * cold start without waiting for a refresh.
	 */
	fun attach(context: Context, settings: AppSettings, baseClient: OkHttpClient) {
		contextRef = WeakReference(context.applicationContext)
		settingsRef = settings
		baseClientRef = WeakReference(baseClient)
		scope.launch {
			val cached = ProxyHealthChecker.loadFromCache(context.applicationContext.cacheDir)
			if (cached != null) {
				status = Status(cached, System.currentTimeMillis(), false, "loaded ${cached.size} cached entries")
				Log.i(TAG, "cold start: ${cached.size} cached healthy entries")
			}
		}
	}

	/**
	 * Amendment 1+3: the pool is INERT when
	 *  - the user has a static proxy configured (proxyType != DIRECT), or
	 *  - SSL bypass (accept-all) is enabled.
	 * Plus DEFAULT OFF. Inert = selector returns the delegate's list verbatim.
	 */
	fun isInert(): Boolean {
		val s = settingsRef ?: return true
		if (s.poolMode == PoolMode.OFF) {
			return true
		}
		if (s.proxyType != ProxyType.DIRECT || s.isSSLBypassEnabled) {
			return true
		}
		return false
	}

	// region per-request evaluation (cheap, no I/O)

	/** Routes for one host, already ordered per mode and learning. Caller handles OFF/inert. */
	fun routesFor(host: String, mode: PoolMode): List<Proxy> {
		val healthy = healthyProxies()
		val marked = markFor(host) != null
		return when (mode) {
			PoolMode.OFF -> DIRECT_LIST
			PoolMode.FALLBACK -> if (marked || healthy.isEmpty()) {
				// learned stickiness: pool first for the marked host (spec 5.3);
				// direct tail kept as last resort
				healthy + DIRECT_LIST
			} else {
				DIRECT_LIST + healthy // spec 3.2: direct-first fallback ordering
			}

			PoolMode.ALWAYS -> healthy.ifEmpty {
				Log.w(TAG, "ALWAYS mode with empty pool: opening direct route as fail-open tail")
				DIRECT_LIST
			}
		}
	}

	/** Cookie-based verdict. Reads the SHARED jar snapshot; never mutates it. */
	fun cookieVerdict(host: String, cookieJar: CookieJar): HostVerdict {
		val url = "https://$host/".toHttpUrlOrNull()
		val cookies: List<Cookie> = if (url != null) {
			runCatching { cookieJar.loadForRequest(url) }.getOrElse { emptyList() }
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

	// endregion — never store cookie names/values from the jar: the logEvaluation
	// call above is the ONLY point that observes verdicts, and it logs the matched
	// RULE LABEL + cookie count + host (amendment 4).

	// region failure learning (spec 5.3/5.4)

	/** Connection-level classification used by selector + interceptor. */
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

	/** A DIRECT route to [host] failed with a reset-class error. */
	fun reportHostReset(host: String) {
		val now = System.currentTimeMillis()
		val dq = resetEvents.getOrPut(host) { ArrayDeque(4) }
		synchronized(dq) {
			while (true) {
				val first = dq.peekFirst() ?: break
				if (first < now - MARK_WINDOW_MS) dq.removeFirst() else break
			}
			dq.addLast(now)
			val n = dq.size
			if (n >= MARK_RESET_THRESHOLD && markFor(host) == null) {
				marks[host] = HostMark(now + MARK_TTL_MS, "resets=$n", n)
				Log.i(TAG, "host=$host marked pool-first for 10min (resets=$n in 10min)")
			}
		}
	}

	/** Ban a proxy for 24h after a cert/TLS error seen through it (spec 5.4). */
	fun reportCertBan(proxyKey: String, host: String) {
		certBans[proxyKey] = System.currentTimeMillis() + CERT_BAN_TTL_MS
		Log.w(TAG, "proxy banned 24h after TLS error via proxy for host=$host")
	}

	fun reportProxyFailure(proxyKey: String, cls: FailureClass, host: String) {
		val n = (proxyFailStreak[proxyKey] ?: 0) + 1
		proxyFailStreak[proxyKey] = n
		Log.i(TAG, "proxy fail ($cls) for host=$host streak=$n")
		// dying proxies self-purge on the next refresh; pessimistic early purge
		// keeps the selector list short in the meantime
		if (n >= 5) {
			status.healthy.find { proxyKeyOf(it.entry) == proxyKey }?.let { dead ->
				status = status.copy(healthy = status.healthy - dead)
				Log.i(TAG, "proxy purged from active set after $n consecutive failures")
			}
		}
	}

	fun reportProxySuccess(proxyKey: String) {
		proxyFailStreak.remove(proxyKey)
	}

	fun markFor(host: String): HostMark? {
		val m = marks[host] ?: return null
		return if (m.untilMs > System.currentTimeMillis()) m else {
			marks.remove(host, m)
			null
		}
	}

	// endregion

	// region refresh orchestration

	/** Cheap lazily-triggered refresh from the selector path. */
	fun maybeRefreshAsync() {
		if (isInert()) {
			return
		}
		val age = System.currentTimeMillis() - status.checkedAtMs
		if (status.healthy.isEmpty() || age > ProxyHealthChecker.STATE_MAX_AGE_MS) {
			refreshAsync(force = false)
		}
	}

	fun refreshAsync(force: Boolean) {
		if (!force && !refreshMutex.tryLock()) {
			return
		}
		val s = settingsRef ?: return
		val client = baseClientRef?.get() ?: return
		val ctx = contextRef?.get() ?: return
		val lists = s.poolLists
		val testUrl = s.poolTestUrl
		val maxHealthy = s.poolMaxHealthy
		scope.launch {
			if (force) {
				refreshMutex.lock()
			}
			try {
				status = status.copy(refreshing = true)
				val (parsed, report) = ProxyHealthChecker.refresh(client, lists, testUrl, maxHealthy)
				if (report.error == null) {
					ProxyHealthChecker.saveToCache(ctx.cacheDir, report)
				}
				val summary = buildSummary(parsed.reports, report)
				status = Status(report.healthy, if (report.checkedAtMs > 0) report.checkedAtMs else System.currentTimeMillis(), false, summary)
				Log.i(TAG, "refresh done: $summary")
			} catch (e: Exception) {
				e.printStackTraceDebug()
				status = status.copy(refreshing = false, lastSummary = "refresh failed: ${e.javaClass.simpleName}")
			} finally {
				refreshMutex.unlock()
			}
		}
	}

	private fun buildSummary(reports: List<ProxyListFetcher.ListReport>, health: HealthReport): String {
		val ok = reports.count { it.error == null }
		val failed = reports.size - ok
		return "${health.healthy.size} healthy of ${health.sampled} sampled " +
			"(${health.candidatesTotal} entries, $ok lists OK/$failed failed)"
	}

	/** Human-readable status for the settings screen. */
	fun statusLine(mode: PoolMode): String = when (mode) {
		PoolMode.OFF -> "Off"
		else -> buildString {
			append(status.lastSummary)
			val ageMin = (System.currentTimeMillis() - status.checkedAtMs) / 60_000L
			if (status.checkedAtMs > 0) {
				append(", ").append(if (ageMin <= 0) "just now" else "$ageMin min ago")
			}
			if (status.refreshing) {
				append(" (refreshing...)")
			}
		}
	}

	// endregion

	private fun healthyProxies(): List<Proxy> {
		val now = System.currentTimeMillis()
		val all = status.healthy
		if (all.isEmpty()) {
			return emptyList()
		}
		val start = if (all.size <= 1) 0 else rotation.getAndIncrement() % all.size
		val out = ArrayList<Proxy>(all.size)
		for (i in 0 until all.size) {
			val h = all[(start + i) % all.size]
			val key = proxyKeyOf(h.entry)
			val bannedUntil = certBans[key]
			if (bannedUntil != null && bannedUntil > now) {
				continue
			}
			out += Proxy(
				when (h.entry.scheme) {
					ProxyListFetcher.Scheme.HTTP -> Proxy.Type.HTTP
					ProxyListFetcher.Scheme.SOCKS4, ProxyListFetcher.Scheme.SOCKS5 -> Proxy.Type.SOCKS
				},
				InetSocketAddress.createUnresolved(h.entry.host, h.entry.port),
			)
		}
		return out
	}

	fun proxyKeyOf(entry: ProxyListFetcher.ProxyEntry): String = "host=${entry.host} port=${entry.port}"

	/** Stable key for a route-level proxy (matching proxyKeyOf for pool entries). */
	fun keyOfProxyAddress(sa: java.net.SocketAddress?): String? {
		return (sa as? InetSocketAddress)?.let { "host=${it.hostString} port=${it.port}" }
	}

	private fun logEvaluation(v: HostVerdict) {
		val line = when {
			v.loginRule != null -> "excluded=${v.loginRule.logLabel} cookies=${v.cookieCount}"
			v.hasCloudflare -> "cleared (cf cookies stripped when proxied) cookies=${v.cookieCount}"
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

	companion object {
		private val DIRECT_LIST = listOf(Proxy.NO_PROXY)

		/**
		 * Cloudflare-managed cookies: never sent through pool proxies (the proxy
		 * would impersonate the cleared IP), but the HOST stays pool-eligible
		 * (user amendment superseding the original "direct only" rule).
		 */
		fun isCloudflareCookie(nameLower: String): Boolean =
			nameLower.startsWith("cf_") || nameLower.startsWith("__cf")
	}
}
