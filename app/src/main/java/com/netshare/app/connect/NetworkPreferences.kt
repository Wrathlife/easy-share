package com.netshare.app.connect

import android.content.Context

/**
 * User preferences for network / transfer policy.
 * - [useMobileData]: allow pairing/transfer on cellular (default off).
 * - [encryptFileTransfer]: force MQTT AES path instead of WebRTC P2P (default off).
 */
class NetworkPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var useMobileData: Boolean
        get() = prefs.getBoolean(KEY_USE_MOBILE_DATA, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_MOBILE_DATA, value).apply()

    var encryptFileTransfer: Boolean
        get() = prefs.getBoolean(KEY_ENCRYPT_FILE_TRANSFER, false)
        set(value) = prefs.edit().putBoolean(KEY_ENCRYPT_FILE_TRANSFER, value).apply()

    companion object {
        private const val PREFS = "easyshare_network"
        private const val KEY_USE_MOBILE_DATA = "use_mobile_data"
        private const val KEY_ENCRYPT_FILE_TRANSFER = "encrypt_file_transfer"
    }
}
