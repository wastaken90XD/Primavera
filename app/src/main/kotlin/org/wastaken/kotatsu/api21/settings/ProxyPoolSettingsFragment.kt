package org.wastaken.kotatsu.api21.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.core.network.proxypool.PoolMode
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyPoolController
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.ui.BasePreferenceFragment
import org.wastaken.kotatsu.api21.core.util.ext.viewLifecycleScope
import org.wastaken.kotatsu.api21.settings.utils.EditTextBindListener

/**
 * Proxy pool (Task C 7/7): the settings screen.
 *
 * Carries the mandated disclosures in-app (pool_disclosure): proxy operators
 * see host names; the OFF<->any-mode switch applies after an app restart
 * while Fallback/Always/Chained apply immediately (amendment 3); inert
 * while a static proxy is configured under Fallback/Always (Chained is the
 * exception - the static proxy is its gateway) or while SSL bypass is on;
 * anonymous-traffic-only rule; the Rails blind spot + pointer to the manual
 * always-direct list; wsrv.nl and video always stay off the pool.
 *
 * The status row doubles as the "refresh now" action (tap) and shows the
 * 5/7 categories via ProxyPoolController.statusLine(): direct-pool healthy,
 * relay chains, challenged hosts and the gateway state. The 6/7 opt-in
 * "Ignore certificate errors on proxied requests" ends the list.
 */
