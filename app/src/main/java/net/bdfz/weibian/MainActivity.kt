package net.bdfz.weibian

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import net.bdfz.weibian.ui.WeibianApp
import net.bdfz.weibian.ui.WeibianTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WeibianTheme {
                Surface(Modifier.fillMaxSize()) {
                    WeibianApp()
                }
            }
        }
    }
}
