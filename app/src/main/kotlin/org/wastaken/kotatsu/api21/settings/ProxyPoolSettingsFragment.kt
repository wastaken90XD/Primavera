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
 * Proxy pool (experimental) - component 5/5: the settings screen.
 *
 * Carries the mandated disclosures in-app (pool_disclosure): proxy operators
 * see host names; inert while a static proxy is configured or while SSL
 * bypass is on (amendment 3); anonymous-traffic-only rule; the Rails
 * blind spot + pointer to the manual always-direct list (login-rule
 * amendment 3); wsrv.nl and video always stay off the pool.
 * The status row doubles as the "refresh now" action (tap), with a live
 * summary from ProxyPoolController.statusLine().
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

		else -> super.onPreferenceTreeClick(preference)
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
		findPreference<Preference>(AppSettings.KEY_POOL_TEST_URL)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_MAX_HEALTHY)?.isEnabled = enabled
		findPreference<Preference>(AppSettings.KEY_POOL_FORBIDDEN_HOSTS)?.isEnabled = enabled
		findPreference<Preference>(KEY_STATUS)?.isEnabled = enabled
	}

	private fun updateStatusSummary() {
		findPreference<Preference>(KEY_STATUS)?.summary =
			ProxyPoolController.statusLine(settings.poolMode)
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
	}
}
