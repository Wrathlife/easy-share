package com.netshare.app.ads

import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.netshare.app.BuildConfig
import com.netshare.app.billing.NoAdsBilling
import com.unity3d.ads.BannerAd
import com.unity3d.ads.BannerConfiguration
import com.unity3d.ads.BannerShowListener
import com.unity3d.ads.BannerSize
import com.unity3d.ads.LoadListener
import com.unity3d.ads.UnityAdsError

/**
 * Unity Ads banner via SDK 4.19 [BannerAd] / [BannerConfiguration] API (Home).
 * Hidden when Remove ads is owned. Debug builds show a status strip on no fill.
 */
@Composable
fun UnityBannerAd(
    modifier: Modifier = Modifier
) {
    val adsRemoved by NoAdsBilling.adsRemoved.collectAsState()
    if (adsRemoved) return

    val placement = BuildConfig.UNITY_ADS_BANNER_PLACEMENT_ID
    if (placement.isBlank() || BuildConfig.UNITY_ADS_GAME_ID.isBlank()) return

    val context = LocalContext.current
    var status by remember { mutableStateOf("Ads: waiting…") }
    var bannerView by remember { mutableStateOf<View?>(null) }

    LaunchedEffect(placement) {
        if (context.findActivity() == null) {
            status = "Ads: no Activity"
            return@LaunchedEffect
        }
        val ok = InterstitialAds.awaitInitialized()
        if (!ok) {
            status = "Ads: SDK init failed"
            return@LaunchedEffect
        }
        status = "Ads: loading…"
        val showListener = object : BannerShowListener {
            override fun onImpression(ad: BannerAd) {
                Log.i("UnityBanner", "Banner impression")
            }

            override fun onClicked(ad: BannerAd) = Unit

            override fun onFailedToShow(ad: BannerAd, error: UnityAdsError) {
                Log.w("UnityBanner", "Banner show failed: ${error.code} ${error.message}")
                status = "Ads: show failed"
            }
        }
        val config = BannerConfiguration.Builder(
            placement,
            BannerSize(320, 50),
            showListener
        ).build()

        BannerAd.load(
            config,
            object : LoadListener<BannerAd> {
                override fun onAdLoaded(ad: BannerAd?, error: UnityAdsError?) {
                    if (ad != null && error == null) {
                        bannerView = ad.view
                        status = "Ads: loaded"
                        Log.i("UnityBanner", "Banner loaded")
                    } else {
                        bannerView = null
                        val msg = error?.message ?: "unknown"
                        Log.w("UnityBanner", "Banner failed: ${error?.code} $msg")
                        status = "Ads: no fill — check Unity dashboard"
                    }
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            bannerView = null
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        factory = { ctx ->
            FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        },
        update = { host ->
            host.removeAllViews()
            val view = bannerView
            if (view != null) {
                (view.parent as? ViewGroup)?.removeView(view)
                host.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            } else if (BuildConfig.DEBUG) {
                host.addView(
                    TextView(host.context).apply {
                        text = status
                        textSize = 12f
                        gravity = Gravity.CENTER
                        setBackgroundColor(0x22000000)
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }
    )
}
