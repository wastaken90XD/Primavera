package org.wastaken.kotatsu.api21.core.network.proxypool

import okhttp3.Authenticator
import okhttp3.OkHttpClient
import org.wastaken.kotatsu.api21.core.network.CloudFlareInterceptor
import org.wastaken.kotatsu.api21.core.network.CommonHeaders
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * The pooled clients: one per distinct route, at most [MAX_CLIENTS], least
 * recently used first out.
 *
 * A pooled client is derived from the TIER client that the request came in on
 * (`tier.newBuilder()`), which is what makes it cheap and correct:
 *  - the interceptor INSTANCES are shared, so GZip, rate limiting, cache limits
 *    and common headers behave identically on a pooled request;
 *  - the dispatcher and the connection pool are shared, so pooling does not
 *    multiply sockets or threads;
 *  - only two interceptors come out: [CloudFlareInterceptor] (a challenge must
 *    not be answered from a proxy's IP) and the routing interceptor itself
 *    (otherwise the derived client would route again, forever).
 *
 * Rate limiting is worth spelling out: RateLimitInterceptor holds NO state - it
 * turns a 429 into TooManyRequestExceptions and nothing else - and the routing
 * interceptor returns a pooled response without calling chain.proceed(), so the
 * outer chain never runs its copy. One request through the pool therefore passes
 * exactly one rate limiter, the shared instance on the pooled client.
 *
 * Clients are built once per route and reused; nothing here ever builds a client
 * per request.
 */
object PooledClients {

	private const val MAX_CLIENTS = 32

	private val tiers = ConcurrentHashMap<PoolCategory, OkHttpClient>()

	/** accessOrder = true, so get() is what makes it an LRU. */
	private val cache = object : LinkedHashMap<String, OkHttpClient>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OkHttpClient>?): Boolean =
			size > MAX_CLIENTS
	}

	/** Called from NetworkModule for every tier the routing interceptor is on. */
	fun registerTier(category: PoolCategory, client: OkHttpClient) {
		tiers[category] = client
	}

	/** The pooled client for [route], built from [category]'s tier on first use. */
	@Synchronized
	fun clientFor(category: PoolCategory, route: PoolRoute): OkHttpClient? {
		cache[route.key]?.let { return it }
		val tier = tiers[category] ?: return null
		val client = build(tier, route)
		cache[route.key] = client
		return client
	}

	/** Drops every derived client (routes changed, pool cleared, rotation). */
	@Synchronized
	fun clear() {
		cache.clear()
	}

	private fun build(tier: OkHttpClient, route: PoolRoute): OkHttpClient {
		val builder = tier.newBuilder()
		builder.interceptors().removeAll {
			it is CloudFlareInterceptor || it is ProxyPoolRoutingInterceptor
		}
		builder.proxySelector(selectorFor(route.proxy))
		// Relay routes ONLY: the pooled client authenticates to our own relay with
		// this run's secret. Single-proxy routes keep the inherited (static proxy)
		// authenticator, which pool proxies never challenge, so it stays inert -
		// ProxyProvider and its authenticator are untouched.
		route.relayCredential?.let { credential ->
			builder.proxyAuthenticator(
				Authenticator { _, response ->
					if (response.request.header(CommonHeaders.PROXY_AUTHORIZATION) != null) {
						null
					} else {
						response.request.newBuilder()
							.header(CommonHeaders.PROXY_AUTHORIZATION, credential)
							.build()
					}
				},
			)
		}
		return builder.build()
	}

	private fun selectorFor(proxy: Proxy): ProxySelector = object : ProxySelector() {
		override fun select(uri: URI?): List<Proxy> = listOf(proxy)
		override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
	}
}
