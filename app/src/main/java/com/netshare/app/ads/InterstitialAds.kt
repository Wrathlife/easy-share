package com.netshare.app.ads

import android.app.Activity
import android.app.Application
import android.util.Log
import com.netshare.app.BuildConfig
import com.unity3d.ads.AdExpiredListener
import com.unity3d.ads.InitializationConfiguration
import com.unity3d.ads.InterstitialAd
import com.unity3d.ads.InterstitialShowListener
import com.unity3d.ads.LoadConfiguration
import com.unity3d.ads.LoadListener
import com.unity3d.ads.ShowConfiguration
import com.unity3d.ads.ShowFinishState
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Unity Ads interstitial via SDK 4.19 [InterstitialAd] API.
 * Shown once after a successful transfer; no-ops if unavailable within a short timeout.
 */
object InterstitialAds {
    private const val TAG = "UnityInterstitial"
    private const val SHOW_BUDGET_MS = 3_500L

    @Volatile private var initialized = false
    @Volatile private var initOk = false
    @Volatile private var loading = false
    @Volatile private var loadedAd: InterstitialAd? = null

    private val initDone = CompletableDeferred<Boolean>()

    fun initialize(app: Application) {
        val gameId = BuildConfig.UNITY_ADS_GAME_ID.trim()
        if (gameId.isEmpty()) {
            Log.w(TAG, "UNITY_ADS_GAME_ID missing; ads disabled")
            initDone.complete(false)
            return
        }
        if (initialized) return
        initialized = true

        // Personal/test defaults — replace with a real consent UI before store release.
        // Kotlin property API (4.19); MetaData is deprecated.
        UnityAds.userConsent = true
        UnityAds.userOptOut = false
        UnityAds.nonBehavioral = false

        // Keep Application referenced; new init API no longer takes Context.
        @Suppress("UNUSED_VARIABLE")
        val appRef = app

        val config = InitializationConfiguration.Builder(gameId)
            .withTestMode(BuildConfig.UNITY_ADS_TEST_MODE)
            .build()

        UnityAds.initialize(config) { error ->
            if (error == null) {
                initOk = true
                Log.i(TAG, "Unity Ads initialized (test=${BuildConfig.UNITY_ADS_TEST_MODE})")
                initDone.complete(true)
                prefetch()
            } else {
                initOk = false
                Log.e(TAG, "Unity Ads init failed: ${error.code} ${error.message}")
                initDone.complete(false)
            }
        }
    }

    /** True once Unity Ads finished initializing successfully. */
    suspend fun awaitInitialized(timeoutMs: Long = 8_000L): Boolean =
        withTimeoutOrNull(timeoutMs) { initDone.await() } == true

    fun prefetch() {
        if (com.netshare.app.billing.NoAdsBilling.adsRemoved.value) return
        val placement = BuildConfig.UNITY_ADS_INTERSTITIAL_PLACEMENT_ID
        if (placement.isBlank() || loading || loadedAd != null || !initOk) return
        loading = true
        val loadConfig = LoadConfiguration.Builder(placement).build()
        InterstitialAd.load(
            loadConfig,
            object : LoadListener<InterstitialAd> {
                override fun onAdLoaded(ad: InterstitialAd?, error: UnityAdsError?) {
                    loading = false
                    if (ad != null && error == null) {
                        loadedAd = ad
                        ad.onAdExpired = AdExpiredListener { expired ->
                            if (loadedAd === expired) loadedAd = null
                            Log.i(TAG, "Interstitial expired; will reload")
                            prefetch()
                        }
                        Log.i(TAG, "Interstitial loaded: $placement")
                    } else {
                        loadedAd = null
                        Log.w(TAG, "Interstitial load failed: ${error?.code} ${error?.message}")
                    }
                }
            }
        )
    }

    /**
     * Shows a loaded interstitial, or returns quickly if unavailable.
     * Always returns (never hangs the transfer UI permanently).
     */
    suspend fun showAfterTransfer(activity: Activity) {
        if (com.netshare.app.billing.NoAdsBilling.adsRemoved.value) {
            Log.i(TAG, "Skip interstitial — ads removed")
            return
        }
        withContext(Dispatchers.Main) {
            val ok = withTimeoutOrNull(SHOW_BUDGET_MS) {
                initDone.await()
            } == true
            if (!ok || !initOk) {
                Log.i(TAG, "Skip interstitial — SDK not ready")
                return@withContext
            }
            if (loadedAd == null) {
                prefetch()
                withTimeoutOrNull(SHOW_BUDGET_MS) {
                    while (loadedAd == null && loading) {
                        kotlinx.coroutines.delay(100)
                    }
                    loadedAd != null
                }
            }
            val ad = loadedAd
            if (ad == null) {
                Log.i(TAG, "Skip interstitial — not loaded")
                return@withContext
            }
            loadedAd = null
            suspendCancellableCoroutine { cont ->
                val showConfig = ShowConfiguration.Builder().build()
                ad.show(
                    activity,
                    showConfig,
                    object : InterstitialShowListener {
                        override fun onStarted(unityAd: InterstitialAd) = Unit
                        override fun onClicked(unityAd: InterstitialAd) = Unit

                        override fun onCompleted(unityAd: InterstitialAd, state: ShowFinishState) {
                            prefetch()
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onFailed(unityAd: InterstitialAd, error: UnityAdsError) {
                            Log.w(TAG, "Show failed: ${error.code} ${error.message}")
                            prefetch()
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                )
            }
        }
    }
}
