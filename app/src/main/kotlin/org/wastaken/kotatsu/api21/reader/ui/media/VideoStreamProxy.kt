package org.wastaken.kotatsu.api21.reader.ui.media

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.IOException
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.regex.Pattern

/**
 * Loopback HTTP relay that gives the stock platform [android.media.MediaPlayer]
 * the read-ahead buffer every real player ships (ExoPlayer: DefaultLoadControl
 * buffers ~50s ahead; VLC: --network-caching read-ahead input, default 1000ms).
 * The framework player's own HTTP cache is a few hundred kilobytes and not
 * tunable, which is exactly why network jitter turned into visible stutter.
 *
 * MediaPlayer is pointed at http://127.0.0.1:<ephemeral>/ and this proxy pumps
 * the actual source through the app's OkHttp stack into a bounded in-memory
 * ring:
 *
 *   upstream (OkHttp: source headers, cookies, CloudFlare) -> pump thread
 *     -> okio ring (cap RING_CAP_BYTES, back-pressured) -> consumer -> player
 *
 * The player always reads from loopback instantly while the ring has data, so
 * its tiny internal cache practically never drains dry; pausing playback
 * naturally throttles the upstream read once the ring is full (no bandwidth
 * wasted while paused).
 *
 * Zero-persistence rule kept: the ring is an okio [Buffer] (chained 8KB
 * segments, never one giant contiguous array - safe for the fragmented dalvik
 * heap), nothing is written to disk anywhere, and every upstream request is
 * sent with Cache-Control: no-store so the OkHttp disk cache stays out of the
 * video path entirely.
 *
 * Seeking: the framework player re-connects with a Range header on seek. The
 * pipeline is torn down and re-opened upstream with the same byte offset; if
 * the CDN ignores Range (200 instead of 206) the pump skips the prefix before
 * filling the ring, so non-range servers still work, just seek slower.
 */
