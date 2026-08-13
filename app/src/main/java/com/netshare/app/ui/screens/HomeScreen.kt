package com.netshare.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.netshare.app.ads.UnityBannerAd
import com.netshare.app.ads.findActivity
import com.netshare.app.billing.NoAdsBilling
import com.netshare.app.connect.ConnectivitySnapshot
import com.netshare.app.ui.components.EncryptTransferControls
import com.netshare.app.ui.components.MobileDataNetworkControls
import com.netshare.app.ui.state.SessionUiState

@Composable
fun HomeScreen(
    snapshot: ConnectivitySnapshot,
    useMobileData: Boolean,
    onUseMobileDataChange: (Boolean) -> Unit,
    encryptFileTransfer: Boolean,
    onEncryptFileTransferChange: (Boolean) -> Unit,
    networkAllowed: Boolean,
    onShare: () -> Unit,
    onReceive: () -> Unit,
    onReceivedHistory: () -> Unit
) {
    val context = LocalContext.current
    val idle = SessionUiState.Idle
    val adsRemoved by NoAdsBilling.adsRemoved.collectAsState()
    val priceLabel by NoAdsBilling.priceLabel.collectAsState()
    val billingReady by NoAdsBilling.ready.collectAsState()
    val billingError by NoAdsBilling.lastError.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                NoAdsBilling.refreshPurchases()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun guard(action: () -> Unit) {
        if (!networkAllowed) {
            Toast.makeText(
                context,
                "Mobile data only — enable “Use mobile data” or connect to Wi‑Fi",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        action()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Netshare",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = idle.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
            MobileDataNetworkControls(
                snapshot = snapshot,
                useMobileData = useMobileData,
                onUseMobileDataChange = onUseMobileDataChange
            )
            EncryptTransferControls(
                encryptFileTransfer = encryptFileTransfer,
                onEncryptFileTransferChange = onEncryptFileTransferChange
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { guard(onShare) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Share files")
            }
            OutlinedButton(
                onClick = { guard(onReceive) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Receive files")
            }
            OutlinedButton(
                onClick = onReceivedHistory,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Received history")
            }
            if (adsRemoved) {
                Text(
                    text = "Ads removed — thank you",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            } else {
                val priceSuffix = priceLabel?.let { " ($it)" }.orEmpty()
                OutlinedButton(
                    onClick = {
                        val activity = context.findActivity()
                        if (activity == null) {
                            Toast.makeText(context, "Can’t open Play Billing here", Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        NoAdsBilling.launchPurchase(activity)
                        billingError?.let {
                            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = billingReady || priceLabel != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remove ads$priceSuffix")
                }
                if (!billingReady && billingError != null) {
                    Text(
                        text = billingError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (!adsRemoved) {
            UnityBannerAd(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            )
        } else {
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}
