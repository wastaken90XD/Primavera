package org.wastaken.kotatsu.api21.settings

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import org.wastaken.kotatsu.api21.R
import org.wastaken.kotatsu.api21.core.ui.BasePreferenceFragment

/**
 * Minimal placeholder, kept deliberately small: the pool moved from the selector
 * model to the routing-interceptor model, and the options that belonged to the
 * old model (its always-direct list above all) went with it.
 *
 * What is still real here is the mode, which is read when the OkHttp clients are
 * built, and the restart note that goes with it. The full screen - category
 * switches, chain options, timeouts, never-use list and the clear actions -
 * arrives in the last commit of this series.
 */
@AndroidEntryPoint
class ProxyPoolSettingsFragment : BasePreferenceFragment(R.string.proxy_pool) {

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_proxy_pool)
	}
}
