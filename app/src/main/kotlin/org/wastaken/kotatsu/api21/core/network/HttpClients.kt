package org.wastaken.kotatsu.api21.core.network

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MangaHttpClient

/**
 * Unwrapped media/playback traffic (Booru video etc.): inherits the base
 * client INCLUDING the static ProxyProvider selector+authenticator, but is
 * re-pointed at the static selector so the experimental proxy pool never
 * serves sustained media throughput. Pool component 3/5.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VideoHttpClient
