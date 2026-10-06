package org.wastaken.kotatsu.api21.core.network.proxypool

import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyListFetcher.ProxyEntry
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyListFetcher.Scheme
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Operating mode of the proxy pool (pref value = enum name).
 *
 * The mode is read when the OkHttp clients are BUILT, because the routing
 * interceptor is either installed or not: switching between OFF and any other
 * mode needs an app restart (the settings screen says so). Switching between
 * FALLBACK and ALWAYS is read per request and applies live.
 */
enum class PoolMode {
	/** Default. Nothing from the pool is installed at all. */
	OFF,

	/** The existing route is tried first; pool routes are the fallback. */
	FALLBACK,

	/** Pool routes first; the existing route is only the fail-open tail. */
	ALWAYS,
}

/**
 * Which part of the app a request belongs to, decided by the client tier the
 * routing interceptor was installed on - not by inspecting the request, which
 * cannot tell a cover load from a backup upload.
 */
enum class PoolCategory {
	/** Parser requests, listings, pages and the Coil image tier. */
	SOURCES_IMAGES,

	/** Playback and cover/video fetches on the @VideoHttpClient tier. */
	VIDEO,

	/** Everything on the base tier: updates, scrobbling, sync, backups. */
	APP_SERVICES,
}

/** What a pooled client is built for; [key] is the pooled-client cache key. */
sealed class PoolRoute {

	abstract val key: String

	abstract val proxy: Proxy

	/**
	 * Relay secret, present ONLY for relay routes. OkHttp sends it preemptively
	 * through the route's proxyAuthenticator, so the relay sees it on the very
	 * first CONNECT instead of after a 407 round trip.
	 */
	abstract val relayCredential: String?

	/** One proxy, dialed directly. */
	data class Single(val endpoint: ProxyEndpoint) : PoolRoute() {
		override val key: String get() = "single ${endpoint.key}"
		override val proxy: Proxy get() = endpoint.toProxy()
		override val relayCredential: String? get() = null
	}

	/** The local relay, which chains gateway -> main proxy itself. */
	class RelayRoute(val port: Int, private val credential: String) : PoolRoute() {
		override val key: String get() = "relay 127.0.0.1:$port"
		override val proxy: Proxy
			get() = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", port))
		override val relayCredential: String? get() = credential

		override fun toString(): String = key
	}
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
 * One proxy hop.
 *
 * [host] may be a name; it is deliberately kept unresolved so the hop can hand
 * the name to whoever resolves it (the JDK for the first hop, the upstream
 * proxy for every later hop - OkHttp does the same, it builds
 * `InetSocketAddress.createUnresolved` for SOCKS routes and passes the name
 * verbatim in a CONNECT request for HTTP routes).
 *
 * Carries no credentials: pool proxies are anonymous, and [toString] is what
 * ends up in logs, so it must stay a bare host/port/scheme triple.
 */
data class ProxyEndpoint(val scheme: Scheme, val host: String, val port: Int) {

	val key: String get() = "$scheme $host:$port"

	override fun toString(): String = key
}

/** A two-proxy chain: client -> [gateway] -> [main] -> target. */
data class ChainRoute(val gateway: ProxyEndpoint, val main: ProxyEndpoint) {

	val key: String get() = "${gateway.key} -> ${main.key}"
}

/**
 * Which hop of a chain failed. Logged with host names only - the log must show
 * that a Tor exit policy (or any other gateway restriction) is what refused the
 * main proxy, without ever printing addresses, paths or credentials.
 */
enum class HopFailure(val logLabel: String) {
	/** The gateway proxy could not be reached at all. */
	GATEWAY_UNREACHABLE("gateway unreachable"),

	/** The gateway answered but refused to open the tunnel to the main proxy. */
	GATEWAY_REFUSED_MAIN("gateway refused to connect to the main proxy"),

	/** The main proxy never answered its own handshake. */
	MAIN_HANDSHAKE_FAILED("main proxy handshake failed"),

	/** Both proxies answered, but the chain could not reach the target. */
	TARGET_CONNECT_FAILED("target connect failed through the chain"),
}

/**
 * A route OkHttp can dial. The address stays UNRESOLVED on purpose: for SOCKS
 * routes OkHttp resolves it anyway (`RouteSelector` builds
 * `createUnresolved` for `Proxy.Type.SOCKS`), and for HTTP routes the name only
 * ever appears inside the CONNECT authority, which the proxy resolves.
 */
fun ProxyEndpoint.toProxy(): Proxy = Proxy(
	when (scheme) {
		Scheme.HTTP -> Proxy.Type.HTTP
		Scheme.SOCKS4, Scheme.SOCKS5 -> Proxy.Type.SOCKS
	},
	InetSocketAddress.createUnresolved(host, port),
)

fun ProxyEntry.toEndpoint(): ProxyEndpoint = ProxyEndpoint(scheme, host, port)
