package com.sapphire.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sapphire.app.ui.SapphireNavHost
import com.sapphire.app.ui.theme.SapphireTheme
import com.sapphire.domain.settings.ThemeConfigStore
import com.sapphire.domain.settings.ThemePreference
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themeConfigStore: ThemeConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Seed with SYSTEM so the first frame follows the device theme — no flash.
            val pref by themeConfigStore.observe().collectAsState(initial = ThemePreference.SYSTEM)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (pref) {
                ThemePreference.SYSTEM -> systemDark
                ThemePreference.DARK -> true
                ThemePreference.LIGHT -> false
            }
            SapphireTheme(darkTheme = darkTheme) {
                SapphireNavHost()
            }
        }
    }
}
