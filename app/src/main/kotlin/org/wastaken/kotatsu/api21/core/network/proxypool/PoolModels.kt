package org.wastaken.kotatsu.api21.core.network.proxypool

import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyListFetcher.ProxyEntry
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyListFetcher.Scheme
import java.net.InetSocketAddress
import java.net.Proxy

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
