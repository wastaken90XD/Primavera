package org.wastaken.kotatsu.api21.core.network.proxypool

import android.util.Base64
import android.util.Log
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proxy pool (experimental) - the local chaining relay (Task C 4.7).
 *
 * OkHttp cannot chain proxies, and SOCKS support stops at one hop, so bi-proxy
 * chains are built by this tiny loopback server. OkHttp points at it as ONE
 * ordinary HTTP proxy; for each CONNECT target the relay opens the chain:
 *
 *   gateway (static | bootstrap | found; TCP)
 *     -> gateway handshake (HTTP CONNECT | SOCKS4a | SOCKS5) to the MAIN proxy
 *       -> main proxy handshake through that tunnel to the TARGET host
 *   -> "200 Connection established" to OkHttp, which then runs TLS to the
 *      target through the whole tunnel with normal TrustManager checks.
 *
 * Target host names are never resolved here: they travel unresolved into the
 * handshakes (SOCKS4a / SOCKS5 ATYP=3 / CONNECT), so remote DNS happens at the
 * proxies (OkHttp 4.12.0 file-level verified for SOCKS routes, cp. relay
 * facts report).
 *
 * Chains are bound by TOKEN, not by target host (2/7 revision): the CONNECT
 * request may carry an `X-Pool-Chain-Id: <token>` header (only accessible via
 * the pooled client's proxyAuthenticator, which is the only OkHttp hook that
 * lets us set CONNECT headers). A token binder maps each token to one
 * (gateway, main) bundle, which is what lets the health checker probe six
 * chains with six DIFFERENT main proxies in parallel over one relay without
 * those probes stomping on each other's bindings. A CONNECT whose token has
 * no live bundle is refused with NO_CHAIN.
 *
 * Amendment-8 hardening, all in this class:
 *  - binds ONLY to 127.0.0.1;
 *  - accepts ONLY the CONNECT method, any other request line is closed
 *    unread-beyond-8KB;
 *  - every CONNECT must carry Proxy-Authorization matching a per-run random
 *    32-byte secret, compared in CONSTANT TIME; the secret lives in memory
 *    only, never logged, never written to disk (buildAuthHeader() gives the
 *    pooled client's authenticator the one string it needs);
 *  - bounded executor of at most MAX_WORKERS (16) threads, 8 KB pipe buffers;
 *  - no timers anywhere: the relay closes when either side closes (4.4 h),
 *    and stop() is called by the controller on pool-off / shutdown hooks.
 *
 * Hop-level failure classes (amendment 9), logged under tag ProxyPool with
 * host names only: GATEWAY_UNREACHABLE, GATEWAY_REFUSED (the gateway
 * refused to open a tunnel to the main proxy - Tor exit policies refuse
 * many public-proxy ports and this class makes it visible),
 * MAIN_HANDSHAKE_FAILED, TARGET_CONNECT_FAILED.
 */
class ProxyChainRelay(
	private val connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_S,
) {

	/** A single hop address the relay can drive through any supported
	 *  handshake. Credentials stay hop-local (amendment 5: manual picks may
	 *  carry them, the static-proxy hop reads them from ProxyProvider
	 *  settings at bind time and passes them here). They are never logged. */
	data class Hop(
		val scheme: ProxyListFetcher.Scheme,
		val host: String,
		val port: Int,
		val login: String? = null,
		val password: String? = null,
	)

	/** Active gateway (legacy single-gateway shape): found/bootstrap hops
	 *  have no credentials; the static gateway reads login/password from
	 *  ProxyProvider settings at bind time. Kept for the 5/7 callers; the
	 *  open-chain engine composes Routes directly. */
	data class GatewayBinding(
		val hop: Hop,
		val username: String?,
		val password: String?,
		val sourceLabel: String, // "static" | "bootstrap" | "found" (status line)
	)

	/** Legacy single-gateway bundle = the static+main two-hop route. */
	data class ChainBundle(
		val gateway: GatewayBinding,
		val main: Hop,
	) {
		fun toRoute(): Route = Route(
			listOf(
				gateway.hop.copy(login = gateway.username, password = gateway.password),
				main,
			),
		)
	}

	/**
	 * Amendment 5 (open chains, item 2): one token => one ordered route.
	 * 1 hop: client -> relay -> hop1 -> target. N hops: client -> relay ->
	 * hop1 -> ... -> hopN -> target. Hop 1 is the entry (the old "gateway"
	 * became just one possible entry); the LAST hop is the exit and is
	 * handed the target HOST NAME unresolved (amendment 5 2e). No step
	 * requires a static proxy - hops can come from anywhere the policy
	 * engine picks them.
	 */
	data class Route(
		val hops: List<Hop>,
	) {
		init {
			require(hops.isNotEmpty()) { "open chains need at least one hop" }
		}

		/** Host names only, in order (logging rule: never URLs/creds). */
		fun display(): String = hops.joinToString(",") { it.host }
	}

	/** Hop-level failure classes (amendment 5 item 8: the log names which
	 *  hop failed - the index travels with the exception). */
	enum class HopFailure(val logLabel: String) {
		HOP_UNREACHABLE("hop unreachable"),
		HOP_TUNNEL_REFUSED("hop refused tunnel to the next hop"),
		HOP_HANDSHAKE_FAILED("hop handshake failed"),
		TARGET_CONNECT_FAILED("target connect failed through the chain"),
		NO_CHAIN("no chain bound for token"),
		INTERNAL("relay internal error"),
	}

	private class ChainException(
		val failure: HopFailure,
		val hopIndex: Int,
		detail: String,
	) : Exception(detail)

	private val chainsByToken = ConcurrentHashMap<String, Route>()
	private val openSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

	@Volatile
	var port: Int = -1
		private set

	@Volatile
	private var serverSocket: ServerSocket? = null

	private val running = AtomicBoolean(false)
	private val secret = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
	private val authHeader = "Basic " + Base64.encodeToString(
		"pool:".toByteArray(Charsets.ISO_8859_1) + secret,
		Base64.NO_WRAP,
	)

	private val workerCount = AtomicInteger(0)
	private val executor = ThreadPoolExecutor(
		0, MAX_WORKERS, 0L, TimeUnit.MILLISECONDS,
		LinkedBlockingQueue(),
		ThreadFactory { r ->
			Thread(r, "proxy-chain-relay").apply { isDaemon = true }
		},
	)

	val isRunning: Boolean get() = running.get()

	/** The one header the pooled client's authenticator must attach (amendment 8:
	 *  this string is never logged, and the authenticator lives only on the
	 *  pooled relay client). */
	fun buildAuthHeader(): String = authHeader

	fun start(): Int {
		if (running.compareAndSet(false, true)) {
			val server = ServerSocket()
			server.reuseAddress = true
			server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
			serverSocket = server
			port = server.localPort
			executor.execute { acceptLoop(server) }
			Log.i(TAG, "relay started on loopback")
		}
		return port
	}

	/** Binds (token -> ordered route) so one CONNECT can name its chain;
	 *  null clears. Amendment 5: NEW open-chain API. */
	fun bindRouteToken(token: String, route: Route?) {
		if (route == null) {
			chainsByToken.remove(token)
		} else {
			chainsByToken[token] = route
		}
	}

	/** Legacy binding (5/7 callers): converts the gateway+main bundle into
	 *  an ordered Route. */
	fun bindChainToken(token: String, bundle: ChainBundle?) {
		bindRouteToken(token, bundle?.toRoute())
	}

	fun clearChainTokens() {
		chainsByToken.clear()
	}

	/** Stops the accept loop and closes every live tunnel (4.4 h semantics). */
	fun stop() {
		if (running.compareAndSet(true, false)) {
			runCatching { serverSocket?.close() }
			serverSocket = null
			port = -1
			openSockets.forEach { runCatching { it.close() } }
			openSockets.clear()
			executor.shutdownNow()
			Log.i(TAG, "relay stopped")
		}
	}

	// region accept + CONNECT handling

	private fun acceptLoop(server: ServerSocket) {
		while (running.get()) {
			val socket = try {
				server.accept()
			} catch (e: Exception) {
				if (running.get()) {
					e.toString() // unread; loop ends on server close
				}
				return
			}
			executor.execute { runCatching { handleClient(socket) } }
		}
	}

	private fun handleClient(client: Socket) {
		track(client)
		try {
			client.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
			val input = client.getInputStream()
			val line = readConnectLine(input) ?: return closeQuiet(client, "non-CONNECT request line")
			val target = parseConnectTarget(line) ?: return closeQuiet(client, "bad CONNECT target")
			val headers = readConnectHeaders(input)
				?: return closeQuiet(client, "bad CONNECT headers")
			if (!checkRelayAuth(headers)) {
				// constant-time compare happens inside; wrong secret = silent close
				return closeQuiet(client, "relay auth missing/mismatch")
			}
			val route = headers[HEADER_CHAIN_TOKEN]?.let { chainsByToken[it] }
				?: run {
					log(target.host, null, HopFailure.NO_CHAIN, -1)
					return closeQuiet(client, "no chain bound for token")
				}
			val upstream = Socket()
			track(upstream)
			try {
				openChain(upstream, route, target)
			} catch (ce: ChainException) {
				log(target.host, route, ce.failure, ce.hopIndex)
				closeQuiet(upstream, ce.failure.logLabel)
				return closeQuiet(client, ce.failure.logLabel)
			} catch (ioe: Exception) {
				log(target.host, route, HopFailure.INTERNAL, -1)
				closeQuiet(upstream, "internal")
				return closeQuiet(client, "internal")
			}
			// chain is up: answer, then pipe both directions
			client.soTimeout = 0
			upstream.soTimeout = 0
			client.getOutputStream().write(RESPONSE_OK.toByteArray(Charsets.US_ASCII))
			client.getOutputStream().flush()
			val down = Thread({
				runCatching { pipe(upstream.getInputStream(), client.getOutputStream()) }
				closeBoth(client, upstream)
			}, "proxy-chain-down").apply { isDaemon = true }
			down.start()
			runCatching { pipe(client.getInputStream(), upstream.getOutputStream()) }
			closeBoth(client, upstream)
		} catch (t: Throwable) {
			closeQuiet(client, "top-level")
		}
	}

	// endregion

	// region chain + hop handshakes

	/**
	 * Amendment 5 item 2e: N-hop loop. Connect TCP to hop 1, then run each
	 * hop's handshake THROUGH the tunnel the previous hop opened, and
	 * finally hand the LAST hop the target host name, unresolved:
	 *   stage i: hop[i] opens a tunnel to hops[i+1] (i < N-1) or to the
	 *   target (exit stage). A 1-hop route is one CONNECT stage.
	 * The exit stage is the old main-hop semantics (isMain = true in the
	 * SOCKS5 helper, keeping greeting/refusal classes distinct); every
	 * intermediate stage refusal is "hop refused tunnel to the next hop".
	 */
	private fun openChain(upstream: Socket, route: Route, target: HopTarget) {
		val hops = route.hops
		try {
			upstream.tcpNoDelay = true
			upstream.connect(InetSocketAddress(hops[0].host, hops[0].port), connectTimeoutSeconds * 1000)
			// same parse guard as the client side: a hop that stops answering
			// mid-handshake must not pin a worker thread forever (reset to 0
			// for the piping phase by the caller; not a 4.4 timer, a guard)
			upstream.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
		} catch (ioe: Exception) {
			throw ChainException(HopFailure.HOP_UNREACHABLE, 0, hops[0].host)
		}
		val input = upstream.getInputStream()
		val output = upstream.getOutputStream()
		for (i in hops.indices) {
			val hop = hops[i]
			// stage destination: the next hop, or the target on the exit stage
			val isExit = i == hops.lastIndex
			val destHost = if (isExit) target.host else hops[i + 1].host
			val destPort = if (isExit) target.port else hops[i + 1].port
			val refusal = if (isExit) HopFailure.TARGET_CONNECT_FAILED else HopFailure.HOP_TUNNEL_REFUSED
			try {
				when (hop.scheme) {
					ProxyListFetcher.Scheme.HTTP ->
						httpConnect(input, output, destHost, destPort, hop.login, hop.password, refusal, i)

					ProxyListFetcher.Scheme.SOCKS4 ->
						socks4aConnect(input, output, destHost, destPort, refusal, i)

					ProxyListFetcher.Scheme.SOCKS5 ->
						socks5Connect(input, output, destHost, destPort, hop.login, hop.password, refusal, isExit, i)
				}
			} catch (e: ChainException) {
				// a scheme helper already classified the phase; pin the hop number on it
				throw e
			} catch (ioe: Exception) {
				// unmapped IO failure: a reachable hop that did not open its
				// tunnel (handshake on the exit stage, tunnel refusal otherwise)
				val cls = if (isExit) refusal else HopFailure.HOP_TUNNEL_REFUSED
				throw ChainException(cls, i, "hop$i ${ioe.javaClass.simpleName}")
			}
		}
	}

	private fun httpConnect(
		input: java.io.InputStream,
		output: OutputStream,
		host: String,
		port: Int,
		username: String?,
		password: String?,
		failure: HopFailure,
		hopIndex: Int,
	) {
		val authority = "$host:$port"
		val sb = StringBuilder(authority.length + 96)
		sb.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
		sb.append("Host: ").append(authority).append("\r\n")
		sb.append("Proxy-Connection: Keep-Alive\r\n")
		if (username != null) {
			val cred = Base64.encodeToString(
				"$username:${password ?: ""}".toByteArray(Charsets.ISO_8859_1),
				Base64.NO_WRAP,
			)
			sb.append("Proxy-Authorization: Basic ").append(cred).append("\r\n")
		}
		sb.append("\r\n")
		output.write(sb.toString().toByteArray(Charsets.US_ASCII))
		output.flush()
		val status = readStatusLine(input)
		if (!status.startsWith("HTTP/1.1 200") && !status.startsWith("HTTP/1.0 200")) {
			throw ChainException(failure, hopIndex, "CONNECT $authority -> $status")
		}
		// consume remaining proxy headers
		val drain = readConnectHeaders(input)
		if (drain == null) throw ChainException(failure, hopIndex, "CONNECT $authority bad trailing headers")
	}

	private fun socks4aConnect(
		input: java.io.InputStream,
		output: OutputStream,
		host: String,
		port: Int,
		failure: HopFailure,
		hopIndex: Int,
	) {
		val name = host.toByteArray(Charsets.ISO_8859_1)
		val req = ByteArray(9 + 1 + name.size + 1)
		req[0] = 0x04 // VER
		req[1] = 0x01 // CONNECT
		req[2] = (port ushr 8).toByte()
		req[3] = port.toByte()
		// DST-IP 0.0.0.1 signals SOCKS4a: resolve the name at the proxy
		req[4] = 0; req[5] = 0; req[6] = 0; req[7] = 1
		req[8] = 0 // USERID terminator
		System.arraycopy(name, 0, req, 9, name.size)
		req[9 + name.size] = 0
		output.write(req)
		output.flush()
		val resp = readFully(input, 8) ?: throw ChainException(failure, hopIndex, "socks4a no reply")
		if (resp[0].toInt() != 0 || resp[1].toInt() != 0x5A) {
			throw ChainException(failure, hopIndex, "socks4a rep=${resp[1].toInt() and 0xFF}")
		}
	}

	private fun socks5Connect(
		input: java.io.InputStream,
		output: OutputStream,
		host: String,
		port: Int,
		username: String?,
		password: String?,
		failure: HopFailure,
		isMain: Boolean,
		hopIndex: Int,
	) {
		val needsAuth = username != null
		// greeting/auth phase failures on a SOCKS5 MAIN are "main handshake
		// failed" (amendment 9); on a gateway they fold into GATEWAY_REFUSED
		val handshakeFailure = if (isMain) HopFailure.HOP_HANDSHAKE_FAILED else failure
		val greet = if (needsAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00)
		output.write(greet)
		output.flush()
		val sel = readFully(input, 2) ?: throw ChainException(handshakeFailure, hopIndex, "socks5 no greeting")
		if (sel[0].toInt() != 0x05) throw ChainException(handshakeFailure, hopIndex, "socks5 ver")
		val method = sel[1].toInt() and 0xFF
		when {
			method == 0x00 -> Unit
			method == 0x02 && needsAuth -> {
				val u = username!!.toByteArray(Charsets.ISO_8859_1)
				val p = (password ?: "").toByteArray(Charsets.ISO_8859_1)
				val auth = ByteArray(3 + u.size + p.size)
				auth[0] = 0x01
				auth[1] = u.size.toByte()
				System.arraycopy(u, 0, auth, 2, u.size)
				auth[2 + u.size] = p.size.toByte()
				System.arraycopy(p, 0, auth, 3 + u.size, p.size)
				output.write(auth)
				output.flush()
				val rep = readFully(input, 2) ?: throw ChainException(handshakeFailure, hopIndex, "socks5 auth no reply")
				if (rep[1].toInt() != 0) throw ChainException(handshakeFailure, hopIndex, "socks5 auth rejected")
			}

			else -> throw ChainException(handshakeFailure, hopIndex, "socks5 method $method unsupported")
		}
		val name = host.toByteArray(Charsets.ISO_8859_1)
		val req = ByteArray(4 + 1 + name.size + 2)
		req[0] = 0x05 // VER
		req[1] = 0x01 // CONNECT
		req[2] = 0x00 // RSV
		req[3] = 0x03 // ATYP domain name (remote DNS)
		req[4] = name.size.toByte()
		System.arraycopy(name, 0, req, 5, name.size)
		val off = 5 + name.size
		req[off] = (port ushr 8).toByte()
		req[off + 1] = port.toByte()
		output.write(req)
		output.flush()
		val head = readFully(input, 4) ?: throw ChainException(failure, hopIndex, "socks5 no reply head")
		if (head[0].toInt() != 0x05) throw ChainException(failure, hopIndex, "socks5 bad reply ver")
		val rep = head[1].toInt() and 0xFF
		if (rep != 0) {
			// rep 2..6 = the named endpoint was refused *inside* this hop's
			// tunnel: main->target failures are TARGET_CONNECT_FAILED; gateway
			// ->main failures are GATEWAY_REFUSED (amendment 9 visibility)
			throw ChainException(if (isMain) TARGET_CONNECT_CLASS else failure, hopIndex, "socks5 rep=$rep")
		}
		// consume BND.ADDR per ATYP
		when (head[3].toInt()) {
			0x01 -> readFully(input, 4)
			0x04 -> readFully(input, 16)
			0x03 -> {
				val len = readFully(input, 1)?.get(0)?.toInt()?.and(0xFF) ?: 0
				readFully(input, len)
			}
		}
		readFully(input, 2) // BND.PORT
	}

	// endregion

	// region io helpers

	private class HopTarget(val host: String, val port: Int)

	private fun readConnectLine(input: java.io.InputStream): String? {
		val line = readLine(input, MAX_REQ_LINE) ?: return null
		return line.takeIf { it.startsWith("CONNECT ", ignoreCase = true) }
	}

	private fun parseConnectTarget(line: String): HopTarget? {
		val parts = line.split(' ')
		if (parts.size < 2) return null
		val authority = parts[1]
		val idx = authority.lastIndexOf(':')
		if (idx <= 0 || idx == authority.length - 1) return null
		val host = authority.substring(0, idx).lowercase()
		val port = authority.substring(idx + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
		if (host.isEmpty() || host.length > 253) return null
		return HopTarget(host, port)
	}

	/** Reads CONNECT headers into a lowercased-name map (bounded), null on EOF
	 *  before the blank line. */
	private fun readConnectHeaders(input: java.io.InputStream): Map<String, String>? {
		val out = HashMap<String, String>(8)
		var lines = 0
		while (lines < MAX_HDR_LINES) {
			val line = readLine(input, MAX_HDR_LINE) ?: return null
			if (line.isEmpty()) return out
			val sep = line.indexOf(':')
			if (sep > 0) {
				out[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
			}
			lines++
		}
		return out // header-pile truncated: accept what fits, rest rides the tunnel
	}

	/** Constant-time secret comparison (amendment 8); nothing is logged here. */
	private fun checkRelayAuth(headers: Map<String, String>): Boolean {
		val provided = headers["proxy-authorization"] ?: return false
		val a = provided.toByteArray(Charsets.ISO_8859_1)
		val b = authHeader.toByteArray(Charsets.ISO_8859_1)
		return MessageDigest.isEqual(a, b)
	}

	private fun readStatusLine(input: java.io.InputStream): String {
		return readLine(input, MAX_HDR_LINE) ?: "<eof>"
	}

	/** Manual CRLF reader; never logs. Bound by per-line byte caps. */
	private fun readLine(input: java.io.InputStream, cap: Int): String? {
		val sb = StringBuilder(96)
		var prevCr = false
		var read = 0
		while (read < cap) {
			val b = input.read()
			if (b < 0) return if (sb.isEmpty()) null else sb.toString()
			read++
			if (b == '\n'.code) {
				return if (prevCr) sb.substring(0, sb.length - 1) else sb.toString()
			}
			prevCr = b == '\r'.code
			sb.append(b.toChar())
		}
		return sb.toString()
	}

	private fun readFully(input: java.io.InputStream, n: Int): ByteArray? {
		val buf = ByteArray(n)
		var off = 0
		while (off < n) {
			val r = input.read(buf, off, n - off)
			if (r < 0) return null
			off += r
		}
		return buf
	}

	private fun pipe(src: java.io.InputStream, dst: OutputStream) {
		val buf = ByteArray(PIPE_BUFFER)
		while (true) {
			val r = src.read(buf)
			if (r < 0) return
			dst.write(buf, 0, r)
			dst.flush()
		}
	}

	private fun track(s: Socket) {
		openSockets += s
		workerCount.incrementAndGet()
	}

	private fun closeQuiet(s: Socket, reason: String) {
		workerCount.decrementAndGet()
		openSockets -= s
		runCatching { s.close() }
	}

	private fun closeBoth(a: Socket, b: Socket) {
		closeQuiet(a, "")
		closeQuiet(b, "")
	}

	/** Item 8 logging: host names only, the chain as hosts IN ORDER, and
	 *  WHICH hop failed with the hop-level class. */
	private fun log(targetHost: String, route: Route?, failure: HopFailure, hopIndex: Int) {
		val chainDesc = route?.display() ?: "-"
		val hopDesc = if (hopIndex >= 0 && route != null && hopIndex < route.hops.size) {
			"hop=$hopIndex(${route.hops[hopIndex].host})"
		} else {
			"hop=-"
		}
		Log.w(TAG, "chain fail (${failure.logLabel}): chain=[$chainDesc] $hopDesc target=$targetHost")
	}

	val activeSocketCount: Int get() = workerCount.get()

	// endregion

	companion object {
		private const val TAG = "ProxyPool"
		private const val MAX_WORKERS = 16
		private const val PIPE_BUFFER = 8 * 1024
		private const val SECRET_BYTES = 32
		private const val MAX_REQ_LINE = 8 * 1024
		private const val MAX_HDR_LINE = 4 * 1024
		private const val MAX_HDR_LINES = 64
		const val DEFAULT_CONNECT_TIMEOUT_S = 8

		/** CONNECT header naming which token-bound chain this tunnel must use.
		 *  Settable only via a pooled client's proxyAuthenticator (OkHttp
		 *  interceptors cannot touch CONNECT request headers). */
		const val HEADER_CHAIN_TOKEN = "x-pool-chain-id"

		/** Per-attempt guard while parsing proxy/protocol replies; closures are
		 *  event driven afterwards (no timers, 4.4). */
		private const val HANDSHAKE_READ_TIMEOUT_MS = 10_000
		private const val RESPONSE_OK = "HTTP/1.1 200 Connection established\r\n" +
			"Proxy-Agent: ProxyChainRelay\r\n\r\n"
		private val TARGET_CONNECT_CLASS = HopFailure.TARGET_CONNECT_FAILED
	}
}
