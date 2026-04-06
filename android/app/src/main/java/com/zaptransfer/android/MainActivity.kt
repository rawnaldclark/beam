package com.zaptransfer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zaptransfer.android.navigation.BeamNavGraph
import com.zaptransfer.android.ui.theme.BeamTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host for the Beam application.
 *
 * Annotated with [@AndroidEntryPoint] so Hilt can inject into this activity
 * and into all composables/ViewModels reachable through its NavGraph.
 *
 * The activity does nothing beyond:
 *  1. Enable edge-to-edge display (system bar transparency, API 35+ default, back-ported).
 *  2. Set the Compose content tree: [BeamTheme] wrapping [BeamNavGraph].
 *
 * Navigation, screen state, and business logic live entirely in ViewModels and
 * the NavGraph — keeping this class minimal and easy to test.
 *
 * Single-activity design rationale (spec §8.2):
 *  - Compose Navigation handles all screen transitions in-process.
 *  - Deep links (e.g. from transfer notifications) navigate via intent extras
 *    processed by [BeamNavGraph], not by starting new activities.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable edge-to-edge BEFORE super.onCreate so the window insets are correct
        // before the first layout pass. See androidx.activity:activity:1.8+ docs.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            // BeamTheme defaults to the system dark/light preference.
            // The NavGraph is the single composable root — it owns the NavController
            // and routes for every screen in the app.
            BeamTheme {
                BeamNavGraph()
            }
        }
    }
}
