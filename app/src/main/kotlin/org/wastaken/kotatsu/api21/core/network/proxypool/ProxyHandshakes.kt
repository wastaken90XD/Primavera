package org.wastaken.kotatsu.api21.core.network.proxypool

import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyListFetcher.Scheme
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * The proxy answered our handshake with a refusal (non-2xx CONNECT status, or a
 * non-zero SOCKS reply code). The proxy itself was reached: what it refused was
 * the NEXT hop, which is exactly the distinction the pool log has to make (a
 * Tor exit policy refusing port 8080 shows up here, not as "unreachable").
 */
class ProxyRefusedException(message: String) : IOException(message)

/** The proxy never answered: closed the socket, timed out, or sent garbage. */
class ProxyUnreachableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Tunnel handshakes for the three schemes the pool understands, performed on an
 * ALREADY CONNECTED stream so they can be chained: the same primitive opens
 * client->gateway, gateway->main and main->target.
 *
 * Headers and replies are read byte-at-a-time from the raw stream on purpose.
 * Any buffered reader would swallow the first bytes of the tunneled payload.
 *
 * Target host names are handed over UNRESOLVED (SOCKS ATYP 0x03 domain name,
 * CONNECT "host:port" authority), so the proxy does the DNS lookup - which is
 * the whole point of routing around DNS poisoning.
 */
object ProxyHandshakes {

	private const val MAX_HEADER_LINE = 4096
	private const val MAX_HEADER_LINES = 32

	/**
	 * Opens a tunnel to [host]:[port] through the proxy at the other end of
	 * [socket]. Throws [ProxyRefusedException] when the proxy answered with a
	 * refusal and [ProxyUnreachableException] when it did not answer.
	 */
	fun openTunnel(socket: Socket, proxy: ProxyEndpoint, host: String, port: Int) {
		val input = socket.getInputStream()
		val output = socket.getOutputStream()
		when (proxy.scheme) {
			Scheme.HTTP -> httpConnect(input, output, host, port)
			Scheme.SOCKS5 -> socks5Connect(input, output, host, port)
			Scheme.SOCKS4 -> socks4Connect(input, output, host, port)
		}
	}

	// region HTTP CONNECT

	private fun httpConnect(input: InputStream, output: OutputStream, host: String, port: Int) {
		val request = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n"
		try {
			output.write(request.toByteArray(Charsets.US_ASCII))
			output.flush()
		} catch (e: IOException) {
			throw ProxyUnreachableException("CONNECT request could not be written", e)
		}
		val statusLine = try {
			readLine(input)
		} catch (e: IOException) {
			throw ProxyUnreachableException("no answer to CONNECT", e)
		} ?: throw ProxyUnreachableException("connection closed during CONNECT")
		val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
			?: throw ProxyUnreachableException("malformed CONNECT answer")
		if (code !in 200..299) {
			throw ProxyRefusedException("CONNECT refused with HTTP $code")
		}
		drainHeaders(input)
	}

	// endregion

	// region SOCKS5

