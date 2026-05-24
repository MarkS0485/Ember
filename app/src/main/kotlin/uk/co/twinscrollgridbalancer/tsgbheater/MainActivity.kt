package uk.co.twinscrollgridbalancer.tsgbheater

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import uk.co.twinscrollgridbalancer.tsgbheater.ui.nav.AppNav
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.TsgbTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Status bar: transparent scrim + force light icons (white). The
        // BrandTopBar paints its navy→red gradient through the status-bar
        // area, so we never want the system to draw a light scrim there.
        // Navigation bar: keep the platform default so the bottom inset
        // adapts to gesture vs. 3-button nav automatically.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            TsgbTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    color    = MaterialTheme.colorScheme.background,
                ) {
                    AppNav()
                }
            }
        }
    }
}
