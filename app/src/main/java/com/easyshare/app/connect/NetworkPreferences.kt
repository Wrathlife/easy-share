package com.easyshare.app.connect

import android.content.Context

/**
 * User preference: allow transfers / pairing over cellular.
 * Default off so mobile-data-only use requires an explicit opt-in.
 */
class NetworkPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var useMobileData: Boolean
        get() = prefs.getBoolean(KEY_USE_MOBILE_DATA, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_MOBILE_DATA, value).apply()

    companion object {
        private const val PREFS = "easyshare_network"
        private const val KEY_USE_MOBILE_DATA = "use_mobile_data"
    }
}
