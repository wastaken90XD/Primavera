package org.wastaken.kotatsu.api21.core.network.proxypool

import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Proxy pool (experimental) - component 1/5: list fetcher and parser.
 *
 * Downloads public proxy lists and parses them into typed entries, honoring
 * the caps from the feature spec:
 *  - at most MAX_LIST_BYTES per list, read as a stream (never buffered whole;
 *    the target device has ~1.3GB RAM and list files top out near 4MB),
 *  - at most MAX_LIST_LINES parsed per list.
 *
 * Transport discipline (spec section 3):
 *  - list downloads go direct: this class builds a transport from the app base
 *    client with Proxy.NO_PROXY and no authenticator, so neither the pool nor
 *    the user's static proxy nor the proxy authenticator can ever touch these
 *    requests. The single exception is the bootstrap's second fetch, which the
 *    caller explicitly pins to ONE named gateway (see [fetch]);
 *  - no cookie/UA fluff beyond what the base client already sets.
 *
 * Line formats supported:
 *  - "scheme://host:port" (monosans style; scheme in {http, socks4, socks5});
 *  - "host:port" (TheSpeedX style; scheme inferred from the list file name
 *    when it contains http/socks4/socks5, otherwise HTTP);
 *  - "[ipv6]:port" is skipped deliberately: public lists that carry v6 are
 *    rare and the address-family/maintenance cost is not worth it here.
 *
 * Rejections (spec section 3.4):
 *  - lines with credentials anywhere (user:pass@host, host:port:user:pass);
 *  - IP literals in loopback / link-local / RFC1918 / UNSPEC ranges - a
 *    fetched list can never aim traffic at the device or the local network;
 *  - "localhost" and its common spellings.
 * Host NAMES (not literals) that resolve to private space are handled at
 * health-check time (component 2), where DNS resolution happens anyway.
 */
object ProxyListFetcher {

	private const val MAX_LIST_BYTES = 2L * 1024L * 1024L // hard transfer cap per list
	private const val MAX_LIST_LINES = 20_000
	private const val CONNECT_TIMEOUT_S = 10L
	private const val READ_TIMEOUT_S = 20L

