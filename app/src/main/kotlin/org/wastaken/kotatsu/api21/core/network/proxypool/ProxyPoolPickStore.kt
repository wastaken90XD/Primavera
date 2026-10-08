package org.wastaken.kotatsu.api21.core.network.proxypool

import org.wastaken.kotatsu.api21.core.util.ext.printStackTraceDebug
import java.io.File

/**
 * Amendment 5 (10c): on-disk pick/ban ledger for the proxy pool.
 *
 * Lives in the app's CACHE dir ("{cacheDir}/proxy_pool/picks.txt") -
 * users are warned in the UI that a cache wipe resets the file without
 * asking. One line per entry, whitespace separated, human-editable:
 *
 *   picked  socks5 1.2.3.4 1080            | a pool pick (5d: survives
 *                                           refreshes, never auto-removed)
 *   manual  http host:port [login pass]    | user's own proxy (5e: may carry
 *                                           credentials; loopback allowed -
 *                                           local Tor setups legitimately
 *                                           use 127.0.0.1; kept out of every
 *                                           list fetch, never uploaded)
 *   banned  scheme host port               | per-host ban (4b: only cert
 *                                           errors with verification on, or
 *                                           manual action; "Clear bans"
 *                                           deletes these lines)
 *   strikes scheme host port N             | failed-check score for a LIST
 *                                           candidate (5e rule: picks are
 *                                           never removed; unpicked entries
 *                                           that reach [STRIKE_REMOVE_AT]
 *                                           strikes are dropped, picks are
 *                                           only excluded from Auto use at 3)
 *
 * Parse failures skip the line silently (commented or partial lines are
 * kept verbatim at their position so the file stays hand-editable).
 */
object ProxyPoolPickStore {

	const val STRIKE_REMOVE_AT = 3

	enum class LineKind(val tag: String) {
		PICKED("picked"),
		MANUAL("manual"),
		BANNED("banned"),
		STRIKES("strikes"),
		OTHER("#"),
	}

