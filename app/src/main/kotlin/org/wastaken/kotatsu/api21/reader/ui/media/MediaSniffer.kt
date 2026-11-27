package org.wastaken.kotatsu.api21.reader.ui.media

/**
 * Three-stage media classification for reader pages (TSD-BOORU-MMBD-001 §3.1),
 * for extension-less or token-signed CDN URLs where the plain URL checks in
 * BooruMedia.kt cannot decide:
 *
 *   1. HTTP Content-Type header (when a response is available)
 *   2. File extension / query format hint of the URL
 *   3. Magic bytes of the first 16-32 payload bytes
 *
 * Pure and network-free: the caller supplies whatever evidence it managed to
 * obtain; any stage may decline and the next one runs. Default fallback is
 * IMAGE so a failed probe keeps the classic page pipeline behavior.
 */
object MediaSniffer {

	private val GIF87A = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
	private val GIF89A = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
	private val WEBM_MKV = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
	private val MP4_FTYP = byteArrayOf(0x66, 0x74, 0x79, 0x70) // "ftyp" at offset 4

	// common image signatures so stage 3 can also CONFIRM a static image
	private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
	private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
	private val RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46) // WEBP: RIFF....WEBP
	private val WEBP_SIG = byteArrayOf(0x57, 0x45, 0x42, 0x50) // "WEBP" at offset 8

	fun sniffType(headerBytes: ByteArray, contentType: String?, url: String): ReaderMediaType {
		// 1. MIME header check
		if (contentType != null) {
			val mime = contentType.substringBefore(';').trim().lowercase()
			when {
				mime.startsWith("video/") -> return ReaderMediaType.VIDEO
				mime == "image/gif" -> return ReaderMediaType.GIF
				mime.startsWith("image/") -> return ReaderMediaType.IMAGE
			}
		}
		// 2. Extension / query-hint check (the shared house rules: path-only,
		// trailing slash trimmed, "gifv" alias, mid-name ".ext" fallbacks)
		when {
			url.looksLikeGif() -> return ReaderMediaType.GIF
			url.looksLikeVideo() -> return ReaderMediaType.VIDEO
		}
		// 3. Magic bytes
		if (headerBytes.size >= 12) {
			if (headerBytes.startsWith(GIF87A) || headerBytes.startsWith(GIF89A)) return ReaderMediaType.GIF
			if (headerBytes.startsWith(WEBM_MKV)) return ReaderMediaType.VIDEO
			if (headerBytes.sliceArray(4..7).contentEquals(MP4_FTYP)) return ReaderMediaType.VIDEO
			if (headerBytes.startsWith(JPEG_SOI) || headerBytes.startsWith(PNG_SIG)) return ReaderMediaType.IMAGE
			if (headerBytes.startsWith(RIFF) && headerBytes.sliceArray(8..11).contentEquals(WEBP_SIG)) {
				return ReaderMediaType.IMAGE
			}
		}
		return ReaderMediaType.IMAGE
	}

	private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
		if (size < prefix.size) return false
		for (i in prefix.indices) {
			if (this[i] != prefix[i]) return false
		}
		return true
	}
}