@AndroidEntryPoint
class ProxyPoolSettingsFragment : BasePreferenceFragment(R.string.proxy_pool),
	SharedPreferences.OnSharedPreferenceChangeListener {

	private var statusJob: Job? = null

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_proxy_pool)
		@Suppress("UsePropertyAccessSyntax")
		findPreference<EditTextPreference>(AppSettings.KEY_POOL_LISTS)?.setOnBindEditTextListener(
			EditTextBindListener(
				inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
					EditorInfo.TYPE_TEXT_VARIATION_URI,
				hint = null,
				validator = null,
			),
		)
		@Suppress("UsePropertyAccessSyntax")
		findPreference<EditTextPreference>(AppSettings.KEY_POOL_FORBIDDEN_HOSTS)?.setOnBindEditTextListener(
			EditTextBindListener(
				inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE,
				hint = null,
				validator = null,
			),
		)
		@Suppress("UsePropertyAccessSyntax")
		findPreference<EditTextPreference>(AppSettings.KEY_POOL_TEST_URL)?.setOnBindEditTextListener(
			EditTextBindListener(
				inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI,
				hint = null,
				validator = null,
			),
		)
		@Suppress("UsePropertyAccessSyntax")
		findPreference<EditTextPreference>(AppSettings.KEY_POOL_MAX_HEALTHY)?.setOnBindEditTextListener(
			EditTextBindListener(
				inputType = EditorInfo.TYPE_CLASS_NUMBER,
				hint = null,
				validator = null,
			),
		)
		@Suppress("UsePropertyAccessSyntax")
		findPreference<EditTextPreference>(AppSettings.KEY_POOL_MIRRORS)?.setOnBindEditTextListener(
			EditTextBindListener(
				inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
					EditorInfo.TYPE_TEXT_VARIATION_URI,
				hint = null,
				validator = null,
			),
		)
		@Suppress("UsePropertyAccessSyntax")
		findPreference<EditTextPreference>(AppSettings.KEY_POOL_TIMEOUT_DIRECT_S)?.setOnBindEditTextListener(
			EditTextBindListener(
				inputType = EditorInfo.TYPE_CLASS_NUMBER,
				hint = null,
				validator = null,
			),
		)
		@Suppress("UsePropertyAccessSyntax")
		findPreference<EditTextPreference>(AppSettings.KEY_POOL_TIMEOUT_CHAIN_S)?.setOnBindEditTextListener(
			EditTextBindListener(
				inputType = EditorInfo.TYPE_CLASS_NUMBER,
				hint = null,
				validator = null,
			),
		)
		updateModeDependencies()
		updateStatusSummary()
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		settings.subscribe(this)
	}

	override fun onDestroyView() {
		statusJob?.cancel()
		settings.unsubscribe(this)
		super.onDestroyView()
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
		KEY_STATUS -> {
			ProxyPoolController.refreshAsync(force = true)
			watchStatus()
			true
		}

		KEY_OPEN_LIST -> {
			startActivity(android.content.Intent(requireContext(), org.wastaken.kotatsu.api21.settings.pool.ProxyPoolListActivity::class.java))
			true
		}

		KEY_ADD_MANUAL -> {
			promptAddManual()
			true
		}

		KEY_CLEAR_BANS -> {
			org.wastaken.kotatsu.api21.core.ui.dialog.buildAlertDialog(requireContext(), isCentered = true) {
				setTitle(R.string.pool_clear_bans_title)
				setMessage(R.string.pool_clear_bans_summary)
				setPositiveButton(android.R.string.ok) { _, _ ->
					ProxyPoolController.clearBans()
					updateStatusSummary()
				}
				setNegativeButton(android.R.string.cancel, null)
			}
			true
		}

		else -> super.onPreferenceTreeClick(preference)
	}

	/** Paste box for manual proxies (5e): free-form lines
	 *  "scheme host port [login password]"; loopback allowed (local Tor);
	 *  credentials are written to the cache-dir ledger only, never sent
	 *  anywhere outside requests that legitimately belong to the proxy. */
	private fun promptAddManual() {
		val ctx = requireContext()
		val input = android.widget.EditText(ctx).apply {
			minLines = 4
			gravity = android.view.Gravity.TOP
			inputType = android.text.InputType.TYPE_CLASS_TEXT or
				android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
			hint = "socks5 127.0.0.1 9050\nhttp proxy.internal 8080 user pass"
		}
		val pad = (16 * resources.displayMetrics.density).toInt()
		org.wastaken.kotatsu.api21.core.ui.dialog.buildAlertDialog(ctx, isCentered = true) {
			setTitle(R.string.pool_add_manual_title)
			val frame = android.widget.FrameLayout(ctx).apply {
				setPadding(pad, 0, pad, 0)
				addView(input)
			}
			setView(frame)
			setPositiveButton(android.R.string.ok) { _, _ ->
				val accepted = ProxyPoolController.addManualPasted(input.text.toString())
				android.widget.Toast.makeText(
					ctx, "accepted $accepted manual lines", android.widget.Toast.LENGTH_SHORT,
				).show()
				updateStatusSummary()
			}
			setNegativeButton(android.R.string.cancel, null)
		}
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_POOL_MODE -> {
				updateModeDependencies()
				updateStatusSummary()
			}

			AppSettings.KEY_POOL_LISTS,
			AppSettings.KEY_POOL_TEST_URL,
			AppSettings.KEY_POOL_MAX_HEALTHY,
			-> updateStatusSummary()
		}
	}

	private fun updateModeDependencies() {
		val enabled = settings.poolMode != PoolMode.OFF
		findPreference<Preference>(AppSettings.KEY_POOL_LISTS)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_MIRRORS)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_TEST_URL)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_MAX_HEALTHY)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_TIMEOUT_DIRECT_S)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_TIMEOUT_CHAIN_S)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_FORBIDDEN_HOSTS)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_CERT_CHECKS)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_CHAIN_LENGTH)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_FETCH_VIA)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_SELECTION_MODE)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_PICKED_ROLE)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_PLAIN_HTTP)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_SKIP_LOGIN_HOSTS)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_CAT_SOURCES)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_CAT_VIDEO)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_CAT_SERVICES)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_CF_COOKIES)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_COOKIES_MODE)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_ALWAYS_HOSTS)?.isEnabled = enabled
		findPreference<Preference>(KEY_STATUS)?.isEnabled = enabled
		findPreference<Preference>(KEY_OPEN_LIST)?.isEnabled = enabled
		findPreference<Preference>(KEY_ADD_MANUAL)?.isEnabled = enabled
		findPreference<Preference>(KEY_CLEAR_BANS)?.isEnabled = enabled
	}

	private fun updateStatusSummary() {
		findPreference<Preference>(KEY_STATUS)?.summary =
			ProxyPoolController.statusLine(settings.poolMode)
		findPreference<Preference>(KEY_CHAIN_PREVIEW)?.summary =
			ProxyPoolController.chainPreviewLine()
		findPreference<Preference>(KEY_CLEAR_BANS)?.summary =
			getString(R.string.pool_clear_bans_summary) + " (" + ProxyPoolController.bannedCount() + " banned)"
		findPreference<Preference>(KEY_OPEN_LIST)?.summary =
			getString(R.string.pool_open_list_summary) + " (@${ProxyPoolController.status.healthy.size} alive, +" +
				ProxyPoolController.pickedCount() + " picked, " + ProxyPoolController.manualCount() + " manual)"
	}

	private fun watchStatus() {
		statusJob?.cancel()
		statusJob = viewLifecycleScope.launch {
			repeat(40) {
				delay(1_500)
				updateStatusSummary()
				if (!ProxyPoolController.status.refreshing) {
					return@launch
				}
			}
		}
	}

	companion object {
		// Key shares no storage: pool_status is non-persistent in the XML
		private const val KEY_STATUS = "pool_status"
		private const val KEY_OPEN_LIST = "pool_open_list"
		private const val KEY_ADD_MANUAL = "pool_add_manual"
		private const val KEY_CLEAR_BANS = "pool_clear_bans"
		private const val KEY_CHAIN_PREVIEW = "pool_chain_preview"
	}
}
