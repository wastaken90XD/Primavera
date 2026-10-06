package org.wastaken.kotatsu.api21.core.network.proxypool

import android.util.Base64
import android.util.Log
import okhttp3.Credentials
import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loopback CONNECT relay for two-proxy chains.
 *
 * Why it exists: OkHttp speaks exactly one proxy hop. A chain
 * (gateway -> main proxy -> target) therefore needs something that looks like an
 * ordinary HTTP CONNECT proxy to OkHttp while chaining the two hops itself. The
 * pooled client is pointed at `127.0.0.1:port` and everything else - which
 * proxies, in which order - is this class's business, so rotating a chain never
 * rebuilds a client.
 *
 *   OkHttp -> 127.0.0.1:port (here) -> gateway -> main proxy -> target
 *
 * Hardening:
 *  - bound to 127.0.0.1 ONLY, so nothing on the network can reach it;
 *  - accepts the CONNECT method and nothing else (405 + close for anything else,
 *    so it can never be used as an open HTTP proxy);
 *  - every CONNECT must carry `Proxy-Authorization: Basic` with this run's
 *    secret; the comparison is constant time ([MessageDigest.isEqual]) so a
 *    local process cannot time it;
 *  - the secret lives in memory only - it is generated per [start], is never
 *    written to preferences, and never appears in a log line or an exception;
 *  - at most [MAX_WORKERS] worker threads, i.e. [MAX_TUNNELS] concurrent chains
 *    (each chain needs one thread per direction); the 9th caller is refused
 *    instead of queued, because a queued tunnel is a hung request.
 *
 * Target host names are passed to the chain unresolved (see [ProxyHandshakes]),
 * so DNS for the target happens at the exit, not on the device.
 */
