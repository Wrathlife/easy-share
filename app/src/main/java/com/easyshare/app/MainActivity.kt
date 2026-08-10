package com.easyshare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.easyshare.app.ui.EasyShareNavHost
import com.easyshare.app.ui.theme.EasyShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EasyShareTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EasyShareNavHost()
                }
            }
        }
    }
}
