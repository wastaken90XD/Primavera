package org.wastaken.kotatsu.api21.core.network.proxypool

import android.util.Log
import okhttp3.CookieJar
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Proxy pool (experimental) - component 3/5: the route selector.
 *
 * Amendment 1: this WRAPS ProxyProvider.selector and delegates to it; the
 * pool is completely inert whenever the delegate would do anything but
 * return NO_PROXY, whenever the mode is OFF (default), and whenever SSL
 * bypass is enabled (amendment 3).
 *
 * Behavior when active, per spec sections 3/5:
 *  - HTTPS-only: uri schemes other than https always get the DIRECT list;
 *  - manual always-direct hosts (pool forbidden list) => DIRECT;
 *  - login-cookie hosts (narrow rule) => DIRECT, with a rule-label log;
 *  - hosts holding cf_* cookies stay pool-eligible; the retry interceptor
 *    (component 4) strips those cookies from requests sent via a proxy;
 *  - FALLBACK: direct-first, except recently-reset hosts (learned mark =>
 *    pool-first, spec 5.3); ALWAYS: pool-first, direct only as the last tail.
 *
 * connectFailed() sees every route attempt that failed to connect (or reset
 * during TLS handshake); it feeds the failure-learning of the controller:
 * reset-class on a DIRECT route => host gets marked; any failure on a pool
 * route => the proxy's streak grows; TLS errors via a pool route => 24h ban
 * (spec 5.4, first half - the post-request half is component 4).
 */
class ProxyPoolSelector(
	private val settings: AppSettings,
	private val delegate: ProxySelector,
	private val cookieJar: CookieJar,
) : ProxySelector() {

	override fun select(uri: URI?): List<Proxy> {
		// anything unexpected => exact old behavior
		if (uri == null) {
			return delegate.select(null)
		}
		if (ProxyPoolController.isInert()) {
			return delegate.select(uri)
		}
		val host = uri.host?.lowercase() ?: return DIRECT_LIST
		ProxyPoolController.maybeRefreshAsync()
		if (uri.scheme?.lowercase() != "https") {
			return DIRECT_LIST // spec 3.7: never proxy non-HTTPS through the pool
		}
		if (ProxyPoolController.isForbiddenManually(host, settings.poolForbiddenHosts)) {
			return DIRECT_LIST
		}
		val verdict = ProxyPoolController.cookieVerdict(host, cookieJar)
		if (verdict.loginRule != null) {
			return DIRECT_LIST
		}
		val routes = ProxyPoolController.routesFor(host, settings.poolMode)
		return routes.ifEmpty { DIRECT_LIST }
	}

	override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
		try {
			delegate.connectFailed(uri, sa, ioe)
		} catch (t: Throwable) {
			// delegate is our own ProxyProvider selector (log-only), but never
			// let route bookkeeping crash a request
			Log.w(TAG, "delegate.connectFailed threw", t)
		}
		if (uri == null || ioe == null) {
			return
		}
		val host = uri.host?.lowercase() ?: return
		val cls = ProxyPoolController.classifyFailure(ioe)
		val routeProxy = ProxyPoolController.keyOfProxyAddress(sa)
		val wasPoolRoute = routeProxy != null && ProxyPoolController.status.healthy.any {
			ProxyPoolController.proxyKeyOf(it.entry) == routeProxy
		}
		if (wasPoolRoute && routeProxy != null) {
			if (cls == ProxyPoolController.FailureClass.TLS) {
				ProxyPoolController.reportCertBan(routeProxy, host)
			} else {
				ProxyPoolController.reportProxyFailure(routeProxy, cls, host)
			}
		} else if (cls == ProxyPoolController.FailureClass.RESET) {
			// direct route reset (connect or TLS-handshake phase, amendment 6)
			ProxyPoolController.reportHostReset(host)
		}
	}

	companion object {
		private const val TAG = "ProxyPool"
		private val DIRECT_LIST = listOf(Proxy.NO_PROXY)
	}
}
