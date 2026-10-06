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
 * Proxy pool (experimental) - commit 1/7: the local chaining relay (Task C 4.7).
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

	/** A single hop address the relay can drive through any supported handshake. */
	data class Hop(
		val scheme: ProxyListFetcher.Scheme,
		val host: String,
		val port: Int,
	)

	/** Active gateway: found/bootstrap hops have no credentials; the static
	 *  gateway reads login/password from ProxyProvider settings at bind time. */
	data class GatewayBinding(
		val hop: Hop,
		val username: String?,
		val password: String?,
		val sourceLabel: String, // "static" | "bootstrap" | "found" (status line)
	)

	enum class HopFailure(val logLabel: String) {
		GATEWAY_UNREACHABLE("gateway unreachable"),
		GATEWAY_REFUSED("gateway refused tunnel to main proxy"),
		MAIN_HANDSHAKE_FAILED("main proxy handshake failed"),
		TARGET_CONNECT_FAILED("target connect failed through the chain"),
		NO_GATEWAY("no gateway bound"),
		NO_CHAIN("no chain bound for target host"),
		INTERNAL("relay internal error"),
	}

	private class ChainException(val failure: HopFailure, detail: String) :
		Exception(detail)

	@Volatile
	private var gateway: GatewayBinding? = null

	private val chainsByHost = ConcurrentHashMap<String, Hop>()
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

	/** Sets or clears the active gateway. Hot-swappable between refresh cycles. */
	fun setGateway(binding: GatewayBinding?) {
		gateway = binding
	}

	/** Binds the main proxy for one target host; null clears. */
	fun bindChain(targetHost: String, main: Hop?) {
		if (main == null) {
			chainsByHost.remove(targetHost.lowercase())
		} else {
			chainsByHost[targetHost.lowercase()] = main
		}
	}

	fun clearChains() {
		chainsByHost.clear()
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
			if (!checkRelayAuth(input)) {
				// constant-time compare happens inside; wrong secret = silent close
				return closeQuiet(client, "relay auth missing/mismatch")
			}
			drainHeaders(input)
			val gw = gateway ?: run {
				log(target.host, null, HopFailure.NO_GATEWAY)
				return closeQuiet(client, "no gateway")
			}
			val main = chainsByHost[target.host.lowercase()] ?: run {
				log(target.host, gw.hop, HopFailure.NO_CHAIN)
				return closeQuiet(client, "no chain bound")
			}
			val upstream = Socket()
			track(upstream)
			try {
				openChain(upstream, gw, main, target)
			} catch (ce: ChainException) {
				log(target.host, gw.hop, ce.failure)
				closeQuiet(upstream, ce.failure.logLabel)
				return closeQuiet(client, ce.failure.logLabel)
			} catch (ioe: Exception) {
				log(target.host, gw.hop, HopFailure.INTERNAL)
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

	/** gateway TCP -> handshake to MAIN through it -> handshake to TARGET through MAIN. */
	private fun openChain(upstream: Socket, gw: GatewayBinding, main: Hop, target: HopTarget) {
		try {
			upstream.tcpNoDelay = true
			upstream.connect(InetSocketAddress(gw.hop.host, gw.hop.port), connectTimeoutSeconds * 1000)
		} catch (ioe: Exception) {
			throw ChainException(HopFailure.GATEWAY_UNREACHABLE, gw.hop.host)
		}
		val input = upstream.getInputStream()
		val output = upstream.getOutputStream()
		// hop 1: gateway -> main
		when (gw.hop.scheme) {
			ProxyListFetcher.Scheme.HTTP ->
				httpConnect(input, output, main.host, main.port, gw.username, gw.password, HopFailure.GATEWAY_REFUSED)

			ProxyListFetcher.Scheme.SOCKS4 ->
				socks4aConnect(input, output, main.host, main.port, HopFailure.GATEWAY_REFUSED)

			ProxyListFetcher.Scheme.SOCKS5 ->
				socks5Connect(input, output, main.host, main.port, gw.username, gw.password, HopFailure.GATEWAY_REFUSED, isMain = false)
		}
		// hop 2: main -> target (through the tunnel hop 1 just opened)
		when (main.scheme) {
			ProxyListFetcher.Scheme.HTTP ->
				httpConnect(input, output, target.host, target.port, null, null, HopFailure.TARGET_CONNECT_FAILED)

			ProxyListFetcher.Scheme.SOCKS4 ->
				socks4aConnect(input, output, target.host, target.port, HopFailure.TARGET_CONNECT_FAILED)

			ProxyListFetcher.Scheme.SOCKS5 ->
				socks5Connect(input, output, target.host, target.port, null, null, HopFailure.TARGET_CONNECT_FAILED, isMain = true)
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
			throw ChainException(failure, "CONNECT $authority -> $status")
		}
		// consume remaining proxy headers
		drainHeaders(input)
	}

	private fun socks4aConnect(
		input: java.io.InputStream,
		output: OutputStream,
		host: String,
		port: Int,
		failure: HopFailure,
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
		val resp = readFully(input, 8) ?: throw ChainException(failure, "socks4a no reply")
		if (resp[0].toInt() != 0 || resp[1].toInt() != 0x5A) {
			throw ChainException(failure, "socks4a rep=${resp[1].toInt() and 0xFF}")
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
	) {
		val needsAuth = username != null
		// greeting
		val greet = if (needsAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00)
		output.write(greet)
		output.flush()
		val sel = readFully(input, 2) ?: throw ChainException(failure, "socks5 no greeting")
		if (sel[0].toInt() != 0x05) throw ChainException(HopFailure.MAIN_HANDSHAKE_FAILED.takeIf { isMain } ?: failure, "socks5 ver")
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
				val rep = readFully(input, 2) ?: throw ChainException(failure, "socks5 auth no reply")
				if (rep[1].toInt() != 0) throw ChainException(failure, "socks5 auth rejected")
			}

			else -> throw ChainException(failure, "socks5 method $method unsupported")
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
		val head = readFully(input, 4) ?: throw ChainException(failure, "socks5 no reply head")
		if (head[0].toInt() != 0x05) throw ChainException(failure, "socks5 bad reply ver")
		val rep = head[1].toInt() and 0xFF
		if (rep != 0) {
			// rep 2..6 = the named endpoint was refused *inside* this hop's
			// tunnel: main->target failures are TARGET_CONNECT_FAILED; gateway
			// ->main failures are GATEWAY_REFUSED (amendment 9 visibility)
			throw ChainException(if (isMain) TARGET_CONNECT_CLASS else failure, "socks5 rep=$rep")
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

	/** Constant-time secret comparison (amendment 8); nothing is logged here. */
	private fun checkRelayAuth(input: java.io.InputStream): Boolean {
		// scan header lines until empty line, remembering the auth value
		var got: String? = null
		while (true) {
			val line = readLine(input, MAX_HDR_LINE) ?: return false
			if (line.isEmpty()) break
			val sep = line.indexOf(':')
			if (sep > 0 && line.substring(0, sep).trim().equals("Proxy-Authorization", true)) {
				got = line.substring(sep + 1).trim()
			}
		}
		val provided = got ?: return false
		val a = provided.toByteArray(Charsets.ISO_8859_1)
		val b = authHeader.toByteArray(Charsets.ISO_8859_1)
		return MessageDigest.isEqual(a, b)
	}

	private fun drainHeaders(input: java.io.InputStream) {
		while (true) {
			val line = readLine(input, MAX_HDR_LINE) ?: return
			if (line.isEmpty()) return
		}
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

	private fun log(targetHost: String, gw: Hop?, failure: HopFailure) {
		val gwDesc = gw?.let { "${it.host}:${it.port}" } ?: "-"
		val mainDesc = chainsByHost[targetHost.lowercase()]?.let { "${it.host}:${it.port}" } ?: "-"
		Log.w(TAG, "chain fail (${failure.logLabel}): gateway=$gwDesc main=$mainDesc target=$targetHost")
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
		const val DEFAULT_CONNECT_TIMEOUT_S = 8

		/** Per-attempt guard while parsing proxy/protocol replies; closures are
		 *  event driven afterwards (no timers, 4.4). */
		private const val HANDSHAKE_READ_TIMEOUT_MS = 10_000
		private const val RESPONSE_OK = "HTTP/1.1 200 Connection established\r\n" +
			"Proxy-Agent: ProxyChainRelay\r\n\r\n"
		private val TARGET_CONNECT_CLASS = HopFailure.TARGET_CONNECT_FAILED
	}
}
