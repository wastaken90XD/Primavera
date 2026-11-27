package org.wastaken.kotatsu.api21.core.network.cookies

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Proxy pool (Task C) - commit 4/7: the Cloudflare-filtering cookie jar
 * (amendment 5). Installed as the cookieJar of every POOLED client
 * (ProxyPoolController.pooled); the tier clients keep the raw shared jar
 * (AndroidCookieJar/PreferencesCookieJar), which is NOT modified - the
 * storage implementations stay byte-identical, so this file is removable
 * with its single call site.
 *
 * LOAD-side rule (outgoing requests through a proxy): CF-managed cookies
 * are dropped from the outgoing Cookie header. A clearance is bound to
 * the client IP that earned it; sending cf_clearance through a random
 * public proxy would impersonate the cleared IP from an IP Cloudflare
 * never validated (and would poison per-exit reputation for everyone).
 *
 * SAVE-side rule (responses arriving through a proxy): CF-managed cookies
 * are dropped BEFORE they reach storage.
 *
 * THE NON-OVERWRITE PROOF (the artifact amendment 5 asks for):
 *   Direct-path responses save through the SHARED jar only. Pooled-path
 *   responses save through this jar, which removes every CF-named cookie
 *   before delegating. Therefore the ONLY writer of Cloudflare cookies in
 *   storage is the direct path; a response that crossed a proxy can never
 *   create, mutate, or expire a Cloudflare cookie the direct path minted.
 *   (A solver pass that intentionally rewrites cf_clearance runs on the
 *   direct tier chain - the WebView solver does not use pooled clients.)
 *
 * THE NAME SET (amendment 5, verbatim):
 *   exact names: cf_clearance, __cf_bm
 *   contains:    cfuvid
 *   prefixes:    cf_chl, cf_, _cf
 * NEVER matched: csrftoken, XSRF-TOKEN, and any other non-Cloudflare
 * cookie - arbitrary apps that happen to use those names are untouched,
 * and anonymity-relevant session cookies (PHPSESSID etc.) pass BOTH ways
 * so pooled browsing keeps working.
 */
class CloudflareFilteringCookieJar(
	private val delegate: CookieJar,
) : CookieJar {

	override fun loadForRequest(url: HttpUrl): List<Cookie> =
		delegate.loadForRequest(url)
			.filterNot { matches(it.name.lowercase()) }

	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		val kept = cookies.filterNot { matches(it.name.lowercase()) }
		if (kept.isNotEmpty()) {
			delegate.saveFromResponse(url, kept)
		}
	}

	companion object {

		/** Amendment-5 matcher; input must be lowercased. */
		fun matches(nameLower: String): Boolean =
			nameLower == "cf_clearance" || nameLower == "__cf_bm" ||
				nameLower.contains("cfuvid") ||
				nameLower.startsWith("cf_chl") ||
				nameLower.startsWith("cf_") ||
				nameLower.startsWith("_cf")
	}
}
