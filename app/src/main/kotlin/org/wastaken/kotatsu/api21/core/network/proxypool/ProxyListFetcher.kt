package org.wastaken.kotatsu.api21.core.network.proxypool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Proxy pool (Task C) - commit 2/7: list fetching with last-good disk copy
 * and mirror fallback (spec 4.6.1/4.6.2 step a, 4.13).
 *
 *  - Hard caps: a list contributes at most MAX_LIST_BYTES (2 MB) of transfer
 *    and MAX_LIST_LINES (20000) parsed lines. The stream reads byte-by-byte
 *    (ram is 1.3 GB, ms-dim lines) so the caps never require buffering the
 *    whole file.
 *  - Line formats: "scheme://host:port" (http|socks4|socks5) or bare
 *    "host:port" (scheme inferred from the list URL tail when it contains
 *    those words, else http).
 *  - Rejections (spec 4.3.3): credentials anywhere ("@" or extra colon
 *    segments), loopback/link-local/RFC1918/other-local IP literals,
 *    "localhost" names. IPv6 literals are skipped outright. Hostnames that
 *    resolve into private space are rejected at health-check time, where
 *    DNS happens anyway (documented once, in the health checker).
 *  - Transport: this object NEVER picks a route by itself. It clones the
 *    caller's client and only overrides timeouts and the cookie jar (list
 *    files carry no cookies). The refresh cycle passes a hard-locked
 *    direct client, so list downloads never cross the pool; the bootstrap's
 *    step c passes its gateway client to retry the fetch through the found
 *    proxy when every direct route is blocked.
 *  - Fallback order per list (spec 4.6.2 step a): direct fetch using the
 *    caller's transport -> the last good copy from disk -> mirror URLs.
 *    The disk copy is trusted until a successful direct/gateway fetch
 *    replaces it - no age logic anywhere (event driven, 4.4).
 *  - Provenance per list: ListSource DIRECT / DISK / MIRROR (the bootstrap
 *    relabels its passes GATEWAY) answers "direct, disk, or mirror" for the
 *    status line and the ProxyPool log.
 *
 * Default list sources were verified with fetch_page (both GitHub-raw URLs
 * and the two jsDelivr mirror projects, recorded in the 2/7 commit
 * message); the jsDelivr forms ship as the default mirror set below.
 */
object ProxyListFetcher {

	private const val MAX_LIST_BYTES = 2L * 1024 * 1024
	private const val MAX_LIST_LINES = 20_000
	private const val CONNECT_TIMEOUT_S = 10
	private const val READ_TIMEOUT_S = 20
	private const val DISK_FILE = "proxy_pool_lists_state.txt"
	private const val DISK_VERSION = 1

	enum class Scheme { HTTP, SOCKS4, SOCKS5 }

	/** Where a list's content actually came from this cycle (status line). */
	enum class ListSource { DIRECT, DISK, MIRROR, GATEWAY }

	data class ProxyEntry(val scheme: Scheme, val host: String, val port: Int) {
		override fun toString(): String = "$scheme:$host:$port"
	}

	data class ListReport(
		val listUrl: String,
		val source: ListSource,
		val linesTotal: Int,
		val linesParsed: Int,
		val entriesKept: Int,
		val bytesRead: Long,
		val capped: Boolean,
		val error: String?,
	)

	data class Result(
		val entries: List<ProxyEntry>,
		val reports: List<ListReport>,
	)

	/** Mirrors shipped as defaults (fetch_page-verified, same line formats). */
	const val POOL_DEFAULT_LISTS =
		"https://api.proxyscrape.com/v3/free-proxy-list/get?request=displayproxies&protocol=http&timeout=15000&proxy_format=protocolipport&format=text\n" +
			"https://api.proxyscrape.com/v3/free-proxy-list/get?request=displayproxies&protocol=socks4&timeout=15000&proxy_format=protocolipport&format=text\n" +
			"https://api.proxyscrape.com/v3/free-proxy-list/get?request=displayproxies&protocol=socks5&timeout=15000&proxy_format=protocolipport&format=text"
	const val POOL_DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"
	const val POOL_DEFAULT_MAX_HEALTHY = 12
	const val POOL_MAX_HEALTHY_CAP = 20

	const val POOL_DEFAULT_MIRRORS =
		"https://cdn.jsdelivr.net/gh/TheSpeedX/SOCKS-List@master/http.txt\n" +
			"https://cdn.jsdelivr.net/gh/TheSpeedX/SOCKS-List@master/socks5.txt\n" +
			"https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/all.txt"