class ProxyChainRelay(
	private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
	private val requestTimeoutMs: Int = DEFAULT_REQUEST_TIMEOUT_MS,
) {

	/**
	 * Notified after every hop failure so the pool state can put a strike on the
	 * member that actually failed. Host names only, never the secret.
	 */
	fun interface FailureListener {
		fun onHopFailure(failure: HopFailure, route: ChainRoute, targetHost: String)
	}

	private val lock = Any()
	private val tunnels = HashSet<Socket>()
	private val tunnelsInUse = AtomicInteger(0)

	@Volatile
	private var serverSocket: ServerSocket? = null

	@Volatile
	private var acceptThread: Thread? = null

	@Volatile
	private var workers: ThreadPoolExecutor? = null

	@Volatile
	private var stopped = true

	@Volatile
	private var secret = newSecret()

	@Volatile
	var route: ChainRoute? = null

	@Volatile
	var failureListener: FailureListener? = null

	val isRunning: Boolean
		get() = serverSocket != null

	/** Loopback port the relay listens on, or 0 while stopped. */
	val port: Int
		get() = serverSocket?.localPort ?: 0

	/**
	 * Starts listening and returns the loopback port. Idempotent: a running relay
	 * keeps its port (and its secret), so pooled clients stay valid.
	 */
	fun start(): Int {
		synchronized(lock) {
			serverSocket?.let { return it.localPort }
			val server = ServerSocket()
			server.reuseAddress = true
			// loopback only - this is the single most important line here
			server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0))
			serverSocket = server
			secret = newSecret()
			stopped = false
			workers = ThreadPoolExecutor(
				0,
				MAX_WORKERS,
				KEEP_ALIVE_SECONDS.toLong(),
				TimeUnit.SECONDS,
				SynchronousQueue(),
				{ runnable ->
					Thread(runnable, "pool-relay-worker").apply { isDaemon = true }
				},
				ThreadPoolExecutor.AbortPolicy(),
			)
			acceptThread = daemonThread("pool-relay-accept") {
				acceptLoop(server)
			}
			return server.localPort
		}
	}

	/**
	 * Tears the relay down: listener, every open tunnel, and the worker pool.
	 * Idempotent, safe to call from any thread, and cheap enough to call on every
	 * "the pool is off now" transition.
	 */
	fun stop() {
		synchronized(lock) {
			stopped = true
			runCatching { serverSocket?.close() }
			serverSocket = null
			acceptThread = null
			synchronized(tunnels) {
				for (socket in tunnels) {
					runCatching { socket.close() }
				}
				tunnels.clear()
			}
			workers?.shutdownNow()
			workers = null
			tunnelsInUse.set(0)
		}
	}

	/** `Proxy-Authorization` value a pooled client must send. Never logged. */
	fun credential(): String = Credentials.basic(LOGIN, secret)

	// region accept / dispatch

	private fun acceptLoop(server: ServerSocket) {
		while (!stopped) {
			val client = try {
				server.accept()
			} catch (e: IOException) {
				return // server socket closed by stop()
			}
			if (tunnelsInUse.get() >= MAX_TUNNELS) {
				runCatching { client.close() }
				continue
			}
			tunnelsInUse.incrementAndGet()
			var submitted = false
			try {
				workers?.execute { handleClient(client) }
				submitted = workers != null
			} catch (e: RejectedExecutionException) {
				submitted = false
			}
			if (!submitted) {
				tunnelsInUse.decrementAndGet()
				runCatching { client.close() }
			}
		}
	}

	private fun handleClient(client: Socket) {
		track(client)
		var tunnel: Socket? = null
		try {
			client.keepAlive = true
			client.soTimeout = requestTimeoutMs
			val input = client.getInputStream()
			val output = client.getOutputStream()
			val request = readRequest(input) ?: return
			if (!request.method.equals(METHOD_CONNECT, ignoreCase = true)) {
				// CONNECT only: this is a tunnel, never an open HTTP proxy
				writeAscii(output, "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n")
				return
			}
			if (!isAuthorized(request)) {
				// deliberately no 407 here: an unauthorized caller learns nothing,
				// and a wrong secret never reaches a log
				return
			}
			val chain = route
			if (chain == null) {
				writeAscii(output, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
				return
			}
			tunnel = openChain(chain, request.host, request.port, output) ?: return
			writeAscii(output, "HTTP/1.1 200 Connection established\r\n\r\n")
			client.soTimeout = PIPE_IDLE_TIMEOUT_MS
			tunnel.soTimeout = PIPE_IDLE_TIMEOUT_MS
			pipeBoth(client, tunnel)
		} catch (e: IOException) {
			e.printStackTraceDebug()
		} finally {
			untrack(client)
			runCatching { client.close() }
			tunnel?.let {
				untrack(it)
				runCatching { it.close() }
			}
			tunnelsInUse.decrementAndGet()
		}
	}

	// endregion

	// region chaining

	/**
	 * gateway -> main -> target. Returns the tunneled socket, or null after
	 * reporting the failing hop and answering the caller with 502.
	 */
	private fun openChain(
		chain: ChainRoute,
		targetHost: String,
		targetPort: Int,
		output: OutputStream,
	): Socket? {
		val gateway = Socket()
		track(gateway)
		try {
			gateway.connect(InetSocketAddress(chain.gateway.host, chain.gateway.port), connectTimeoutMs)
		} catch (e: IOException) {
			report(HopFailure.GATEWAY_UNREACHABLE, chain, targetHost, e)
			closeAnswering(gateway, output)
			return null
		}
		try {
			ProxyHandshakes.openTunnel(gateway, chain.gateway, chain.main.host, chain.main.port)
		} catch (e: ProxyRefusedException) {
			report(HopFailure.GATEWAY_REFUSED_MAIN, chain, targetHost, e)
			closeAnswering(gateway, output)
			return null
		} catch (e: IOException) {
			report(HopFailure.GATEWAY_UNREACHABLE, chain, targetHost, e)
			closeAnswering(gateway, output)
			return null
		}
		try {
			ProxyHandshakes.openTunnel(gateway, chain.main, targetHost, targetPort)
		} catch (e: ProxyRefusedException) {
			// both proxies answered; the chain simply could not reach the target
			report(HopFailure.TARGET_CONNECT_FAILED, chain, targetHost, e)
			closeAnswering(gateway, output)
			return null
		} catch (e: IOException) {
			report(HopFailure.MAIN_HANDSHAKE_FAILED, chain, targetHost, e)
			closeAnswering(gateway, output)
			return null
		}
		gateway.soTimeout = PIPE_IDLE_TIMEOUT_MS
		return gateway
	}

	private fun closeAnswering(gateway: Socket, output: OutputStream) {
		runCatching { gateway.close() }
		untrack(gateway)
		runCatching {
			writeAscii(output, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
		}
	}

	/**
	 * Host names only - never ports, paths, addresses or the secret. This is the
	 * line that makes a Tor exit policy visible: it says WHICH hop refused WHAT,
	 * which is the difference between "the pool is broken" and "the gateway will
	 * not dial port 8080".
	 */
	private fun report(failure: HopFailure, chain: ChainRoute, targetHost: String, error: IOException) {
		Log.w(
			TAG,
			"chain: ${failure.logLabel} (gateway=${chain.gateway.host} main=${chain.main.host} " +
				"target=$targetHost reason=${error.javaClass.simpleName})",
		)
		runCatching { failureListener?.onHopFailure(failure, chain, targetHost) }
	}

	// endregion

	// region piping

	private fun pipeBoth(client: Socket, tunnel: Socket) {
		val pool = workers
		if (pool == null) {
			return
		}
		val reverseStarted = try {
			pool.execute { pipe(tunnel, client) }
			true
		} catch (e: RejectedExecutionException) {
			false
		}
		if (!reverseStarted) {
			return
		}
		pipe(client, tunnel)
	}

	/**
	 * Copies [from] into [to] until either end closes. Whichever direction ends
	 * first closes BOTH sockets, which is what unblocks the other direction -
	 * there is no half-open state to leak a thread in.
	 */
	private fun pipe(from: Socket, to: Socket) {
		val buffer = ByteArray(PIPE_BUFFER_BYTES)
		try {
			val input = from.getInputStream()
			val output = to.getOutputStream()
			while (true) {
				val count = input.read(buffer)
				if (count < 0) {
					break
				}
				if (count == 0) {
					continue
				}
				output.write(buffer, 0, count)
				output.flush()
			}
		} catch (e: IOException) {
			// peer vanished, idle timeout, or the socket was closed by stop():
			// all of them mean "this tunnel is over"
		} finally {
			runCatching { from.close() }
			runCatching { to.close() }
		}
	}

	// endregion

	// region CONNECT request parsing

	private class RelayRequest(
		val method: String,
		val host: String,
		val port: Int,
		val proxyAuthorization: String?,
	)

	private fun readRequest(input: InputStream): RelayRequest? {
		val requestLine = readLine(input) ?: return null
		val parts = requestLine.split(' ')
		if (parts.size < 2) {
			return null
		}
		var authorization: String? = null
		while (true) {
			val line = readLine(input) ?: break
			if (line.isEmpty()) {
				break // end of headers
			}
			if (line.regionMatches(0, HEADER_PROXY_AUTHORIZATION, 0, HEADER_PROXY_AUTHORIZATION.length, ignoreCase = true)) {
				authorization = line.substring(HEADER_PROXY_AUTHORIZATION.length).trim()
			}
		}
		val authority = parts[1]
		val host = authority.substringBeforeLast(':')
		val port = authority.substringAfterLast(':', "").toIntOrNull() ?: return null
		if (host.isEmpty() || port !in 1..0xFFFF) {
			return null
		}
		return RelayRequest(parts[0], host, port, authorization)
	}

	/** Constant-time check of `Basic base64(login:secret)` against this run's secret. */
	private fun isAuthorized(request: RelayRequest): Boolean {
		val header = request.proxyAuthorization ?: return false
		if (!header.regionMatches(0, AUTH_SCHEME, 0, AUTH_SCHEME.length, ignoreCase = true)) {
			return false
		}
		val decoded = runCatching {
			String(Base64.decode(header.substring(AUTH_SCHEME.length).trim(), Base64.DEFAULT), Charsets.UTF_8)
		}.getOrNull() ?: return false
		val password = decoded.substringAfter(':', "")
		if (password.isEmpty()) {
			return false
		}
		return MessageDigest.isEqual(
			password.toByteArray(Charsets.UTF_8),
			secret.toByteArray(Charsets.UTF_8),
		)
	}

	/** Byte-at-a-time so no payload byte after the header block is ever buffered. */
	private fun readLine(input: InputStream): String? {
		val builder = StringBuilder(64)
		var readAny = false
		while (true) {
			val b = input.read()
			if (b < 0) {
				break
			}
			readAny = true
			if (b == '\n'.code) {
				break
			}
			if (b != '\r'.code) {
				builder.append(b.toChar())
				if (builder.length >= MAX_HEADER_LINE) {
					break
				}
			}
		}
		return if (readAny) builder.toString() else null
	}

	private fun writeAscii(output: OutputStream, text: String) {
		output.write(text.toByteArray(Charsets.US_ASCII))
		output.flush()
	}

	// endregion

	private fun track(socket: Socket) {
		synchronized(tunnels) {
			tunnels.add(socket)
		}
	}

	private fun untrack(socket: Socket) {
		synchronized(tunnels) {
			tunnels.remove(socket)
		}
	}

	private fun daemonThread(name: String, block: () -> Unit): Thread {
		val thread = Thread(block, name)
		thread.isDaemon = true
		thread.start()
		return thread
	}

	private fun newSecret(): String {
		val bytes = ByteArray(SECRET_BYTES)
		SecureRandom().nextBytes(bytes)
		return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
	}

	companion object {

		private const val TAG = "ProxyPool"
		private const val LOOPBACK = "127.0.0.1"
		private const val LOGIN = "pool"
		private const val METHOD_CONNECT = "CONNECT"
		private const val HEADER_PROXY_AUTHORIZATION = "Proxy-Authorization:"
		private const val AUTH_SCHEME = "Basic "
		private const val SECRET_BYTES = 24
		private const val MAX_HEADER_LINE = 4096

		/** Worker threads; a chain needs two, so this caps concurrent chains at 8. */
		private const val MAX_WORKERS = 16
		private const val MAX_TUNNELS = MAX_WORKERS / 2
		private const val KEEP_ALIVE_SECONDS = 30
		private const val PIPE_BUFFER_BYTES = 8 * 1024

		/** Idle cap while piping: bounds thread lifetime on a silent peer. */
		private const val PIPE_IDLE_TIMEOUT_MS = 10 * 60_000

		const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000
		const val DEFAULT_REQUEST_TIMEOUT_MS = 8_000
	}
}
