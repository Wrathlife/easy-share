package com.easyshare.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.easyshare.app.ui.screens.HomeScreen
import com.easyshare.app.ui.screens.PreviewTransferScreen
import com.easyshare.app.ui.screens.ReceivePlaceholderScreen
import com.easyshare.app.ui.screens.SharePlaceholderScreen

object Routes {
    const val Home = "home"
    const val Share = "share"
    const val Receive = "receive"
    const val PreviewTransfer = "preview_transfer"
}

@Composable
fun EasyShareNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.Home) {
        composable(Routes.Home) {
            HomeScreen(
                onShare = { navController.navigate(Routes.Share) },
                onReceive = { navController.navigate(Routes.Receive) },
                onPreviewProgress = { navController.navigate(Routes.PreviewTransfer) }
            )
        }
        composable(Routes.Share) {
            SharePlaceholderScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Receive) {
            ReceivePlaceholderScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PreviewTransfer) {
            PreviewTransferScreen(onBack = { navController.popBackStack() })
        }
    }
}