	/**
	 * Full pipeline with fallbacks. [transport] is whatever route the caller
	 * chose (direct during candidate gathering; the gateway client during
	 * bootstrap step c - the bootstrap then relabels the reports GATEWAY).
	 * Never throws: any failure collapses into a per-list report.
	 */
	suspend fun fetchLists(
		transport: OkHttpClient,
		listUrls: List<String>,
		mirrorUrls: List<String>,
		cacheDir: File,
	): Result = coroutineScope {
		val diskState = loadDisk(cacheDir)
		// workers READ the disk state but never mutate it: refreshed copies
		// are returned per list and merged here on the calling dispatcher
		// (no concurrent writes to a shared HashMap)
		val pas = listUrls.map { url ->
			async(Dispatchers.IO) {
				fetchOneWithFallback(transport, url, mirrorUrls, diskState[url].orEmpty())
			}
		}.awaitAll()
		for (pa in pas) {
			pa.refreshedCopy?.let { diskState[pa.listUrl] = it.toMutableList() }
		}
		saveDisk(cacheDir, diskState)
		val entries = LinkedHashSet<ProxyEntry>(pas.size * 64)
		val reports = ArrayList<ListReport>(pas.size)
		for (pa in pas) {
			entries.addAll(pa.entries)
			reports += pa.report
		}
		Result(entries.toList(), reports)
	}

	private data class OneList(
		val listUrl: String,
		val report: ListReport,
		val entries: List<ProxyEntry>,
		val refreshedCopy: List<String>?,
	)

	// region one list

	private data class Download(val lines: List<String>, val bytes: Long, val hitByteCap: Boolean, val error: String?)

	private suspend fun fetchOneWithFallback(
		transport: OkHttpClient,
		url: String,
		mirrors: List<String>,
		diskCopy: List<String>,
	): OneList {
		var firstError: ListReport? = null
		// order: direct -> last-good disk copy -> each mirror url
		for (kind in listOf(ListSource.DIRECT, ListSource.DISK, ListSource.MIRROR)) {
			val sources: List<String> = when (kind) {
				ListSource.DIRECT -> listOf(url)
				ListSource.DISK -> if (diskCopy.isEmpty()) emptyList() else listOf(url)
				ListSource.MIRROR -> mirrors
				ListSource.GATEWAY -> emptyList() // never a fallback source itself
			}
			for (source in sources) {
				val dl = when (kind) {
					ListSource.DISK -> Download(diskCopy, 0L, false, null)
					else -> download(transport, source)
				}
				if (dl.error != null || dl.lines.isEmpty()) {
					if (firstError == null) {
						firstError = ListReport(url, kind, 0, 0, 0, dl.bytes, false, dl.error ?: "empty")
					}
					continue
				}
				val (report, entries) = parseEntries(if (kind == ListSource.MIRROR) source else url, dl, source = kind)
				if (entries.isNotEmpty()) {
					// only a successful direct/gateway fetch returns a
					// refreshed last-good copy (caller merges it); disk/mirror
					// content never poisons the copy
					val refreshed = if (kind == ListSource.DIRECT) {
						dl.lines.filter { it.isNotBlank() }
					} else {
						null
					}
					return OneList(url, report, entries, refreshed)
				}
				if (firstError == null) {
					firstError = ListReport(url, kind, 0, 0, 0, dl.bytes, false, "no usable entries")
				}
			}
		}
		return OneList(
			url,
			firstError ?: ListReport(url, ListSource.DIRECT, 0, 0, 0, 0, false, "no source yielded entries"),
			emptyList(),
			null,
		)
	}

