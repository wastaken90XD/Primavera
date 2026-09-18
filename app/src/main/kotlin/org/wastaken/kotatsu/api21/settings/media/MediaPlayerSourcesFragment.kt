package org.wastaken.kotatsu.api21.settings.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.core.ui.BaseFragment
import org.wastaken.kotatsu.api21.core.util.ext.consumeAllSystemBarsInsets
import org.wastaken.kotatsu.api21.core.util.ext.observe
import org.wastaken.kotatsu.api21.core.util.ext.systemBarsInsets
import org.wastaken.kotatsu.api21.databinding.FragmentMediaPlayerSourcesBinding

/**
 * Settings -> Media player -> Sources screen: searchable toggle list of all
 * parser sources. Same UI pattern as the queue sheet: a plain RecyclerView
 * adapter, rows bound directly by id.
 */
@AndroidEntryPoint
class MediaPlayerSourcesFragment : BaseFragment<FragmentMediaPlayerSourcesBinding>() {

	private val viewModel by viewModels<MediaPlayerSourcesViewModel>()
	private var sourcesAdapter: SourcesAdapter? = null

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentMediaPlayerSourcesBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: FragmentMediaPlayerSourcesBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		sourcesAdapter = SourcesAdapter { source, enabled -> viewModel.setEnabled(source, enabled) }
		binding.recyclerView.adapter = sourcesAdapter
		binding.searchInput.doAfterTextChanged { text ->
			viewModel.setQuery(text?.toString().orEmpty())
		}
		binding.buttonReset.setOnClickListener { viewModel.resetDefaults() }
		viewModel.rows.observe(viewLifecycleOwner) { items ->
			sourcesAdapter?.submit(items)
			binding.textEmpty.isVisible = items.isEmpty()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding?.recyclerView?.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onResume() {
		super.onResume()
		activity?.setTitle(R.string.media_player_sources)
	}

	override fun onDestroyView() {
		sourcesAdapter = null
		super.onDestroyView()
	}

	private class SourcesAdapter(
		private val onToggle: (MangaParserSource, Boolean) -> Unit,
	) : RecyclerView.Adapter<SourcesAdapter.Holder>() {

		private var items: List<MediaPlayerSourcesViewModel.SourceRow> = emptyList()

		fun submit(newItems: List<MediaPlayerSourcesViewModel.SourceRow>) {
			items = newItems
			notifyDataSetChanged()
		}

		override fun getItemCount() = items.size

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.item_media_player_source, parent, false)
			return Holder(view)
		}

		override fun onBindViewHolder(holder: Holder, position: Int) {
			holder.bind(items[position], onToggle)
		}

		class Holder(view: View) : RecyclerView.ViewHolder(view) {

			fun bind(
				row: MediaPlayerSourcesViewModel.SourceRow,
				onToggle: (MangaParserSource, Boolean) -> Unit,
			) {
				itemView.findViewById<TextView>(R.id.textTitle).text = row.title
				val toggle = itemView.findViewById<MaterialSwitch>(R.id.switchEnabled)
				toggle.setOnCheckedChangeListener(null)
				toggle.isChecked = row.enabled
				toggle.setOnCheckedChangeListener { _, isChecked -> onToggle(row.source, isChecked) }
			}
		}
	}
}
