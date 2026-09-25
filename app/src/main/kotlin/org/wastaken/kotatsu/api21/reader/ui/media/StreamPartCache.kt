package org.wastaken.kotatsu.api21.reader.ui.media

import androidx.annotation.WorkerThread
import java.io.File
import java.io.RandomAccessFile

/**
 * Transient spill-over store for [VideoStreamProxy]: as the pump streams the
 * remote file, every byte is additionally spooled into rotating ".part" chunk
 * files on disk. On RAM-starved devices the parts ARE the read-ahead: the
 * proxy serves consumers straight from disk (in-memory ring stays empty),
 * back-pressuring the pump only once it leads the player by the whole part
 * budget - pause then keeps megabytes of cushion without touching the heap.
 * The small-scale version of ExoPlayer SimpleCache's spans, minus its index
 * (the spool is one contiguous stream).
 *
 * Hygiene contract (player UX spec):
 *  - [clear] leaves NO .part file behind; [begin] first purges the directory
 *    of anything - including parts orphaned by a process death - so no part
 *    ever exists outside a live stream.
 *  - [VideoStreamProxy] calls [clear] on start()/stop(): parts live exactly
 *    as long as the stream and vanish on new video / player stop.
 *
 * Layout: part i covers [base + i*partSizeBytes, base + (i+1)*partSizeBytes)
 * of the REMOTE file, named part_%06d.part by that absolute index, so spools
 * that start mid-file (seek / resume) need no offset-zero math. Once more
 * than [maxParts] parts exist the lowest-index (oldest) one is deleted, so
 * the retained window always trails the pump head; with the memory share at
 * a quarter of the budget the ring's content is guaranteed to sit inside the
 * retained window.
 *
 * Writes are sequential appends from the single pump thread; reads come from
 * consumer threads. All state is guarded by the intrinsic lock; callers may
 * hold the proxy's lock - the lock order is always proxy -> this class.
 */
class StreamPartCache(
	private val directory: File,
	val partSizeBytes: Long,
	val maxParts: Int,
) {

	private val lock = Object()
	private var base = 0L
	private var writePos = 0L

	private var writer: RandomAccessFile? = null
	private var writerIndex = -1L
	private var reader: RandomAccessFile? = null
	private var readerIndex = -1L

	/** Indices of parts currently on disk; retained window = newest [maxParts]. */
	private val present = HashSet<Long>()

	/** Disk gone full / unwritable: degrade to plain streaming without parts. */
	private var writeDisabled = false

	/** Disk budget: what the pump may spool ahead of the player before throttling. */
	val totalBytes: Long
		get() = partSizeBytes * maxParts

	@WorkerThread
	fun begin(baseOffset: Long) {
		synchronized(lock) {
			clearLocked()
			directory.mkdirs()
			base = baseOffset
			writePos = baseOffset
			writeDisabled = false
		}
	}

	/** Append [length] bytes of [data] (starting at [offset]) at the pump head. */
	@WorkerThread
	fun append(data: ByteArray, offset: Int, length: Int) {
		if (length <= 0) {
			return
		}
		synchronized(lock) {
			if (writeDisabled) {
				return
			}
			var off = offset
			var remaining = length
			try {
				while (remaining > 0) {
					val index = partIndex(writePos)
					if (writerIndex != index) {
						openWriter(index)
					}
					val w = writer ?: return
					val inPart = (writePos - baseFor(index)).toInt()
					val n = minOf(remaining, (partSizeBytes - inPart).toInt())
					w.seek(inPart.toLong())
					w.write(data, off, n)
					off += n
					remaining -= n
					writePos += n
				}
			} catch (e: Exception) {
				// disk pressure must never kill playback; keep streaming, drop parts
				writeDisabled = true
			}
		}
	}

	/** Exclusive remote offset where contiguous coverage currently ends. */
	fun endPosition(): Long = synchronized(lock) { writePos }

	/** True when [position] lies inside the retained, contiguous window. */
	fun contains(position: Long): Boolean = synchronized(lock) {
		val oldest = present.minOrNull() ?: return false // no parts on disk, nothing covered
		position >= baseFor(oldest) && position < writePos
	}

	/**
	 * Read up to [length] bytes at remote [position] into [dest] at [offset].
	 * Never crosses a part boundary in one call; returns 0 when not covered.
	 */
	@WorkerThread
	fun read(position: Long, dest: ByteArray, offset: Int, length: Int): Int {
		synchronized(lock) {
			if (!contains(position)) {
				return 0
			}
			val index = partIndex(position)
			val inPart = (position - baseFor(index)).toInt()
			val n = minOf(
				length,
				(partSizeBytes - inPart).toInt(),
				(writePos - position).toInt(),
			)
			if (readerIndex != index) {
				openReader(index)
			}
			val r = reader ?: return 0
			return runCatching {
				r.seek(inPart.toLong())
				var total = 0
				while (total < n) {
					val rd = r.read(dest, offset + total, n - total)
					if (rd <= 0) {
						break
					}
					total += rd
				}
				total
			}.getOrDefault(0)
		}
	}

	@WorkerThread
	fun clear() {
		synchronized(lock) {
			clearLocked()
		}
	}

	private fun clearLocked() {
		runCatching { writer?.close() }
		writer = null
		writerIndex = -1L
		runCatching { reader?.close() }
		reader = null
		readerIndex = -1L
		present.clear()
		// SimpleCache rule: the dir is ours alone, purge unrecognized leftovers
		directory.listFiles()?.forEach { file -> file.deleteRecursively() }
		base = 0L
		writePos = 0L
		writeDisabled = false
	}

	private fun partIndex(position: Long): Long = (position - base) / partSizeBytes

	private fun baseFor(index: Long): Long = base + index * partSizeBytes

	private fun partFile(index: Long) = File(directory, "part_%06d.part".format(index))

	private fun openWriter(index: Long) {
		runCatching { writer?.close() }
		writer = RandomAccessFile(partFile(index), "rw")
		writerIndex = index
		present.add(index)
		while (present.size > maxParts) {
			val oldest = present.minOrNull() ?: break
			if (oldest == index) {
				break // only the freshly opened part is left: nothing to evict
			}
			present.remove(oldest)
			runCatching { partFile(oldest).delete() }
			if (readerIndex == oldest) {
				runCatching { reader?.close() }
				reader = null
				readerIndex = -1L
			}
		}
	}

	private fun openReader(index: Long) {
		runCatching { reader?.close() }
		reader = null
		readerIndex = -1L
		val file = partFile(index)
		if (!file.exists()) {
			return
		}
		reader = runCatching { RandomAccessFile(file, "r") }.getOrNull()
		if (reader != null) {
			readerIndex = index
		}
	}
}