	/** Streams one list through the caller's transport. Collection stops at
	 *  the line cap; transfer stops at the byte cap (marked in the report). */
	private fun download(transport: OkHttpClient, listUrl: String): Download {
		return try {
			val client = transport.newBuilder()
				.cookieJar(CookieJar.NO_COOKIES) // list files carry no cookies
				.connectTimeout(CONNECT_TIMEOUT_S.toLong(), TimeUnit.SECONDS)
				.readTimeout(READ_TIMEOUT_S.toLong(), TimeUnit.SECONDS)
				.build()
			val request = Request.Builder().url(listUrl).build()
			var error: String? = null
			var bytesRead = 0L
			var hitByteCap = false
			val lines = ArrayList<String>(1024)
			client.newCall(request).execute().use { response ->
				if (response.isSuccessful) {
					val source = response.body?.byteStream()?.buffered()
					if (source == null) {
						error = "empty body"
					} else {
						var eof = false
						while (!eof && lines.size < MAX_LIST_LINES && !hitByteCap) {
							val lineBuffer = StringBuilder()
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
									hitByteCap = true
									break
								}
							}
							lines.add(lineBuffer.toString())
						}
					}
				} else {
					error = "HTTP ${response.code}"
				}
			}
			Download(lines, bytesRead, hitByteCap, error)
		} catch (e: Exception) {
			e.printStackTraceDebug()
			Download(emptyList(), 0L, false, e.javaClass.simpleName + ": " + (e.message ?: ""))
		}
	}

	private fun parseEntries(labelUrl: String, dl: Download, source: ListSource = ListSource.DIRECT): Pair<ListReport, List<ProxyEntry>> {
		val entries = ArrayList<ProxyEntry>(minOf(dl.lines.size, MAX_LIST_LINES))
		var total = 0
		var kept = 0
		var hitLineCap = false
		val defaultScheme = inferScheme(labelUrl)
		for (line in dl.lines) {
			total++
			if (total > MAX_LIST_LINES) {
				hitLineCap = true
				break
			}
			val e = parseLine(line.trim(), defaultScheme) ?: continue
			entries += e
			kept++
		}
		val report = ListReport(
			listUrl = labelUrl,
			source = source,
			linesTotal = dl.lines.size,
			linesParsed = minOf(total, MAX_LIST_LINES),
			entriesKept = kept,
			bytesRead = dl.bytes,
			capped = hitLineCap || dl.hitByteCap,
			error = null,
		)
		return report to entries
	}

	// endregion

	// region disk copy (trusted until a successful fetch replaces it; no age rules)

	private fun loadDisk(cacheDir: File): MutableMap<String, MutableList<String>> {
		val out = HashMap<String, MutableList<String>>(4)
		try {
			val file = File(cacheDir, DISK_FILE)
			if (!file.isFile) return out
			val lines = file.readLines()
			if (lines.isEmpty() || lines[0] != "#v$DISK_VERSION") return out
			var currentUrl: String? = null
			for (line in lines.drop(1)) {
				when {
					line.startsWith("#url ") -> currentUrl = line.removePrefix("#url ")
					currentUrl != null && line.isNotBlank() && !line.startsWith("#") ->
						out.getOrPut(currentUrl!!) { ArrayList() }.add(line)
				}
			}
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
		return out
	}

	private fun saveDisk(cacheDir: File, state: Map<String, List<String>>) {
		try {
			val sb = StringBuilder(64 * 1024)
			sb.append("#v").append(DISK_VERSION).append('\n')
			for ((url, lines) in state) {
				sb.append("#url ").append(url).append('\n')
				for (l in lines.take(MAX_LIST_LINES)) {
					sb.append(l).append('\n')
				}
			}
			File(cacheDir, DISK_FILE).writeText(sb.toString())
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	// endregion

	// region parsing (unchanged semantics carried over from the 1/5 list work)

	private val SCHEME_REGEX = Regex("^(https?|socks4|socks5)://", RegexOption.IGNORE_CASE)

	/** @VisibleForTesting; pure string work, null = rejected */
	fun parseLine(raw: String, defaultScheme: Scheme): ProxyEntry? {
		var line = raw.trim()
		if (line.isEmpty() || line.startsWith("#")) {
			return null
		}
		// spec 4.3.3: credentials anywhere in the line => private/leaked proxies
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
		if (line.contains('[', ignoreCase = false)) {
			return null // "[ipv6]:port" is skipped outright (documented in the class doc)
		}
		val colon = line.indexOf(':')
		if (colon <= 0) {
			return null
		}
		val host = line.substring(0, colon).trim()
		val portText = line.substring(colon + 1).trim()
		if (host.isEmpty() || portText.isEmpty() || portText.contains(':')) {
			return null // extra colon segments also cover "user:pass" leftovers
		}
		val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
		if (!isSafeHost(host)) {
			return null
		}
		return ProxyEntry(scheme, host, port)
	}

	private fun inferScheme(listUrl: String): Scheme {
		val tail = listUrl.substringAfterLast('/').lowercase()
		return when {
			tail.contains("socks5") -> Scheme.SOCKS5
			tail.contains("socks4") -> Scheme.SOCKS4
			else -> Scheme.HTTP
		}
	}

	private fun isSafeHost(host: String): Boolean {
		val h = host.trim().lowercase()
		if (h.isEmpty() || h.length > 253) return false
		if (h == "localhost" || h.endsWith(".localhost")) return false
		if (!h.all { it.isDigit() || it == '.' }) {
			return true // hostname: DNS safety enforced at health check time
		}
		val quad = h.split('.')
		if (quad.size != 4) return false
		val o = quad.map { it.toIntOrNull() ?: return false }
		if (o.any { it !in 0..255 }) return false
		if (o[0] == 0 || o[0] == 127 || o[0] >= 224) return false
		if (o[0] == 10) return false
		if (o[0] == 172 && o[1] in 16..31) return false
		if (o[0] == 192 && o[1] == 168) return false
		if (o[0] == 169 && o[1] == 254) return false
		return true
	}

	// endregion
}
