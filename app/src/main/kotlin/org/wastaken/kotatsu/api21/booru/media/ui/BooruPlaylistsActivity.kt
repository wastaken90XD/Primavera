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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.booru.data.BooruPlaylistEntity
import org.wastaken.kotatsu.api21.booru.data.BooruPlaylistsRepository
import org.wastaken.kotatsu.api21.booru.media.BooruMediaImportExport
import org.wastaken.kotatsu.api21.booru.media.BooruMediaImportExport.toStored
import org.wastaken.kotatsu.api21.booru.media.BooruMediaService
import org.wastaken.kotatsu.api21.booru.media.RepeatMode
import org.wastaken.kotatsu.api21.core.ui.BaseFullscreenActivity
import org.wastaken.kotatsu.api21.databinding.ActivityBooruPlaylistsBinding
import javax.inject.Inject

/**
 * Playlists management (Task 1): list, tap = load into the live queue (+play),
 * swipe = delete, long-press = rename / export as self-contained JSON.
 * Opened from Settings → Media player → Playlists.
 */
@AndroidEntryPoint
class BooruPlaylistsActivity :
	BaseFullscreenActivity<ActivityBooruPlaylistsBinding>() {

	@Inject
	lateinit var repository: BooruPlaylistsRepository

	private val adapter = PlaylistsAdapter()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityBooruPlaylistsBinding.inflate(layoutInflater))
		supportActionBar?.setTitle(R.string.media_playlists)
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
		viewBinding.recyclerView.adapter = adapter
		ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {
			override fun onMove(
				recyclerView: RecyclerView,
				viewHolder: RecyclerView.ViewHolder,
				target: RecyclerView.ViewHolder,
			): Boolean = false

			override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
				val playlist = adapter.itemAt(viewHolder.bindingAdapterPosition) ?: return
				deletePlaylist(playlist)
			}
		}).attachToRecyclerView(viewBinding.recyclerView)
	}

	override fun onResume() {
		super.onResume()
		reload()
	}

	override fun onSupportNavigateUp(): Boolean {
		finish()
		return true
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.root.updatePadding(
			left = systemBars.left,
			top = systemBars.top,
			right = systemBars.right,
			bottom = systemBars.bottom,
		)
		return insets
	}

	private fun reload() {
		lifecycleScope.launch(Dispatchers.IO) {
			val playlists = repository.playlists()
			withContext(Dispatchers.Main) {
				adapter.submit(playlists)
				viewBinding.emptyView.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
			}
		}
	}

	private fun deletePlaylist(playlist: BooruPlaylistEntity) {
		lifecycleScope.launch(Dispatchers.IO) {
			repository.delete(playlist.id)
			withContext(Dispatchers.Main) {
				Toast.makeText(this@BooruPlaylistsActivity, R.string.media_playlist_deleted, Toast.LENGTH_SHORT).show()
				reload()
			}
		}
	}

	private fun renamePlaylist(playlist: BooruPlaylistEntity) {
		val input = EditText(this)
		input.setText(playlist.name)
		AlertDialog.Builder(this)
			.setTitle(R.string.media_playlist_rename)
			.setView(input)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val name = input.text.toString().ifEmpty { return@setPositiveButton }
				lifecycleScope.launch(Dispatchers.IO) {
					repository.rename(playlist.id, name)
					withContext(Dispatchers.Main) { reload() }
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun loadPlaylist(playlist: BooruPlaylistEntity) {
		lifecycleScope.launch(Dispatchers.IO) {
			val items = repository.loadItems(playlist.id)
			withContext(Dispatchers.Main) {
				if (items.isEmpty()) {
					Toast.makeText(this@BooruPlaylistsActivity, R.string.media_queue_empty, Toast.LENGTH_SHORT).show()
					return@withContext
				}
				loadIntoService(items)
			}
		}
	}

	private fun loadIntoService(items: List<org.wastaken.kotatsu.api21.booru.media.BooruMediaItem>) {
		BooruMediaService.start(this)
		bindService(
			Intent(this, BooruMediaService::class.java),
			object : ServiceConnection {
				override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
					val service = (binder as? BooruMediaService.LocalBinder)?.service
					service?.queue?.restore(items, 0, RepeatMode.NONE)
					runCatching { unbindService(this) }
					service?.let { BooruPlayerActivity.start(this@BooruPlaylistsActivity, items.first()) }
				}

				override fun onServiceDisconnected(name: ComponentName?) = Unit
			},
			Context.BIND_AUTO_CREATE,
		)
	}

	private fun exportPlaylist(playlist: BooruPlaylistEntity) {
		lifecycleScope.launch(Dispatchers.IO) {
			val items = repository.loadItems(playlist.id)
			if (items.isEmpty()) {
				withContext(Dispatchers.Main) { toast(R.string.media_queue_empty) }
				return@launch
			}
			val content = BooruMediaImportExport.encodeItems(
				BooruMediaImportExport.TYPE_PLAYLIST,
				playlist.name,
				items.map { it.toStored() },
			)
			withContext(Dispatchers.Main) {
				runCatching {
					val share = Intent(Intent.ACTION_SEND)
						.setType(BooruMediaImportExport.MIME_JSON)
						.putExtra(Intent.EXTRA_TEXT, content)
					startActivity(Intent.createChooser(share, getString(R.string.media_playlist_export)))
				}
			}
		}
	}

	private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

	// region adapter

	private inner class PlaylistsAdapter : RecyclerView.Adapter<PlaylistsAdapter.Holder>() {

		private var items: List<BooruPlaylistEntity> = emptyList()

		fun submit(newItems: List<BooruPlaylistEntity>) {
			items = newItems
			notifyDataSetChanged()
		}

		fun itemAt(position: Int): BooruPlaylistEntity? = items.getOrNull(position)

		override fun getItemCount() = items.size

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val view = LayoutInflater.from(parent.context).inflate(R.layout.item_booru_playlist, parent, false)
			return Holder(view)
		}

		override fun onBindViewHolder(holder: Holder, position: Int) {
			holder.bind(items[position])
		}

		inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

			fun bind(item: BooruPlaylistEntity) {
				itemView.findViewById<android.widget.TextView>(R.id.playlistName).text = item.name
				itemView.findViewById<android.widget.TextView>(R.id.playlistMeta).text =
					java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.createdAt))
				itemView.setOnClickListener { loadPlaylist(item) }
				itemView.setOnLongClickListener {
					val menu = PopupMenu(this@BooruPlaylistsActivity, itemView)
					menu.menu.add(0, ACTION_RENAME, 0, R.string.media_playlist_rename)
					menu.menu.add(0, ACTION_EXPORT_PLAYLIST, 1, R.string.media_playlist_export)
					menu.menu.add(0, ACTION_DELETE_PLAYLIST, 2, R.string.clear)
					menu.setOnMenuItemClickListener { menuItem ->
						when (menuItem.itemId) {
							ACTION_RENAME -> renamePlaylist(item)
							ACTION_EXPORT_PLAYLIST -> exportPlaylist(item)
							ACTION_DELETE_PLAYLIST -> deletePlaylist(item)
						}
						true
					}
					menu.show()
					true
				}
			}
		}
	}

	// endregion

	companion object {
		private const val ACTION_RENAME = 1
		private const val ACTION_EXPORT_PLAYLIST = 2
		private const val ACTION_DELETE_PLAYLIST = 3
	}
}
