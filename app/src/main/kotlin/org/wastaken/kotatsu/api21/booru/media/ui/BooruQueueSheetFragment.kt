package org.wastaken.kotatsu.api21.booru.media.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.booru.data.BooruPlaylistEntity
import org.wastaken.kotatsu.api21.booru.data.BooruPlaylistsRepository
import org.wastaken.kotatsu.api21.booru.media.BooruMediaImportExport
import org.wastaken.kotatsu.api21.booru.media.BooruMediaImportExport.toStored
import org.wastaken.kotatsu.api21.booru.media.BooruMediaQueue
import org.wastaken.kotatsu.api21.booru.media.BooruMediaService
import org.wastaken.kotatsu.api21.booru.media.BooruMediaType
import org.wastaken.kotatsu.api21.databinding.SheetBooruQueueBinding
import javax.inject.Inject

/**
 * Queue UI (Task 1): draggable bottom sheet showing the live media queue.
 * Rows: thumbnail, title, source, type badge, remove, drag handle (reorder);
 * tap teleports playback; swipe or the remove button drops the row. Header:
 * save-as-playlist, load playlist, overflow (clear, export queue).
 */
@AndroidEntryPoint
class BooruQueueSheetFragment :
	BottomSheetDialogFragment(),
	BooruMediaQueue.Listener {

	@Inject
	lateinit var playlistsRepository: BooruPlaylistsRepository

	private var _binding: SheetBooruQueueBinding? = null
	private val binding get() = requireNotNull(_binding)
	private var service: BooruMediaService? = null
	private val adapter = QueueAdapter()

	private val connection = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
			service = (binder as? BooruMediaService.LocalBinder)?.service
			service?.queue?.addListener(this@BooruQueueSheetFragment)
			refresh()
		}

		override fun onServiceDisconnected(name: ComponentName?) {
			service = null
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		_binding = SheetBooruQueueBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		binding.queueList.layoutManager = LinearLayoutManager(requireContext())
		binding.queueList.adapter = adapter
		ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
			ItemTouchHelper.UP or ItemTouchHelper.DOWN,
			ItemTouchHelper.START or ItemTouchHelper.END,
		) {
			override fun onMove(
				recyclerView: RecyclerView,
				viewHolder: RecyclerView.ViewHolder,
				target: RecyclerView.ViewHolder,
			): Boolean {
				val from = viewHolder.bindingAdapterPosition
				val to = target.bindingAdapterPosition
				service?.queue?.move(from, to)
				return true
			}

			override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
				service?.queue?.removeAt(viewHolder.bindingAdapterPosition)
			}
		}).attachToRecyclerView(binding.queueList)
		binding.buttonSavePlaylist.setOnClickListener { promptSavePlaylist() }
		binding.buttonLoadPlaylist.setOnClickListener { promptLoadPlaylist() }
		binding.buttonQueueOverflow.setOnClickListener { showOverflow(it) }
		BooruMediaService.start(requireContext())
		requireContext().bindService(
			Intent(requireContext(), BooruMediaService::class.java),
			connection,
			Context.BIND_AUTO_CREATE,
		)
	}

	override fun onDestroyView() {
		service?.queue?.removeListener(this)
		runCatching { context?.unbindService(connection) }
		_binding = null
		super.onDestroyView()
	}

	override fun onQueueChanged() {
		activity?.runOnUiThread { refresh() }
	}

	private fun refresh() {
		val queue = service?.queue ?: return
		binding.queueTitle.text = getString(R.string.media_queue_title) + " (" + queue.size + ")"
		binding.queueEmpty.visibility = if (queue.isEmpty) View.VISIBLE else View.GONE
		adapter.submit(queue.items, queue.index)
	}

	private fun promptSavePlaylist() {
		val queue = service?.queue ?: return
		if (queue.isEmpty) {
			toast(R.string.media_queue_empty)
			return
		}
		val input = EditText(requireContext())
		input.hint = getString(R.string.media_playlist_name_hint)
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.media_save_as_playlist)
			.setView(input)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val name = input.text.toString().ifEmpty { return@setPositiveButton }
				viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
					playlistsRepository.savePlaylist(name = name, items = queue.items)
					withContext(Dispatchers.Main) { toast(R.string.media_playlist_saved) }
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun promptLoadPlaylist() {
		viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
			val playlists = playlistsRepository.playlists()
			withContext(Dispatchers.Main) {
				if (playlists.isEmpty()) {
					toast(R.string.media_queue_empty)
					return@withContext
				}
				AlertDialog.Builder(requireContext())
					.setTitle(R.string.media_playlist_load)
					.setItems(playlists.map { it.name }.toTypedArray()) { _, which ->
						loadPlaylistIntoQueue(playlists[which].id, playlists[which].name)
					}
					.show()
			}
		}
	}

	private fun loadPlaylistIntoQueue(playlistId: Long, name: String) {
		viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
			val items = playlistsRepository.loadItems(playlistId)
			withContext(Dispatchers.Main) {
				if (items.isEmpty()) {
					toast(R.string.media_queue_empty)
					return@withContext
				}
				service?.queue?.restore(items, 0, service?.queue?.repeatMode ?: org.wastaken.kotatsu.api21.booru.media.RepeatMode.NONE)
				toast(R.string.media_import_done)
				dismiss()
			}
		}
	}

	private fun showOverflow(anchor: View) {
		val menu = PopupMenu(requireContext(), anchor)
		menu.menu.add(0, ACTION_CLEAR, 0, R.string.clear)
		menu.menu.add(0, ACTION_EXPORT, 1, R.string.media_export_queue)
		menu.setOnMenuItemClickListener { item ->
			when (item.itemId) {
				ACTION_CLEAR -> service?.stopPlaybackAndQueueClear()
				ACTION_EXPORT -> exportQueue()
			}
			true
		}
		menu.show()
	}

	private fun exportQueue() {
		val queue = service?.queue ?: return
		if (queue.isEmpty) {
			toast(R.string.media_queue_empty)
			return
		}
		val content = BooruMediaImportExport.encodeItems(
			BooruMediaImportExport.TYPE_QUEUE,
			null,
			queue.items.map { it.toStored() },
		)
		// SAF create-document is owned by the settings screen; from the sheet we
		// simply share the file content as text (same payload, user-picked target)
		runCatching {
			val share = Intent(Intent.ACTION_SEND)
				.setType(BooruMediaImportExport.MIME_JSON)
				.putExtra(Intent.EXTRA_TEXT, content)
			startActivity(Intent.createChooser(share, getString(R.string.media_export_queue)))
		}
	}

	private fun toast(resId: Int) {
		Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
	}

	// region adapter

	private inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.Holder>() {

		private var items: List<org.wastaken.kotatsu.api21.booru.media.BooruMediaItem> = emptyList()
		private var currentIndex = 0

		fun submit(newItems: List<org.wastaken.kotatsu.api21.booru.media.BooruMediaItem>, index: Int) {
			items = ArrayList(newItems)
			currentIndex = index
			notifyDataSetChanged()
		}

		override fun getItemCount() = items.size

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val view = LayoutInflater.from(parent.context).inflate(R.layout.item_booru_queue, parent, false)
			return Holder(view)
		}

		override fun onBindViewHolder(holder: Holder, position: Int) {
			holder.bind(items[position], position == currentIndex)
		}

		inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

			fun bind(item: org.wastaken.kotatsu.api21.booru.media.BooruMediaItem, isCurrent: Boolean) {
				itemView.findViewById<android.widget.TextView>(R.id.title).text = item.title
				itemView.findViewById<android.widget.TextView>(R.id.sourceName).text = item.source.name
				itemView.findViewById<android.widget.TextView>(R.id.mediaBadge).text =
					if (item.mediaType == BooruMediaType.GIF) {
						getString(R.string.media_gif_badge)
					} else {
						getString(R.string.media_tap_play_in_app)
					}
				itemView.isSelected = isCurrent
				itemView.alpha = if (isCurrent) 1f else 0.75f
				val thumb = itemView.findViewById<org.wastaken.kotatsu.api21.image.ui.CoverImageView>(R.id.thumbnail)
				thumb.setImageAsync(item.thumbnailUrl, item.source)
				itemView.findViewById<View>(R.id.buttonRemove).setOnClickListener {
					val pos = bindingAdapterPosition
					if (pos != RecyclerView.NO_POSITION) service?.queue?.removeAt(pos)
				}
				itemView.setOnClickListener {
					val pos = bindingAdapterPosition
					if (pos != RecyclerView.NO_POSITION) {
						service?.playIndex(pos)
						dismiss()
					}
				}
			}
		}
	}

	// endregion

	companion object {

		private const val ACTION_CLEAR = 1
		private const val ACTION_EXPORT = 2

		fun show(context: androidx.fragment.app.FragmentActivity) {
			BooruQueueSheetFragment().show(context.supportFragmentManager, "booru_queue")
		}
	}
}
