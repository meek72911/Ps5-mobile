package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.mobileplay.ui.MobilePlayApp
import com.example.mobileplay.ui.MobilePlayViewModel
import com.example.ui.theme.MobilePlayTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MobilePlayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureImmersiveLandscapeBars()

        setContent {
            MobilePlayTheme {
                MobilePlayApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        configureImmersiveLandscapeBars()
    }

    override fun onPause() {
        // Non-negotiable safety guarantee: never leave any controller button or stick held on pause.
        viewModel.triggerEmergencyClear()
        super.onPause()
    }

    override fun onStop() {
        // Guard against backgrounding, incoming calls, or activity stop leaving stuck inputs.
        viewModel.triggerEmergencyClear()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureImmersiveLandscapeBars()
        } else {
            // System dialogs, notifications, or OEM edge gestures immediately clear all inputs.
            viewModel.triggerEmergencyClear()
        }
    }

    private fun configureImmersiveLandscapeBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
