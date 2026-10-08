package org.wastaken.kotatsu.api21.settings.pool

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.core.ui.BaseActivity
import org.wastaken.kotatsu.api21.core.util.ext.consumeAllSystemBarsInsets
import org.wastaken.kotatsu.api21.core.util.ext.systemBarsInsets
import org.wastaken.kotatsu.api21.databinding.ActivityProxyPoolListBinding
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyPoolController
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyPoolController.PoolRow

/**
 * Amendment 5 (10d): the disk-backed, paged proxy pool list.
 *
 * Rows come from ProxyPoolController.poolRowsSnapshot(): the refreshed
 * candidate window UNION picks/manual from the cache-dir ledger
 * (proxy_pool/picks.txt - the "disk backed" contract; the UI warns via the
 * main screen that a cache wipe resets the file). Paging slices the
 * filtered list by PAGE; filters (alive/picked/manual/dead/banned),
 * live search over host names and a three-way sort all re-page locally.
 * Row actions: test visible / test selected / test all alive, select all
 * alive, clear selection, remove (picks/manual), ban + unban, copy to the
 * clipboard, add-by-paste. The chain preview line above the list shows
 * exactly what a request's chain looks like right now (host names in
 * order), never credentials or URLs.
 */
class ProxyPoolListActivity : BaseActivity<ActivityProxyPoolListBinding>() {

