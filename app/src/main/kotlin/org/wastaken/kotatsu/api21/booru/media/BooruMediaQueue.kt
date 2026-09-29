package org.wastaken.kotatsu.api21.booru.media

import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * One playable booru post in the queue / playlist system.
 *
 * Mirrors NewPipe's PlayQueueItem shape (title + url + origin) extended with
 * the media type and the thumbnail the queue UI needs. [source] is kept as a
 * live object at runtime; persistence stores only `source.name` (see
 * [StoredBooruMediaItem]) and resolves it back with the same idiom Kotatsu
 * uses elsewhere (`MangaSource(name)` + UnknownMangaSource guard).
 */
data class BooruMediaItem(
	val id: Long,
	val url: String,
	val source: MangaSource,
	val title: String,
	val thumbnailUrl: String?,
	val mediaType: BooruMediaType,
	val addedAt: Long = System.currentTimeMillis(),
) {

	companion object {

		/** Stable id derived from the URL: dedupes double-adds of the same post. */
		fun uidOf(url: String): Long = url.hashCode().toLong()
	}
}

/** JSON mirror of [BooruMediaItem] for SharedPreferences and import/export. */
@Serializable
data class StoredBooruMediaItem(
	val url: String,
	val source: String,
	val title: String,
	val thumbnail_url: String? = null,
	val media_type: String,
)

/**
 * Ordered, index-tracked play queue modeled after NewPipe's PlayQueue:
 * a list plus an index plus a repeat mode, mutated only through the ops
 * below, which all emit [Listener.onQueueChanged] so persistence and UI
 * observers stay in sync without invalidate-dirty bookkeeping.
 */
class BooruMediaQueue {

	interface Listener {
		fun onQueueChanged()
	}

	private val mutableItems = ArrayList<BooruMediaItem>()
	private val listeners = ArrayList<Listener>()
	private var lockNotifications = false

	var index: Int = 0
		private set

	var repeatMode: RepeatMode = RepeatMode.NONE
		private set

	val items: List<BooruMediaItem>
		get() = mutableItems

	val size: Int
		get() = mutableItems.size

	val isEmpty: Boolean
		get() = mutableItems.isEmpty()

	fun addListener(listener: Listener) {
		if (listener !in listeners) listeners.add(listener)
	}

	fun removeListener(listener: Listener) {
		listeners.remove(listener)
	}

	fun current(): BooruMediaItem? = mutableItems.getOrNull(index)

	/** Appends [item]; returns the position it was inserted at. No dedupe by design. */
	fun add(item: BooruMediaItem): Int {
		mutableItems.add(item)
		if (mutableItems.size == 1) index = 0
		notifyChanged()
		return mutableItems.lastIndex
	}

	/** Inserts right after the current position (NewPipe's "enqueue next"). */
	fun addNext(item: BooruMediaItem): Int {
		val at = (index + 1).coerceAtMost(mutableItems.size)
		mutableItems.add(at, item)
		if (at <= index) index++
		notifyChanged()
		return at
	}

	fun insertAll(position: Int, newItems: Collection<BooruMediaItem>) {
		if (newItems.isEmpty()) return
		val at = position.coerceIn(0, mutableItems.size)
		mutableItems.addAll(at, newItems)
		if (mutableItems.size == newItems.size) index = 0 else if (at <= index) index += newItems.size
		notifyChanged()
	}

	fun removeAt(position: Int): BooruMediaItem? {
		if (position !in mutableItems.indices) return null
		val removed = mutableItems.removeAt(position)
		index = index.coerceAtMost(mutableItems.size - 1).coerceAtLeast(0)
		notifyChanged()
		return removed
	}

	fun move(from: Int, to: Int) {
		if (from !in mutableItems.indices || to !in mutableItems.indices || from == to) return
		val item = mutableItems.removeAt(from)
		mutableItems.add(to, item)
		index = when (index) {
			from -> to
			in (from + 1)..to -> index - 1
			in to until from -> index + 1
			else -> index
		}
		notifyChanged()
	}

	/** Fisher-Yates shuffle of the tail; the current item stays pinned at its position. */
	fun shuffle() {
		if (mutableItems.size < 3) return
		val current = current() ?: return
		val tail = mutableItems.toMutableList()
		tail.remove(current)
		for (i in tail.size - 1 downTo 1) {
			val j = (Math.random() * (i + 1)).toInt()
			val tmp = tail[i]
			tail[i] = tail[j]
			tail[j] = tmp
		}
		mutableItems.clear()
		mutableItems.add(current)
		mutableItems.addAll(tail)
		index = 0
		notifyChanged()
	}

	fun setRepeatMode(mode: RepeatMode) {
		repeatMode = mode
		notifyChanged()
	}

	fun jumpTo(position: Int): BooruMediaItem? {
		if (position !in mutableItems.indices) return null
		index = position
		notifyChanged()
		return current()
	}

	/** Index [next] should play, or -1 when the queue is exhausted. */
	fun nextIndex(): Int {
		if (mutableItems.isEmpty()) return -1
		return when {
			repeatMode == RepeatMode.ONE -> index
			index + 1 < mutableItems.size -> index + 1
			repeatMode == RepeatMode.ALL -> 0
			else -> -1
		}
	}

	fun previousIndex(): Int {
		if (mutableItems.isEmpty()) return -1
		return when {
			index > 0 -> index - 1
			repeatMode == RepeatMode.ALL -> mutableItems.lastIndex
			else -> index
		}
	}

	fun clear() {
		mutableItems.clear()
		index = 0
		notifyChanged()
	}

	/** Replaces the whole queue; emits exactly one change notification. */
	fun restore(newItems: List<BooruMediaItem>, newIndex: Int, newRepeatMode: RepeatMode) {
		lockNotifications = true
		mutableItems.clear()
		mutableItems.addAll(newItems)
		index = newIndex.coerceIn(0, (mutableItems.size - 1).coerceAtLeast(0))
		repeatMode = newRepeatMode
		lockNotifications = false
		notifyChanged()
	}

	private fun notifyChanged() {
		if (lockNotifications) return
		for (listener in listeners.toList()) {
			listener.onQueueChanged()
		}
	}
}