	private fun socks5Connect(input: InputStream, output: OutputStream, host: String, port: Int) {
		val hostBytes = host.toByteArray(Charsets.US_ASCII)
		if (hostBytes.isEmpty() || hostBytes.size > 0xFF) {
			throw ProxyRefusedException("host name cannot be sent to a SOCKS5 proxy")
		}
		try {
			// version 5, one offered method, "no authentication"
			output.write(byteArrayOf(0x05, 0x01, 0x00))
			output.flush()
			val version = input.read()
			val method = input.read()
			if (version < 0 || method < 0) {
				throw ProxyUnreachableException("SOCKS5 greeting was cut short")
			}
			if (version != 0x05) {
				throw ProxyRefusedException("not a SOCKS5 proxy (version $version)")
			}
			if (method != 0x00) {
				// 0xFF = "no acceptable methods"; anything else asks for auth we do not have
				throw ProxyRefusedException("SOCKS5 proxy demands authentication (method $method)")
			}
			val request = ByteArray(7 + hostBytes.size)
			request[0] = 0x05 // version
			request[1] = 0x01 // CONNECT
			request[2] = 0x00 // reserved
			request[3] = 0x03 // ATYP: domain name -> the proxy resolves it
			request[4] = hostBytes.size.toByte()
			System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
			request[5 + hostBytes.size] = ((port shr 8) and 0xFF).toByte()
			request[6 + hostBytes.size] = (port and 0xFF).toByte()
			output.write(request)
			output.flush()
			val replyVersion = input.read()
			val replyCode = input.read()
			if (replyVersion < 0 || replyCode < 0) {
				throw ProxyUnreachableException("SOCKS5 reply was cut short")
			}
			if (replyVersion != 0x05) {
				throw ProxyUnreachableException("malformed SOCKS5 reply")
			}
			if (replyCode != 0x00) {
				throw ProxyRefusedException("SOCKS5 connect refused (code $replyCode)")
			}
			skipBoundAddress(input)
		} catch (e: IOException) {
			if (e is ProxyRefusedException || e is ProxyUnreachableException) {
				throw e
			}
			throw ProxyUnreachableException("SOCKS5 handshake failed", e)
		}
	}

	private fun skipBoundAddress(input: InputStream) {
		when (val type = input.read()) {
			0x01 -> readFully(input, 4) // IPv4
			0x03 -> {
				val length = input.read()
				if (length < 0) {
					throw ProxyUnreachableException("SOCKS5 reply was cut short")
				}
				readFully(input, length)
			}

			0x04 -> readFully(input, 16) // IPv6
			else -> throw ProxyUnreachableException("unknown SOCKS5 address type $type")
		}
		readFully(input, 2) // bound port
	}

	// endregion

	// region SOCKS4 / SOCKS4a

	private fun socks4Connect(input: InputStream, output: OutputStream, host: String, port: Int) {
		val hostBytes = host.toByteArray(Charsets.US_ASCII)
		// SOCKS4a: DSTIP 0.0.0.x (x != 0) tells the proxy a domain name follows,
		// so the name is resolved remotely exactly like with SOCKS5.
		val request = ByteArray(9 + hostBytes.size + 1)
		request[0] = 0x04 // version
		request[1] = 0x01 // CONNECT
		request[2] = ((port shr 8) and 0xFF).toByte()
		request[3] = (port and 0xFF).toByte()
		request[4] = 0x00
		request[5] = 0x00
		request[6] = 0x00
		request[7] = 0x01 // 0.0.0.1 => SOCKS4a
		request[8] = 0x00 // empty user id
		System.arraycopy(hostBytes, 0, request, 9, hostBytes.size)
		request[9 + hostBytes.size] = 0x00
		try {
			output.write(request)
			output.flush()
			val version = input.read()
			val code = input.read()
			if (version < 0 || code < 0) {
				throw ProxyUnreachableException("SOCKS4 reply was cut short")
			}
			when (code) {
				0x5A -> readFully(input, 6) // granted: 2 bytes port + 4 bytes ip

				0x5B -> throw ProxyRefusedException("SOCKS4 connect refused")
				else -> throw ProxyRefusedException("SOCKS4 connect failed (code $code)")
			}
		} catch (e: IOException) {
			if (e is ProxyRefusedException || e is ProxyUnreachableException) {
				throw e
			}
			throw ProxyUnreachableException("SOCKS4 handshake failed", e)
		}
	}

	// endregion

	/** Reads one CRLF/LF terminated line; null on a clean EOF before any byte. */
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

	private fun drainHeaders(input: InputStream) {
		repeat(MAX_HEADER_LINES) {
			val line = readLine(input) ?: return
			if (line.isEmpty()) {
				return
			}
		}
	}

	private fun readFully(input: InputStream, count: Int) {
		var left = count
		while (left > 0) {
			if (input.read() < 0) {
				throw ProxyUnreachableException("short proxy reply")
			}
			left--
		}
	}
}
