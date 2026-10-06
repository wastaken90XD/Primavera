package org.wastaken.kotatsu.api21.core.network.proxypool

import okhttp3.Response
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

/**
 * Challenge detection for pooled responses.
 *
 * A challenge served through a proxy is not a transport failure - the chain
 * worked, Cloudflare simply refused the exit IP. Treating it as a failure would
 * rotate proxies forever; treating it as an answer would hand the caller a page
 * it cannot render. So it is its own outcome: the response is closed, the host is
 * counted, and the next route (or the existing one) is tried.
 *
 * Two checks, cheapest first:
 *
 * 1. The `cf-mitigated: challenge` response header. Cloudflare documents this as
 *    the way to identify challenge-page responses, with `challenge` as the only
 *    valid value, set for all challenge page types
 *    (developers.cloudflare.com/cloudflare-challenges/challenge-types/
 *    challenge-pages/detect-response/). It costs one header lookup and no I/O.
 *
 * 2. The detection the app already uses - the parsers library's
 *    CloudFlareHelper.checkResponseForProtection(), the same call
 *    CloudFlareInterceptor makes - reused, not reimplemented. It only ever
 *    detects on 403/503, so the status is checked here first and the body is
 *    never even peeked for other statuses; that keeps a proxied video download
 *    from being buffered.
 *
 * The body is never consumed. The helper is fed a shallow copy of the response
 * whose body is a 64 KB [Response.peekBody], which caps what it can buffer (the
 * helper itself peeks with Long.MAX_VALUE) and leaves the original response
 * intact for the caller when it is returned.
 */
object CloudflareChallenge {

	private const val HEADER_CF_MITIGATED = "cf-mitigated"
	private const val MITIGATED_CHALLENGE = "challenge"
	private const val PEEK_CAP_BYTES = 64L * 1024L

	fun isChallenge(response: Response): Boolean {
		if (MITIGATED_CHALLENGE.equals(response.header(HEADER_CF_MITIGATED), ignoreCase = true)) {
			return true
		}
		// the reused helper returns PROTECTION_NOT_DETECTED for every other status,
		// so this shortcut cannot hide a challenge
		if (response.code != 403 && response.code != 503) {
			return false
		}
		if (response.body == null) {
			return false
		}
		return runCatching {
			val probe = response.newBuilder()
				.body(response.peekBody(PEEK_CAP_BYTES))
				.build()
			CloudFlareHelper.checkResponseForProtection(probe) != CloudFlareHelper.PROTECTION_NOT_DETECTED
		}.getOrDefault(false)
	}
}
