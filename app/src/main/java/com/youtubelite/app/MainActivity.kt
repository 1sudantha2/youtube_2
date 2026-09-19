package com.youtubelite.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.youtubelite.app.ui.nav.AppRoot
import com.youtubelite.app.ui.theme.YouTheme

/**
 * Single-activity Compose host. configChanges covers orientation/size so the
 * Activity is never recreated mid-playback — the player and all StateFlows
 * survive rotation untouched.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YouTheme {
                AppRoot()
            }
        }
    }
}