class VideoStreamProxy(
	private val okHttpClient: OkHttpClient,
) {

	private val lock = Object()

	private var serverSocket: ServerSocket? = null
	private var acceptThread: Thread? = null

	private var remoteUrl: String? = null
	private var source: MangaSource? = null
	private var headers: Map<String, String> = emptyMap()

	private var producerThread: Thread? = null
	private var producerCall: Call? = null
	private var producerPosition = -1L // absolute byte offset the ring content starts at
	private var producerDone = false
	private var producerFailed = false
	private var cancelled = false

	// framing info captured from the pump's own upstream response (no probe fetch)
	private var headersReady = false
	private var upstreamStatus = 0
	private var upstreamContentType: String? = null
	private var upstreamContentLength = -1L
	private var upstreamContentRange: String? = null
	private var upstreamAcceptsRanges = false

	private var consumerActive = false

	private val ring = Buffer()
	private val clients = HashSet<Socket>()

	@Volatile
	private var stopped = false

	/** Starts the loopback server for [url]; returns the local URL to hand to MediaPlayer. */
	fun start(url: String, source: MangaSource, headers: Map<String, String>): String {
		stopInternal()
		stopped = false
		remoteUrl = url
		this.source = source
		this.headers = headers
		val server = ServerSocket()
		server.reuseAddress = true
		server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0))
		serverSocket = server
		acceptThread = daemonThread("video-proxy-accept") {
			acceptLoop(server)
		}
		return "http://$LOOPBACK:${server.localPort}/video"
	}

	/** Tears down everything: server, open connections, pump and the ring. */
	fun stop() = stopInternal()

	private fun stopInternal() {
		stopped = true
		cancelPipeline()
		runCatching { serverSocket?.close() }
		serverSocket = null
		acceptThread = null
		synchronized(clients) {
			for (socket in clients) {
				runCatching { socket.close() }
			}
			clients.clear()
		}
		synchronized(lock) {
			runCatching { ring.clear() }
			resetProducerState()
			producerPosition = -1L
		}
	}

	private fun resetProducerState() {
		producerDone = false
		producerFailed = false
		headersReady = false
		upstreamStatus = 0
		upstreamContentType = null
		upstreamContentLength = -1L
		upstreamContentRange = null
		upstreamAcceptsRanges = false
	}

	private fun cancelPipeline() {
		synchronized(lock) {
			cancelled = true
			lock.notifyAll()
		}
		producerCall?.cancel() // aborts a blocking okhttp read
		producerCall = null
		producerThread = null
	}

	private fun acceptLoop(server: ServerSocket) {
		while (!stopped) {
			val socket = try {
				server.accept()
			} catch (e: IOException) {
				return // server closed during stop()
			}
			synchronized(clients) {
				clients.add(socket)
			}
			daemonThread("video-proxy-client") {
				handleClient(socket)
			}
		}
	}

	private fun handleClient(socket: Socket) {
		try {
			socket.soTimeout = SOCKET_TIMEOUT_MS
			val request = readRequest(socket) ?: return
			if (request.isHead) {
				respondToHead(socket)
				return
			}
			ensureProducer(request.rangeStart)
			streamToPlayer(socket, request.rangeStart)
		} catch (e: Throwable) {
			// aborted sockets and cancelled pipelines are normal player behavior
		} finally {
			synchronized(clients) {
				clients.remove(socket)
			}
			runCatching { socket.close() }
		}
	}

	/**
	 * (Re)starts the upstream pump so the ring contains bytes starting at [offset]
	 * of the remote file. Reuses a healthy pump already feeding from that offset
	 * (the framework player commonly closes a probe connection and reopens a
	 * playback one at the same position).
	 */
	private fun ensureProducer(offset: Long) {
		synchronized(lock) {
			if (producerThread != null && !producerFailed && producerPosition == offset) {
				return
			}
		}
		cancelPipeline()
		synchronized(lock) {
			runCatching { ring.clear() }
			cancelled = false
			resetProducerState()
			producerPosition = offset
			lock.notifyAll()
		}
		val url = remoteUrl ?: return
		val call = buildUpstreamCall(url, offset)
		producerCall = call
		producerThread = daemonThread("video-proxy-pump") {
			pump(call, offset)
		}
	}

	private fun buildUpstreamCall(url: String, offset: Long): Call {
		val builder = Request.Builder().url(url).get()
		for ((name, value) in headers) {
			builder.header(name, value)
		}
		// keep the OkHttp disk cache out of the video path entirely
		builder.header("Cache-Control", "no-store")
		if (offset > 0L) {
			builder.header("Range", "bytes=$offset-")
		}
		val src = source
		if (src != null) {
			builder.tag(MangaSource::class.java, src)
		}
		return okHttpClient.newCall(builder.build())
	}

	/**
	 * Reads the upstream body into the ring with backpressure. The response's own
	 * headers double as the framing info for the player (no parallel probe fetch).
	 * If the server ignored our Range request (200 instead of 206), the prefix is
	 * skipped before anything is pushed into the ring.
	 */
	private fun pump(call: Call, offset: Long) {
		var body: ResponseBody? = null
		try {
			val response = call.execute()
			if (!response.isSuccessful) {
				synchronized(lock) {
					headersReady = true
					producerFailed = true
					lock.notifyAll()
				}
				return
			}
			val respBody = response.body
			if (respBody == null) {
				synchronized(lock) {
					headersReady = true
					producerFailed = true
					lock.notifyAll()
				}
				return
			}
			body = respBody
			synchronized(lock) {
				upstreamStatus = response.code
				upstreamContentType = response.header("Content-Type")
					?: respBody.contentType()?.toString()
				upstreamContentLength = respBody.contentLength()
				upstreamContentRange = response.header("Content-Range")
				upstreamAcceptsRanges =
					response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true ||
						response.code == HTTP_PARTIAL
				headersReady = true
				lock.notifyAll()
			}
			var skip = if (offset > 0L && response.code == HTTP_OK) offset else 0L
			val upstream = respBody.source()
			val scratch = Buffer()
			while (true) {
				if (skip > 0L) {
					val n = upstream.read(scratch, minOf(PIPE_CHUNK_BYTES, skip))
					if (n == -1L) break
					skip -= n
					scratch.clear()
					continue
				}
				val n = upstream.read(scratch, PIPE_CHUNK_BYTES)
				if (n == -1L) {
					break
				}
				synchronized(lock) {
					ring.write(scratch, n)
					lock.notifyAll()
					while (!cancelled && !stopped && ring.size >= RING_CAP_BYTES) {
						lock.wait(PIPE_WAIT_MS)
					}
				}
				if (isCancelledOrStopped()) {
					break
				}
			}
			synchronized(lock) {
				producerDone = true
				lock.notifyAll()
			}
		} catch (e: InterruptedIOException) {
			markCancelledOrFailed()
		} catch (e: IOException) {
			markCancelledOrFailed()
		} catch (e: Throwable) {
			synchronized(lock) {
				if (!cancelled && !stopped) {
					producerFailed = true
				}
				headersReady = true
				lock.notifyAll()
			}
		} finally {
			runCatching { body?.close() }
		}
	}

	private fun markCancelledOrFailed() {
		synchronized(lock) {
			if (!cancelled && !stopped) {
				producerFailed = true
			}
			headersReady = true
			lock.notifyAll()
		}
	}

	private fun isCancelledOrStopped(): Boolean = synchronized(lock) {
		cancelled || stopped
	}

	/** Drains the ring into the player socket as fast as the player reads. */
	private fun streamToPlayer(socket: Socket, offset: Long) {
		val header = awaitLocalHeader(offset) ?: return
		val out = socket.getOutputStream()
		try {
			out.write(header)
			out.flush()
		} catch (e: IOException) {
			return
		}
		synchronized(lock) {
			if (consumerActive) {
				// two concurrent consumers would steal ring bytes from each other;
				// the framework player always reconnects sequentially, so an
				// overlapping connection is safe to drop
				return
			}
			consumerActive = true
		}
		try {
			while (!stopped) {
				val chunk = synchronized(lock) {
					while (ring.size == 0L && !producerDone && !producerFailed) {
						// producer notifies on data/done/fail; the wait timeout is
						// only a missed-notification guard, never a kill condition
						lock.wait(PIPE_WAIT_MS)
					}
					if (ring.size == 0L && (producerDone || producerFailed)) {
						null
					} else {
						val bytes = ring.readByteArray(minOf(SOCKET_WRITE_CHUNK_BYTES, ring.size))
						lock.notifyAll() // wake the back-pressured pump
						bytes
					}
				} ?: break
				try {
					out.write(chunk)
					out.flush()
				} catch (e: IOException) {
					break // player aborted / connection closed
				}
			}
		} finally {
			synchronized(lock) {
				consumerActive = false
				lock.notifyAll()
			}
		}
	}

	/**
	 * Waits for the pump's captured upstream headers and renders the local
	 * framing header for the player (or a 502 when the upstream failed).
	 */
	private fun awaitLocalHeader(offset: Long): ByteArray? {
		synchronized(lock) {
			while (!headersReady && !producerFailed && !stopped) {
				lock.wait(PIPE_WAIT_MS)
			}
		}
		if (stopped) {
			return null
		}
		val status: Int
		val contentType: String?
		val contentLength: Long
		val contentRange: String?
		val acceptsRanges: Boolean
		synchronized(lock) {
			status = upstreamStatus
			contentType = upstreamContentType
			contentLength = upstreamContentLength
			contentRange = upstreamContentRange
			acceptsRanges = upstreamAcceptsRanges
		}
		if (producerFailed || (status != HTTP_OK && status != HTTP_PARTIAL)) {
			return "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
		}
		return buildString {
			if (offset > 0L && status == HTTP_PARTIAL) {
				append("HTTP/1.1 206 Partial Content\r\n")
			} else {
				append("HTTP/1.1 200 OK\r\n")
			}
			append("Content-Type: ").append(contentType ?: DEFAULT_MIME_TYPE).append("\r\n")
			if (contentRange != null && status == HTTP_PARTIAL) {
				append("Content-Range: ").append(contentRange).append("\r\n")
			}
			if (contentLength >= 0L) {
				append("Content-Length: ").append(contentLength).append("\r\n")
			}
			if (acceptsRanges) {
				append("Accept-Ranges: bytes\r\n")
			}
			append("Connection: close\r\n\r\n")
		}.toByteArray(Charsets.US_ASCII)
	}

	/**
	 * HEAD responses for the rare client that probes with HEAD instead of a GET:
	 * real HEAD first, 1-byte ranged GET fallback when the CDN forbids it.
	 * Never touches the ring/pipeline.
	 */
	private fun respondToHead(socket: Socket) {
		val url = remoteUrl ?: return
		val probe = runCatching { executeProbe(url, method = "HEAD") }.getOrNull()
			?: runCatching { executeProbe(url, method = "GET") }.getOrNull()
		val header = buildString {
			append("HTTP/1.1 200 OK\r\n")
			append("Content-Type: ").append(probe?.contentType ?: DEFAULT_MIME_TYPE).append("\r\n")
			val length = probe?.contentLength ?: -1L
			if (length >= 0L) {
				append("Content-Length: ").append(length).append("\r\n")
			}
			if (probe?.acceptsRanges == true) {
				append("Accept-Ranges: bytes\r\n")
			}
			append("Connection: close\r\n\r\n")
		}.toByteArray(Charsets.US_ASCII)
		socket.getOutputStream().write(header)
		socket.getOutputStream().flush()
	}

	private fun executeProbe(url: String, method: String): ProbeResult {
		val builder = Request.Builder().url(url).method(method, null)
		for ((name, value) in headers) {
			builder.header(name, value)
		}
		builder.header("Cache-Control", "no-store")
		if (method == "GET") {
			builder.header("Range", "bytes=0-0")
		}
		val src = source
		if (src != null) {
			builder.tag(MangaSource::class.java, src)
		}
		okHttpClient.newCall(builder.build()).execute().use { response ->
			val contentRange = response.header("Content-Range")
			return ProbeResult(
				contentType = response.header("Content-Type")
					?: response.body?.contentType()?.toString(),
				contentLength = parseTotalLength(contentRange)
					?: response.body?.contentLength() ?: -1L,
				acceptsRanges = response.header("Accept-Ranges")?.equals("bytes", true) == true ||
					response.code == HTTP_PARTIAL,
			)
		}
	}

	private fun parseTotalLength(contentRange: String?): Long? {
		if (contentRange == null) {
			return null
		}
		// "bytes 0-0/12345"
		val slash = contentRange.lastIndexOf('/')
		if (slash < 0) {
			return null
		}
		val total = contentRange.substring(slash + 1).trim()
		return total.toLongOrNull()?.takeIf { total != "*" }
	}

	private class ProbeResult(
		val contentType: String?,
		val contentLength: Long,
		val acceptsRanges: Boolean,
	)

	private class ClientRequest(
		val isHead: Boolean,
		val rangeStart: Long,
	)

	private fun readRequest(socket: Socket): ClientRequest? {
		val input = socket.getInputStream()
		val firstLine = readAsciiLine(input) ?: return null
		if (!firstLine.startsWith("GET") && !firstLine.startsWith("HEAD")) {
			return null
		}
		var rangeStart = 0L
		var line = readAsciiLine(input)
		while (line != null && line.isNotEmpty()) {
			if (line.startsWith("Range:", ignoreCase = true)) {
				rangeStart = parseRangeStart(line) ?: 0L
			}
			line = readAsciiLine(input)
		}
		return ClientRequest(isHead = firstLine.startsWith("HEAD"), rangeStart = rangeStart)
	}

	private fun parseRangeStart(rangeLine: String): Long? {
		val matcher = RANGE_PATTERN.matcher(rangeLine)
		if (matcher.find()) {
			return matcher.group(1)?.toLongOrNull()
		}
		return null
	}

	private fun readAsciiLine(input: java.io.InputStream): String? {
		val buffer = ByteArrayOutputStream(128)
		while (true) {
			val c = input.read()
			if (c == -1) {
				if (buffer.size() == 0) {
					return null
				}
				break
			}
			if (c == '\n'.code) {
				break
			}
			if (c != '\r'.code) {
				buffer.write(c)
			}
		}
		return String(buffer.toByteArray(), Charsets.US_ASCII)
	}

	private fun daemonThread(name: String, block: () -> Unit): Thread {
		val t = Thread(block, name)
		t.isDaemon = true
		t.start()
		return t
	}

	private companion object {

		private const val LOOPBACK = "127.0.0.1"
		private const val HTTP_OK = 200
		private const val HTTP_PARTIAL = 206

		/** Max bytes buffered ahead of the player (okio segments, never one big array). */
		private const val RING_CAP_BYTES = 6L * 1024L * 1024L
		private const val PIPE_CHUNK_BYTES = 64L * 1024L
		private const val SOCKET_WRITE_CHUNK_BYTES = 64L * 1024L
		private const val PIPE_WAIT_MS = 1_000L
		private const val SOCKET_TIMEOUT_MS = 30_000

		private const val DEFAULT_MIME_TYPE = "video/mp4"

		private val RANGE_PATTERN: Pattern = Pattern.compile("bytes=(\\d+)-\\d*", Pattern.CASE_INSENSITIVE)
	}
}