	private val DIRECT_SELECTOR = object : ProxySelector() {
		override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)
		override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
	}

	enum class Scheme { HTTP, SOCKS4, SOCKS5 }

	data class ProxyEntry(val scheme: Scheme, val host: String, val port: Int) {
		override fun toString() = "$scheme:$host:$port"
	}

	data class ListReport(
		val listUrl: String,
		val linesTotal: Int,
		val linesParsed: Int,
		val entriesKept: Int,
		val bytesRead: Long,
		val truncated: Boolean,
		val error: String?,
	)

	data class Result(
		val entries: List<ProxyEntry>,
		val reports: List<ListReport>,
	)

	/**
	 * Downloads and parses all [listUrls]. Never throws: per-list failures are
	 * captured into [ListReport.error] so one bad URL cannot kill the batch.
	 *
	 * [viaGateway] is normally null (lists are fetched direct, as above). The
	 * bootstrap cycle passes a gateway for the SECOND list fetch, which is the
	 * one that replaces the on-disk copy: lists that are only reachable from
	 * behind the gateway - or that a censor serves differently to the device's
	 * own IP - are exactly the lists worth having when the pool is in use.
	 */
	suspend fun fetch(
		baseClient: OkHttpClient,
		listUrls: List<String>,
		viaGateway: ProxyEndpoint? = null,
	): Result {
		val transport = baseClient.newBuilder()
			.proxySelector(if (viaGateway == null) DIRECT_SELECTOR else selectorFor(viaGateway))
			.proxyAuthenticator(Authenticator.NONE)
			.connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
			.readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
			.build()
		val all = LinkedHashSet<ProxyEntry>()
		val reports = ArrayList<ListReport>(listUrls.size)
		for (url in listUrls) {
			if (url.isBlank()) continue
			reports += fetchOne(transport, url.trim(), all)
		}
		return Result(all.toList(), reports)
	}

	private fun selectorFor(endpoint: ProxyEndpoint): ProxySelector = object : ProxySelector() {
		override fun select(uri: URI?): List<Proxy> = listOf(endpoint.toProxy())
		override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
	}

	private fun fetchOne(
		client: OkHttpClient,
		listUrl: String,
		out: LinkedHashSet<ProxyEntry>,
	): ListReport {
		var linesTotal = 0
		var entriesKept = 0
		var bytesRead = 0L
		var truncated = false
		var error: String? = null
		try {
			val request = Request.Builder().url(listUrl).build()
			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					error = "HTTP ${response.code}"
					return@use
				}
				val source = response.body?.byteStream()?.buffered()
				if (source == null) {
					error = "empty body"
					return@use
				}
				while (true) {
					val lineBuffer = StringBuilder()
					// streaming line-read with byte accounting: read chars from the
					// buffer so the 2MB cap never requires holding the file whole
					var eof = false
					while (true) {
						val ch = source.read()
						if (ch < 0) {
							eof = true
							break
						}
						bytesRead++
						if (ch == '\n'.code) break
						if (ch != '\r'.code) lineBuffer.append(ch.toChar())
						if (bytesRead >= MAX_LIST_BYTES) {
							truncated = true
							eof = true
							break
						}
					}
					val line = lineBuffer.toString().trim()
					if (line.isNotEmpty()) {
						linesTotal++
					}
					if (linesTotal <= MAX_LIST_LINES && line.isNotEmpty()) {
						val entry = parseLine(line, inferScheme(listUrl))
						if (entry != null && out.add(entry)) {
							entriesKept++
						}
					} else if (linesTotal > MAX_LIST_LINES) {
						truncated = true
					}
					if (eof || truncated) break
				}
			}
		} catch (e: Exception) {
			e.printStackTraceDebug()
			error = e.javaClass.simpleName + ": " + (e.message ?: "")
		}
		return ListReport(
			listUrl = listUrl,
			linesTotal = linesTotal,
			linesParsed = minOf(linesTotal, MAX_LIST_LINES),
			entriesKept = entriesKept,
			bytesRead = bytesRead,
			truncated = truncated,
			error = error,
		)
	}

	private val SCHEME_REGEX = Regex("^(https?|socks4|socks5)://", RegexOption.IGNORE_CASE)

	/** VisibleForTesting; pure string work, null = rejected */
	fun parseLine(raw: String, defaultScheme: Scheme): ProxyEntry? {
		var line = raw.trim()
		if (line.isEmpty() || line.startsWith("#")) {
			return null
		}
		// spec 3.4: credentials anywhere in the line => private/leaked proxies
		if (line.contains('@')) {
			return null
		}
		var scheme = defaultScheme
		val m = SCHEME_REGEX.find(line)
		if (m != null) {
			scheme = when (m.groupValues[1].lowercase()) {
				"http", "https" -> Scheme.HTTP
				"socks4" -> Scheme.SOCKS4
				"socks5" -> Scheme.SOCKS5
				else -> return null
			}
			line = line.substring(m.value.length)
		}
		// host:port:extra parts with colons beyond the first => either v6
		// (skipped) or host:port:user:pass (credentials, rejected above only when
		// '@'-style is used); a second colon after a bare host:port is treated
		// as malformed for this parser
		val firstColon = line.indexOf(':')
		if (firstColon <= 0) {
			return null
		}
		val host = line.substring(0, firstColon).trim()
		val portText = line.substring(firstColon + 1).trim()
		if (portText.contains(':')) {
			return null
		}
		val port = portText.toIntOrNull() ?: return null
		if (port !in 1..65535) {
			return null
		}
		if (host.isEmpty() || host.contains(' ') || host.contains('/')) {
			return null
		}
		if (isLocalOrPrivate(host)) {
			return null
		}
		return ProxyEntry(scheme, host, port)
	}

	/** No-scheme lists: TheSpeedX encodes the type in the file name. */
	private fun inferScheme(listUrl: String): Scheme {
		val name = listUrl.substringAfterLast('/').lowercase()
		return when {
			name.contains("socks5") -> Scheme.SOCKS5
			name.contains("socks4") -> Scheme.SOCKS4
			else -> Scheme.HTTP
		}
	}

	private val LOCALHOST_NAMES = setOf("localhost", "localhost.localdomain", "ip6-localhost")

	/** Literal-only gate (host names are resolved at health-check time). */
	private fun isLocalOrPrivate(host: String): Boolean {
		val h = host.lowercase()
		if (h in LOCALHOST_NAMES) {
			return true
		}
		// IPv6-ish shorthand: only literal forms arrive here
		if (h.contains(":")) {
			return h == "::1" || h == "::" ||
				h.startsWith("fe80:") || h.startsWith("fec0:") ||
				h.startsWith("fc") || h.startsWith("fd")
		}
		if (!h.all { it.isDigit() || it == '.' }) {
			return false // hostname, not a literal
		}
		val quad = h.split('.')
		if (quad.size != 4) {
			return false
		}
		val o = quad.map { it.toIntOrNull() ?: return false }
		if (o.any { it !in 0..255 }) {
			return false
		}
		return o[0] == 0 ||                 // 0.0.0.0/8  "this" network
			o[0] == 10 ||                   // RFC1918
			(o[0] == 172 && o[1] in 16..31) || // RFC1918
			(o[0] == 192 && o[1] == 168) || // RFC1918
			o[0] == 127 ||                  // loopback
			(o[0] == 169 && o[1] == 254) || // link-local
			(o[0] == 224)                   // multicast: not a valid proxy target anyway
	}
}
