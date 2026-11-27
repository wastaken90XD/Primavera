package org.wastaken.kotatsu.api21.reader.ui.media

/**
 * What a reader page actually is (TSD-BOORU-MMBD-001 section 5). UNRESOLVED is
 * the pre-classification state: the URL carried no decisive signal and the
 * Content-Type / magic-byte stages have not run yet.
 */
enum class ReaderMediaType {
	UNRESOLVED,
	IMAGE,
	GIF,
	VIDEO,
}
