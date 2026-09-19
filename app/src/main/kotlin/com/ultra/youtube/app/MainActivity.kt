package com.ultra.youtube.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ultra.youtube.app.ui.AppNavHost
import com.ultra.youtube.app.ui.theme.YouTubeTheme

/**
 * Single activity. Compose owns the whole UI tree, so there is nothing for the framework to
 * inflate at startup — one window, one composition, no `Fragment`/`AppCompatActivity`
 * view hierarchies resident in memory.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val locator = (application as App).locator

        setContent {
            YouTubeTheme {
                AppNavHost(
                    repository = locator.repository,
                    cookieStore = locator.cookieStore,
                )
            }
        }
    }
}