	class Line(
		val kind: LineKind,
		/** Kind OTHER keeps its exact raw text. */
		val raw: String,
	) {
		var scheme: ProxyListFetcher.Scheme = ProxyListFetcher.Scheme.HTTP
		var host: String = ""
		var port: Int = 0
		var login: String? = null
		var password: String? = null
		var strikes: Int = 0

		/** Matches ProxyPoolController.proxyKeyOf: ProxyEntry.toString(). */
		val key: String get() = entry().toString()

		fun entry(): ProxyListFetcher.ProxyEntry =
			ProxyListFetcher.ProxyEntry(scheme, host, port)

		fun write(): String = when (kind) {
			LineKind.PICKED, LineKind.BANNED -> "${kind.tag} ${scheme.name.lowercase()} $host $port"
			LineKind.MANUAL ->
				if (login != null) {
					"${kind.tag} ${scheme.name.lowercase()} $host $port $login ${password.orEmpty()}"
				} else {
					"${kind.tag} ${scheme.name.lowercase()} $host $port"
				}

			LineKind.STRIKES -> "${kind.tag} ${scheme.name.lowercase()} $host $port $strikes"
			LineKind.OTHER -> raw
		}

		companion object {
			fun parse(text: String): Line? {
				val t = text.trim()
				if (t.isEmpty()) {
					return null
				}
				if (t.startsWith("#")) {
					return Line(LineKind.OTHER, text)
				}
				val parts = t.split(Regex("\\s+"))
				if (parts.size < 4) {
					return null
				}
				val kind = when (parts[0]) {
					LineKind.PICKED.tag -> LineKind.PICKED
					LineKind.MANUAL.tag -> LineKind.MANUAL
					LineKind.BANNED.tag -> LineKind.BANNED
					LineKind.STRIKES.tag -> LineKind.STRIKES
					else -> return Line(LineKind.OTHER, text)
				}
				val scheme = ProxyListFetcher.parseSchemeWord(parts[1]) ?: return Line(LineKind.OTHER, text)
				val port = parts[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
				return Line(kind, text).apply {
					this.scheme = scheme
					host = parts[2]
					this.port = port
					if (kind == LineKind.MANUAL && parts.size >= 5) {
						login = parts[4]
						if (parts.size >= 6) password = parts[5]
					}
					if (kind == LineKind.STRIKES && parts.size >= 5) {
						strikes = parts[4].toIntOrNull()?.coerceAtLeast(0) ?: 0
					}
				}
			}
		}
	}

	private fun fileFor(cacheDir: File): File = File(File(cacheDir, "proxy_pool"), "picks.txt")

	/** The whole ledger, in file order. Never throws: a missing/corrupt
	 *  file yields an empty list (and logs once). */
	fun load(cacheDir: File): MutableList<Line> {
		val file = fileFor(cacheDir)
		if (!file.isFile) {
			return ArrayList()
		}
		return try {
			file.readLines(Charsets.UTF_8).mapNotNullTo(ArrayList()) { Line.parse(it) }
		} catch (e: Exception) {
			e.printStackTraceDebug()
			ArrayList()
		}
	}

	/** Full rewrite - the ledger is small (< a few KB) and users edit it
	 *  rarely. @Synchronized because refresh, outcomes and UI writes all
	 *  pass through this file. */
	@Synchronized
	fun save(cacheDir: File, lines: List<Line>) {
		val file = fileFor(cacheDir)
		try {
			file.parentFile?.mkdirs()
			val tmp = File(file.parentFile, "picks.tmp")
			tmp.writeText(lines.joinToString("\n") { it.write() } + "\n", Charsets.UTF_8)
			tmp.renameTo(file)
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	// ---------- typed views over the ledger ----------

	fun pickedEntries(cacheDir: File): List<Line> =
		load(cacheDir).filter { it.kind == LineKind.PICKED }

	fun manualEntries(cacheDir: File): List<Line> =
		load(cacheDir).filter { it.kind == LineKind.MANUAL }

	fun bannedKeys(cacheDir: File): Set<String> =
		load(cacheDir).mapNotNullTo(LinkedHashSet()) { if (it.kind == LineKind.BANNED) it.key else null }

	fun strikeMap(cacheDir: File): Map<String, Int> = strikeMapFrom(load(cacheDir))

	/** The strike view over an already-loaded ledger (the controller holds
	 *  the lines; no second disk read). */
	fun strikeMapFrom(lines: List<Line>): Map<String, Int> =
		lines.mapNotNull { if (it.kind == LineKind.STRIKES) it.key to it.strikes else null }
			.toMap(linkedMapOf())

	// ---------- mutations ----------

	@Synchronized
	fun mutate(cacheDir: File, block: (MutableList<Line>) -> Unit) {
		val lines = load(cacheDir)
		block(lines)
		save(cacheDir, lines)
	}

	/** Matches ProxyPoolController.proxyKeyOf: ProxyEntry.toString()
	 *  ("SCHEME:host:port"). One key format across memory and disk. */
	fun keyOf(entry: ProxyListFetcher.ProxyEntry): String = entry.toString()

	/** Add-or-update one typed line (kind+key is the identity). */
	fun upsert(cacheDir: File, line: Line) {
		mutate(cacheDir) { lines ->
			val idx = lines.indexOfFirst { it.kind == line.kind && it.key == line.key }
			if (idx >= 0) lines[idx] = line else lines.add(line)
		}
	}

	fun remove(cacheDir: File, kind: LineKind, key: String) {
		mutate(cacheDir) { lines -> lines.removeAll { it.kind == kind && it.key == key } }
	}

	fun clearBans(cacheDir: File) {
		mutate(cacheDir) { lines -> lines.removeAll { it.kind == LineKind.BANNED } }
	}

	fun ban(cacheDir: File, entry: ProxyListFetcher.ProxyEntry) {
		val line = Line(LineKind.BANNED, "").apply {
			scheme = entry.scheme; host = entry.host; port = entry.port
		}
		upsert(cacheDir, line)
	}

	fun unban(cacheDir: File, entry: ProxyListFetcher.ProxyEntry) {
		remove(cacheDir, LineKind.BANNED, keyOf(entry))
	}

	/** Strikes for one LIST key; returns the new score. */
	fun recordStrike(cacheDir: File, entry: ProxyListFetcher.ProxyEntry): Int {
		var score = 0
		mutate(cacheDir) { lines ->
			val key = keyOf(entry)
			val idx = lines.indexOfFirst { it.kind == LineKind.STRIKES && it.key == key }
			if (idx >= 0) {
				lines[idx].strikes += 1
				score = lines[idx].strikes
			} else {
				score = 1
				lines.add(Line(LineKind.STRIKES, "").apply {
					scheme = entry.scheme; host = entry.host; port = entry.port; strikes = 1
				})
			}
		}
		return score
	}

	fun clearStrike(cacheDir: File, entry: ProxyListFetcher.ProxyEntry) {
		remove(cacheDir, LineKind.STRIKES, keyOf(entry))
	}
}
