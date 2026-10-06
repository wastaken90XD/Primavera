package org.wastaken.kotatsu.api21.core.network.proxypool

import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.util.Predicate
import okhttp3.Cookie
import okhttp3.HttpUrl
import org.wastaken.kotatsu.api21.core.network.cookies.MutableCookieJar

/**
 * What a pooled request may carry from the shared jar.
 *
 * The Cloudflare filter is NOT part of this choice: it is unconditional, on load
 * and on save, in every mode (see [FilteringCookieJar]). This setting decides
 * what happens to the user's OWN cookies - session and account cookies in
 * particular - when a request leaves through somebody else's proxy.
 */
enum class PoolCookieMode {
	/** Default: Cloudflare cookies and account-style cookies stay home. */
	AUTO,

	/** Everything except Cloudflare cookies travels. The user accepts the risk. */
	ALL,

	/** Pooled requests are sent with no cookies at all. */
	NONE,
}

/**
 * Cookie jar for pooled clients only. The base/manga/video tiers keep the real
 * shared jar; this wrapper sits between a pooled client and it.
 *
 * Two rules, and they are the reason this class exists:
 *
 * 1. UNCONDITIONAL, on load AND save: Cloudflare cookies never travel through a
 *    pool proxy and never come back through one. A clearance minted for a proxy's
 *    exit IP is worthless to the device and actively harmful - written into the
 *    shared jar it would sideline the clearance the direct route minted for the
 *    user's own IP. The save path is the guarantee: [saveFromResponse] filters
 *    first and returns without touching the delegate when nothing is left, so a
 *    proxied Set-Cookie for a filtered name physically cannot reach
 *    CookieManager.setCookie().
 *
 * 2. Mode-dependent, on load only: in AUTO, account-style cookies stay home as
 *    well, so a signed-in session is never presented to a stranger's proxy. NONE
 *    sends no cookies at all.
 *
 * The filtered set is a deliberate SUPERSET of Cloudflare's cookie names:
 * cf_clearance, __cf_bm/_cf_bm, cfuvid, and any name starting with cf_chl/cfchl,
 * cf, __cf or _cf. It is intentionally NOT the parsers library's
 * CloudFlareHelper.isCloudFlareCookie(), because that helper also matches
 * "csrftoken" - a Django CSRF token with nothing to do with Cloudflare, which
 * must keep working. csrftoken, XSRF-TOKEN, PHPSESSID and every other
 * non-Cloudflare cookie pass through untouched.
 *
 * Known cost of the superset: ColdFusion's CFID/CFTOKEN also start with "cf" and
 * are dropped. Pooled requests are anonymous by design, so losing a ColdFusion
 * session on a proxied request is accepted; losing a Cloudflare clearance is not.
 */
class FilteringCookieJar(
	private val delegate: MutableCookieJar,
	private val mode: () -> PoolCookieMode,
) : MutableCookieJar {

	@WorkerThread
	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val cookies = delegate.loadForRequest(url)
		if (cookies.isEmpty()) {
			return cookies
		}
		return when (mode()) {
			PoolCookieMode.NONE -> emptyList()
			PoolCookieMode.ALL -> cookies.filterNot { isCloudflareCookie(it.name) }
			PoolCookieMode.AUTO -> cookies.filterNot {
				isCloudflareCookie(it.name) || isAccountCookie(it.name)
			}
		}
	}

	/**
	 * The save path. Filtered names are removed BEFORE the delegate is called and
	 * the delegate is skipped entirely when nothing survives, so no proxied
	 * response can overwrite a directly-minted Cloudflare cookie in the shared
	 * jar - not for this host, not for any other.
	 */
	@WorkerThread
	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		if (cookies.isEmpty()) {
			return
		}
		val kept = cookies.filterNot { isCloudflareCookie(it.name) }
		if (kept.isEmpty()) {
			logDropped(url, cookies.size)
			return
		}
		if (kept.size != cookies.size) {
			logDropped(url, cookies.size - kept.size)
		}
		delegate.saveFromResponse(url, kept)
	}

	@WorkerThread
	override fun removeCookies(url: HttpUrl, predicate: Predicate<Cookie>?) {
		delegate.removeCookies(url, predicate)
	}

	@WorkerThread
	override fun insertCookie(url: HttpUrl, rawSetCookie: String) {
		delegate.insertCookie(url, rawSetCookie)
	}

	override suspend fun clear(): Boolean = delegate.clear()

	/** Host name only - never a cookie name or value. */
	private fun logDropped(url: HttpUrl, count: Int) {
		Log.i(TAG, "host=${url.host} dropped $count Cloudflare cookie(s) from a proxied response")
	}

	companion object {

		private const val TAG = "ProxyPool"

		private val ACCOUNT_COOKIE_NAMES = setOf(
			// BooruParser's own account-cookie notion, without pulling parser
			// internals into app code
			"user_id", "pass_hash", "password_hash", "login",
			"remember_login", "remember_me", "remember_webtoken", "remember_web",
			"auth_token", "access_token", "api_token", "connect.sid",
		)
		private const val WORDPRESS_PREFIX = "wordpress_logged_in_"
		private const val PHPBB_PREFIX = "phpbb3_"

		/**
		 * Superset rule. Lower-cased prefixes: "cf" alone already covers
		 * cf_clearance, cfuvid, cf_chl_* and cfchl_*; "_cf" and "__cf" cover the
		 * two _cf_bm spellings. Nothing else matches - csrftoken starts with "cs".
		 */
		fun isCloudflareCookie(name: String): Boolean {
			val lower = name.lowercase()
			return lower.startsWith("cf") || lower.startsWith("_cf") || lower.startsWith("__cf")
		}

		fun isAccountCookie(name: String): Boolean {
			val lower = name.lowercase()
			return lower in ACCOUNT_COOKIE_NAMES ||
				lower.startsWith(WORDPRESS_PREFIX) ||
				lower.startsWith(PHPBB_PREFIX)
		}
	}
}
