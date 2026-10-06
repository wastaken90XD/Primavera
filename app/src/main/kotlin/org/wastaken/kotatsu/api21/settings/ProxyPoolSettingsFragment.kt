package org.wastaken.kotatsu.api21.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.core.network.proxypool.PoolMode
import org.wastaken.kotatsu.api21.core.network.proxypool.ProxyPoolState
import org.wastaken.kotatsu.api21.core.prefs.AppSettings
import org.wastaken.kotatsu.api21.core.ui.BasePreferenceFragment
import org.wastaken.kotatsu.api21.core.util.ext.viewLifecycleScope
import org.wastaken.kotatsu.api21.settings.utils.EditTextBindListener

/**
 * Proxy pool settings.
 *
 * What is live and what is not, and why:
 *  - MODE is read while the OkHttp clients are built, because the routing
 *    interceptor is installed or not installed at that moment. Changing it needs
 *    an app restart, which [pool_restart_note] states on screen and which a
 *    Snackbar repeats when the row is changed;
 *  - everything else - the category switches, chain mode, cookie mode, the
 *    never-use list, the limits and the timeouts - is read per request or per
 *    routing decision, so it applies immediately.
 *
 * The status row doubles as the refresh action. The clear rows exist because the
 * state is event-driven: nothing expires on its own any more, so a stuck proxy,
 * a stale certificate ban or a wrongly learned host is a button press away
 * instead of a wait.
 */
@AndroidEntryPoint
class ProxyPoolSettingsFragment : BasePreferenceFragment(R.string.proxy_pool),
	SharedPreferences.OnSharedPreferenceChangeListener {

	private var statusJob: Job? = null

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_proxy_pool)
		bindEdit(AppSettings.KEY_POOL_LISTS, TYPE_MULTILINE or EditorInfo.TYPE_TEXT_VARIATION_URI)
		bindEdit(AppSettings.KEY_POOL_BOOTSTRAP_PROXIES, TYPE_MULTILINE)
		bindEdit(AppSettings.KEY_POOL_TEST_URL, EditorInfo.TYPE_TEXT_VARIATION_URI)
		bindEdit(AppSettings.KEY_POOL_NEVER_USE_HOSTS, TYPE_MULTILINE)
		for (key in NUMBER_KEYS) {
			bindEdit(key, EditorInfo.TYPE_CLASS_NUMBER)
		}
		updateDependencies()
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
		KEY_STATUS,
		KEY_REFRESH,
		-> {
			ProxyPoolState.refreshAsync(force = true)
			watchStatus()
			true
		}

		KEY_CLEAR_POOL -> {
			ProxyPoolState.clearPool()
			watchStatus()
			true
		}

		KEY_CLEAR_BANS -> {
			ProxyPoolState.clearBans()
			updateStatusSummary()
			true
		}

		KEY_CLEAR_LEARNED -> {
			ProxyPoolState.clearLearnedHosts()
			updateStatusSummary()
			true
		}

		else -> super.onPreferenceTreeClick(preference)
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_POOL_MODE -> {
				updateDependencies()
				updateStatusSummary()
				// the routing interceptor is installed at client build time
				Snackbar.make(listView, R.string.settings_apply_restart_required, Snackbar.LENGTH_INDEFINITE).show()
			}

			AppSettings.KEY_POOL_CHAIN_ENABLED -> {
				updateDependencies()
				// live: the relay is started or stopped without a restart
				ProxyPoolState.refreshAsync(force = true)
				watchStatus()
			}

			else -> updateStatusSummary()
		}
	}

	private fun updateDependencies() {
		val enabled = settings.poolMode != PoolMode.OFF
		for (key in DEPENDENT_KEYS) {
			findPreference<Preference>(key)?.isEnabled = enabled
		}
		val chainEnabled = enabled && settings.poolChainEnabled
		for (key in CHAIN_KEYS) {
			findPreference<Preference>(key)?.isEnabled = chainEnabled
		}
		findPreference<Preference>(KEY_STATUS)?.isEnabled = enabled
		findPreference<Preference>(KEY_REFRESH)?.isEnabled = enabled
		findPreference<Preference>(KEY_CLEAR_POOL)?.isEnabled = enabled
	}

	private fun updateStatusSummary() {
		findPreference<Preference>(KEY_STATUS)?.summary =
			ProxyPoolState.statusLine(settings.poolMode)
	}

	private fun watchStatus() {
		statusJob?.cancel()
		statusJob = viewLifecycleScope.launch {
			repeat(40) {
				delay(1_500)
				updateStatusSummary()
				if (!ProxyPoolState.isRefreshing) {
					return@launch
				}
			}
		}
	}

	@Suppress("UsePropertyAccessSyntax")
	private fun bindEdit(key: String, inputType: Int) {
		findPreference<EditTextPreference>(key)?.setOnBindEditTextListener(
			EditTextBindListener(
				inputType = inputType,
				hint = null,
				validator = null,
			),
		)
	}

	companion object {

		private const val TYPE_MULTILINE =
			EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE

		private const val KEY_STATUS = "pool_status"
		private const val KEY_REFRESH = "pool_action_refresh"
		private const val KEY_CLEAR_POOL = "pool_action_clear_pool"
		private const val KEY_CLEAR_BANS = "pool_action_clear_bans"
		private const val KEY_CLEAR_LEARNED = "pool_action_clear_learned"

		private val NUMBER_KEYS = arrayOf(
			AppSettings.KEY_POOL_MAX_HEALTHY,
			AppSettings.KEY_POOL_STRIKES,
			AppSettings.KEY_POOL_HOST_CHAIN_LIMIT,
			AppSettings.KEY_POOL_CHALLENGE_LIMIT,
			AppSettings.KEY_POOL_ROTATE_AFTER,
			AppSettings.KEY_POOL_DIRECT_TIMEOUT,
			AppSettings.KEY_POOL_CHAIN_TIMEOUT,
			AppSettings.KEY_POOL_RELAY_TIMEOUT,
		)

		/** Only meaningful with a chain, so they follow that switch too. */
		private val CHAIN_KEYS = arrayOf(
			AppSettings.KEY_POOL_BOOTSTRAP_PROXIES,
			AppSettings.KEY_POOL_CHAIN_TIMEOUT,
			AppSettings.KEY_POOL_RELAY_TIMEOUT,
		)
		/** Everything that has no meaning while the pool is Off. */
		private val DEPENDENT_KEYS = arrayOf(
			AppSettings.KEY_POOL_CATEGORY_SOURCES,
			AppSettings.KEY_POOL_CATEGORY_VIDEO,
			AppSettings.KEY_POOL_CATEGORY_APP_SERVICES,
			AppSettings.KEY_POOL_CHAIN_ENABLED,
			AppSettings.KEY_POOL_COOKIE_MODE,
			AppSettings.KEY_POOL_IGNORE_CERT_ERRORS,
			AppSettings.KEY_POOL_LISTS,
			AppSettings.KEY_POOL_TEST_URL,
			AppSettings.KEY_POOL_NEVER_USE_HOSTS,
			AppSettings.KEY_POOL_MAX_HEALTHY,
			AppSettings.KEY_POOL_STRIKES,
			AppSettings.KEY_POOL_HOST_CHAIN_LIMIT,
			AppSettings.KEY_POOL_CHALLENGE_LIMIT,
			AppSettings.KEY_POOL_ROTATE_AFTER,
			AppSettings.KEY_POOL_DIRECT_TIMEOUT,
		) + CHAIN_KEYS

	}
}
