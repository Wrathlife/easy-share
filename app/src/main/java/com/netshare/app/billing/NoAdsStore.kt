package com.netshare.app.billing

import android.content.Context

/** Local entitlement cache for the one-time Remove ads purchase. */
class NoAdsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var adsRemoved: Boolean
        get() = prefs.getBoolean(KEY_ADS_REMOVED, false)
        set(value) = prefs.edit().putBoolean(KEY_ADS_REMOVED, value).apply()

    companion object {
        private const val PREFS = "netshare_billing"
        private const val KEY_ADS_REMOVED = "ads_removed"
    }
}
