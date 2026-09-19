package app.you.tube

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.you.tube.core.util.Formats
import app.you.tube.ui.YouApp
import app.you.tube.ui.theme.YouTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialVideoId = extractVideoId(intent)
        setContent {
            YouTheme {
                YouApp(initialVideoId = initialVideoId)
            }
        }
    }

    private fun extractVideoId(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "http" && data.scheme != "https") return null
        return when (data.host) {
            "youtu.be" -> data.pathSegments.firstOrNull()
            "youtube.com", "www.youtube.com", "m.youtube.com" ->
                data.getQueryParameter("v") ?: data.pathSegments.getOrNull(1)?.takeIf { data.pathSegments.firstOrNull() == "shorts" }
            else -> null
        }?.takeIf { it.length == 11 && Formats.videoIdFromUrl(it) != null }
    }

    companion object {
        fun hasNotificationPermission(context: android.content.Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
