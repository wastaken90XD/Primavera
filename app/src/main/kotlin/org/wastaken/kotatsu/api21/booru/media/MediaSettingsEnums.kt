package org.wastaken.kotatsu.api21.booru.media

/**
 * Enums backing the "Media player" settings screen (Task 5). All values are
 * persisted by name via AppSettings' enum helpers, so names are part of the
 * persisted format: reorder freely, never rename without a migration note.
 */

enum class BooruMediaType { GIF, VIDEO }

enum class GifTapAction { INLINE, OPEN_DETAIL }

enum class VideoTapAction { PLAY_IN_APP, ADD_TO_QUEUE, OPEN_DETAIL }

enum class DefaultPlayerMode { FULLSCREEN, INLINE, FLOATING }

enum class FloatingWindowSize { SMALL, MEDIUM, LARGE }

enum class FloatingWindowPosition { TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

enum class AspectRatioMode { FIT, FILL, CROP, RATIO_16_9, RATIO_4_3 }

enum class RepeatMode { NONE, ONE, ALL }

enum class BooruLongPressAction { DOWNLOAD, MENU, SELECT }

enum class BooruVideoEngine { LIBVLC, SYSTEM }
