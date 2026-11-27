package org.wastaken.kotatsu.api21.core.network.proxypool

import okhttp3.Response
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

/**
 * Proxy pool (Task C) - amendment-6 challenge detection for health checks
 * and host verification probes.
 *
 * Order (per the amendment, backed by Cloudflare's official docs, fetched
 * 2026-10-06 and recorded in the pre-code report):
 *  1. `cf-mitigated: challenge` - Cloudflare sets this header on EVERY
 *     challenge page type (managed / interactive / JS / country), and
 *     "challenge" is the only valid value (docs page
 *     /cloudflare-challenges/challenge-types/challenge-pages/detect-response/).
 *  2. Fallback: the parsers' CloudFlareHelper heuristic
 *     (403/503 + known challenge markers parsed from the body). To honor
 *     the amendment's body cap the helper is invoked on a COPY whose body
 *     is a `peekBody(64 KB)` of the original - peekBody never consumes the
 *     real stream, so the caller's response stays intact and readable in
 *     full afterwards (the helper itself is not modified).
 *
 * The copy loses no detection fidelity: the helper only reads up to its own
 * parse cap anyway (Jsoup parse of the response body), and challenge
 * markers (challenge-platform script, "Just a moment") all live in the
 * first few KB of a challenge page.
 */
object PoolChallengeDetector {

	/** Amendment cap for the fallback heuristic body. */
	private const val PEEK_LIMIT_BYTES = 64L * 1024

	fun isChallenge(response: Response): Boolean {
		if (response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true) {
			return true
		}
		val peek = response.peekBody(PEEK_LIMIT_BYTES)
		val probe = response.newBuilder().body(peek).build()
		return try {
			CloudFlareHelper.checkResponseForProtection(probe) != CloudFlareHelper.PROTECTION_NOT_DETECTED
		} catch (_: Exception) {
			// a body the helper cannot parse is not evidence of a challenge
			false
		} finally {
			probe.close()
		}
	}
}