	private val adapter = RowAdapter()
	private var allRows: List<PoolRow> = emptyList()
	private var visibleRows: List<PoolRow> = emptyList()
	private val selected = HashSet<String>()
	private var page = 1
	private var search: String = ""
	private var sortMode: Int = SORT_HOST_ASC

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityProxyPoolListBinding.inflate(layoutInflater))
		setSupportActionBar(viewBinding.toolbar)
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		viewBinding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
		viewBinding.recyclerView.also {
			it.setHasFixedSize(false)
			it.layoutManager = LinearLayoutManager(this)
			it.adapter = adapter
		}
		bindControls()
		reload()
	}

	// region data + paging

	private fun reload() {
		allRows = ProxyPoolController.poolRowsSnapshot()
		recomputeVisible()
		updateSummary()
	}

	private fun recomputeVisible() {
		val q = search.trim().lowercase()
		val aliveOn = viewBinding.checkAlive.isChecked
		val pickedOn = viewBinding.checkPicked.isChecked
		val manualOn = viewBinding.checkManual.isChecked
		val deadOn = viewBinding.checkDead.isChecked
		val bannedOn = viewBinding.checkBanned.isChecked
		visibleRows = allRows.filter { row ->
			if (q.isNotEmpty() && !row.entry.host.lowercase().contains(q)) return@filter false
			val keep = when {
				row.isBanned -> bannedOn
				row.isManual -> manualOn
				row.isPicked -> pickedOn
				row.isAliveNow -> aliveOn
				else -> deadOn
			}
			keep
		}.let { sorted(it) }
		page = 1
		notifyPage()
	}

	private fun sorted(rows: List<PoolRow>): List<PoolRow> = when (sortMode) {
		SORT_HOST_ASC -> rows.sortedWith(compareBy({ it.entry.host }, { it.entry.port }))
		SORT_HOST_DESC -> rows.sortedWith(compareByDescending<PoolRow> { it.entry.host }
			.thenByDescending { it.entry.port })

		SORT_LATENCY -> rows.sortedWith(
			compareBy<PoolRow> { it.latencyMs ?: Long.MAX_VALUE }
				.thenBy { it.entry.host },
		)

		else -> rows
	}

	private fun notifyPage() {
		val slice = visibleRows.take(page * PAGE)
		adapter.submit(slice)
		viewBinding.buttonLoadMore.visibility =
			if (visibleRows.size > slice.size) View.VISIBLE else View.GONE
	}

	private fun updateSummary() {
		val alive = allRows.count { it.isAliveNow }
		val picked = allRows.count { it.isPicked }
		val manual = allRows.count { it.isManual }
		val banned = allRows.count { it.isBanned }
		val dead = allRows.size - alive
		viewBinding.textSummary.text = getString(
			R.string.pool_list_summary_format,
			allRows.size, alive, picked, manual, dead, banned, selected.size, visibleRows.size,
		)
		viewBinding.textChainPreview.text = ProxyPoolController.chainPreviewLine()
	}

	// endregion

	// region controls

	private fun bindControls() {
		val filterListener: (View.OnClickListener) = View.OnClickListener { recomputeVisible() }
		viewBinding.checkAlive.setOnClickListener(filterListener)
		viewBinding.checkPicked.setOnClickListener(filterListener)
		viewBinding.checkManual.setOnClickListener(filterListener)
		viewBinding.checkDead.setOnClickListener(filterListener)
		viewBinding.checkBanned.setOnClickListener(filterListener)

		viewBinding.inputSearch.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
			override fun afterTextChanged(s: Editable?) {
				search = s?.toString() ?: ""
				recomputeVisible()
			}
		})

		viewBinding.buttonRefresh.setOnClickListener {
			ProxyPoolController.refreshAsync(force = true)
			viewBinding.progress.visibility = View.VISIBLE
			viewBinding.toolbar.postDelayed(
				{
					viewBinding.progress.visibility = View.GONE
					reload()
				},
				REFRESH_POLL_MS,
			)
		}
		viewBinding.buttonTestVisible.setOnClickListener {
			testRows(visibleRows.take(page * PAGE).map { it.entry })
		}
		viewBinding.buttonTestSelected.setOnClickListener {
			testRows(allRows.filter { it.key in selected }.map { it.entry })
		}
		viewBinding.buttonTestAlive.setOnClickListener {
			testRows(allRows.filter { it.isAliveNow }.map { it.entry })
		}
		viewBinding.buttonSelectAlive.setOnClickListener {
			selected += allRows.filter { it.isAliveNow }.map { it.key }
			adapter.submit(adapter.currentRows())
			updateSummary()
		}
		viewBinding.buttonClearSelection.setOnClickListener {
			selected.clear()
			adapter.submit(adapter.currentRows())
			updateSummary()
		}
		viewBinding.buttonBan.setOnClickListener {
			selected.forEach { ProxyPoolController.banKey(it) }
			reload()
		}
		viewBinding.buttonUnban.setOnClickListener {
			selected.forEach { ProxyPoolController.unbanKey(it) }
			reload()
		}
		viewBinding.buttonRemove.setOnClickListener {
			val n = selected.size
			selected.forEach { ProxyPoolController.removePickOrManual(it) }
			selected.clear()
			Toast.makeText(this, getString(R.string.pool_removed_format, n), Toast.LENGTH_SHORT).show()
			viewBinding.progress.postDelayed({ reload() }, ACTION_RELOAD_DELAY_MS)
		}
		viewBinding.buttonCopy.setOnClickListener {
			val text = allRows.filter { it.key in selected }
				.joinToString("\n") {
					buildString {
						append(it.entry.scheme.name.lowercase()).append(' ')
							.append(it.entry.host).append(' ').append(it.entry.port)
					}
				}
			getSystemService<ClipboardManager>()
				?.setPrimaryClip(ClipData.newPlainText("proxy pool picks", text))
			Toast.makeText(this, getString(R.string.pool_copied_format, selected.size), Toast.LENGTH_SHORT).show()
		}
		viewBinding.buttonAdd.setOnClickListener { promptAddManual() }
		viewBinding.buttonSort.setOnClickListener {
			sortMode = (sortMode + 1) % 3
			viewBinding.buttonSort.text = when (sortMode) {
				SORT_HOST_ASC -> getString(R.string.pool_sort_host)
				SORT_HOST_DESC -> getString(R.string.pool_sort_host_desc)
				else -> getString(R.string.pool_sort_latency)
			}
			recomputeVisible()
		}
		viewBinding.buttonLoadMore.setOnClickListener {
			page++
			notifyPage()
		}
	}

	private fun testRows(entries: List<org.wastaken.kotatsu.api21.core.network.proxypool.ProxyListFetcher.ProxyEntry>) {
		if (entries.isEmpty()) {
			Toast.makeText(this, R.string.pool_nothing_to_test, Toast.LENGTH_SHORT).show()
			return
		}
		viewBinding.progress.visibility = View.VISIBLE
		ProxyPoolController.testEntries(entries) { passes, total ->
			runOnUiThread {
				viewBinding.progress.visibility = View.GONE
				Toast.makeText(
					this, getString(R.string.pool_test_result_format, passes, total),
					Toast.LENGTH_LONG,
				).show()
				reload()
			}
		}
	}

	private fun promptAddManual() {
		val input = android.widget.EditText(this).apply {
			minLines = 4
			gravity = android.view.Gravity.TOP
			inputType = android.text.InputType.TYPE_CLASS_TEXT or
				android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
			hint = "socks5 127.0.0.1 9050\nhttp proxy.internal 8080 user pass"
		}
		val pad = (16 * resources.displayMetrics.density).toInt()
		org.wastaken.kotatsu.api21.core.ui.dialog.buildAlertDialog(this, isCentered = true) {
			setTitle(R.string.pool_add_manual_title)
			val frame = android.widget.FrameLayout(this@ProxyPoolListActivity).apply {
				setPadding(pad, 0, pad, 0)
				addView(input)
			}
			setView(frame)
			setPositiveButton(android.R.string.ok) { _, _ ->
				val accepted = ProxyPoolController.addManualPasted(input.text.toString())
				Toast.makeText(
					this@ProxyPoolListActivity,
					getString(R.string.pool_added_format, accepted),
					Toast.LENGTH_SHORT,
				).show()
				reload()
			}
			setNegativeButton(android.R.string.cancel, null)
		}
	}

	// endregion

	// region adapter

	private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

		private var rows: List<PoolRow> = emptyList()

		fun currentRows(): List<PoolRow> = rows

		fun submit(newRows: List<PoolRow>) {
			rows = newRows
			notifyDataSetChanged()
		}

		override fun onCreateViewHolder(
			parent: android.view.ViewGroup,
			viewType: Int,
		): VH = VH(
			org.wastaken.kotatsu.api21.databinding.ItemProxyPoolRowBinding.inflate(
				layoutInflater, parent, false,
			),
		)

		override fun getItemCount(): Int = rows.size

		override fun onBindViewHolder(holder: VH, position: Int) {
			holder.bind(rows[position])
		}

		inner class VH(
			private val b: org.wastaken.kotatsu.api21.databinding.ItemProxyPoolRowBinding,
		) : RecyclerView.ViewHolder(b.root) {

			fun bind(row: PoolRow) {
				b.textHost.text = "${row.entry.host}:${row.entry.port}"
				val badges = ArrayList<String>(6)
				badges += row.entry.scheme.name
				if (row.isPicked) badges += getString(R.string.pool_badge_picked)
				if (row.isManual) badges += getString(R.string.pool_badge_manual)
				if (row.isBanned) badges += getString(R.string.pool_badge_banned)
				if (!row.isAliveNow && !row.isDroppedNow) badges += getString(R.string.pool_badge_dead)
				if (row.isDroppedNow) badges += getString(R.string.pool_badge_paused)
				if (row.strikes > 0) badges += "✕${row.strikes}"
				b.textMeta.text = badges.joinToString(" · ")
				b.textLatency.text = row.latencyMs?.let { "${it}ms" } ?: "—"
				b.checkSelected.isChecked = row.key in selected
				b.root.setOnClickListener {
					if (!selected.add(row.key)) selected.remove(row.key)
					b.checkSelected.isChecked = row.key in selected
					updateSummary()
				}
			}
		}
	}

	// endregion

	private companion object {
		const val PAGE = 50
		const val SORT_HOST_ASC = 0
		const val SORT_HOST_DESC = 1
		const val SORT_LATENCY = 2
		const val REFRESH_POLL_MS = 4_000L
		const val ACTION_RELOAD_DELAY_MS = 400L
	}
}
