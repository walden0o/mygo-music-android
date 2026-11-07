package dev.walden.mygomusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.walden.mygomusic.ui.MyGoApp
import dev.walden.mygomusic.ui.MyGoState

/**
 * Single-activity host. All UI lives in Compose (`MyGoApp`); the plugin
 * runtime, player and page state are process-scoped in [MyGoState].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = MyGoState.get(this)
        setContent {
            MyGoApp(state)
        }
    }
}
