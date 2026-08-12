package com.easyshare.app.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.easyshare.app.connect.ConnectivityDiagnoser
import com.easyshare.app.connect.ConnectivitySnapshot
import com.easyshare.app.connect.NetworkPreferences
import com.easyshare.app.connect.canUseCurrentNetwork
import com.easyshare.app.ui.screens.HomeScreen
import com.easyshare.app.ui.screens.PreviewTransferScreen
import com.easyshare.app.ui.screens.ReceivePlaceholderScreen
import com.easyshare.app.ui.screens.ReceivedHistoryScreen
import com.easyshare.app.ui.screens.SharePlaceholderScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

object Routes {
    const val Home = "home"
    const val Share = "share"
    const val Receive = "receive"
    const val ReceivedHistory = "received_history"
    const val PreviewTransfer = "preview_transfer"
}

class NetworkUiViewModel(app: Application) : AndroidViewModel(app) {
    private val diagnoser = ConnectivityDiagnoser(app)
    private val prefs = NetworkPreferences(app)

    val connectivity: StateFlow<ConnectivitySnapshot> = diagnoser.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = diagnoser.snapshot()
        )

    private val _useMobileData = MutableStateFlow(prefs.useMobileData)
    val useMobileData: StateFlow<Boolean> = _useMobileData.asStateFlow()

    fun setUseMobileData(enabled: Boolean) {
        prefs.useMobileData = enabled
        _useMobileData.value = enabled
        // #region agent log
        com.easyshare.app.debug.AgentDebugLog.log(
            hypothesisId = "B1",
            location = "NetworkUiViewModel.setUseMobileData",
            message = "mobile data toggle changed",
            data = mapOf(
                "useMobileData" to enabled,
                "cellularOnly" to connectivity.value.cellularOnly,
                "allowed" to canUseCurrentNetwork(connectivity.value, enabled)
            )
        )
        // #endregion
    }

    fun allowsCurrentNetwork(snapshot: ConnectivitySnapshot = connectivity.value): Boolean =
        canUseCurrentNetwork(snapshot, _useMobileData.value)
}

@Composable
fun EasyShareNavHost(
    networkVm: NetworkUiViewModel = viewModel()
) {
    val navController = rememberNavController()
    val snapshot by networkVm.connectivity.collectAsState()
    val useMobileData by networkVm.useMobileData.collectAsState()

    NavHost(navController = navController, startDestination = Routes.Home) {
        composable(Routes.Home) {
            HomeScreen(
                snapshot = snapshot,
                useMobileData = useMobileData,
                onUseMobileDataChange = networkVm::setUseMobileData,
                networkAllowed = canUseCurrentNetwork(snapshot, useMobileData),
                onShare = { navController.navigate(Routes.Share) },
                onReceive = { navController.navigate(Routes.Receive) },
                onReceivedHistory = { navController.navigate(Routes.ReceivedHistory) },
                onPreviewProgress = { navController.navigate(Routes.PreviewTransfer) }
            )
        }
        composable(Routes.Share) {
            SharePlaceholderScreen(
                snapshot = snapshot,
                useMobileData = useMobileData,
                onUseMobileDataChange = networkVm::setUseMobileData,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.Receive) {
            ReceivePlaceholderScreen(
                snapshot = snapshot,
                useMobileData = useMobileData,
                onUseMobileDataChange = networkVm::setUseMobileData,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.ReceivedHistory) {
            ReceivedHistoryScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PreviewTransfer) {
            PreviewTransferScreen(onBack = { navController.popBackStack() })
        }
    }
}
