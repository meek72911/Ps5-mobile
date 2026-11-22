package com.example

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.mobileplay.input.AndroidGamepadInput
import com.example.mobileplay.ui.MobilePlayApp
import com.example.mobileplay.ui.MobilePlayViewModel
import com.example.ui.theme.MobilePlayTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MobilePlayViewModel by viewModels()
    private var videoOnly by mutableStateOf(false)
    private var pipPlayback = false
    private val gamepadInput by lazy {
        AndroidGamepadInput(
            context = this,
            inputGeneration = { viewModel.gamepadInputGeneration },
            tuningFor = viewModel::gamepadTuningFor,
            captureRaw = viewModel::capturesGamepadCalibration,
            onRawSample = viewModel::onGamepadRawSample,
            onState = viewModel::onGamepadState,
            onRemoved = viewModel::onGamepadRemoved,
            onDevices = viewModel::onGamepadsChanged
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureImmersiveLandscapeBars()
        val activityCallback = checkNotNull(window.callback)
        window.callback = object : Window.Callback by activityCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                (acceptsGamepadInput() && gamepadInput.handleKey(event)) || activityCallback.dispatchKeyEvent(event)
        }

        setContent {
            MobilePlayTheme {
                MobilePlayApp(
                    viewModel = viewModel,
                    videoOnly = videoOnly,
                    onPictureInPicture = if (pictureInPictureAvailable()) ::requestPictureInPicture else null
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) {
            pipPlayback = false
            videoOnly = false
        }
        configureImmersiveLandscapeBars()
    }

    override fun onStart() {
        super.onStart()
        gamepadInput.start()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (acceptsGamepadInput() && gamepadInput.handleMotion(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun acceptsGamepadInput() = !isInPictureInPictureMode && viewModel.acceptsGamepadEvents()

    override fun onPause() {
        // Non-negotiable safety guarantee: never leave any controller button or stick held on pause.
        clearSessionInputs()
        viewModel.flushProfileEdit()
        viewModel.setMicrophoneEnabled(false)
        super.onPause()
    }

    override fun onStop() {
        // Guard against backgrounding, incoming calls, or activity stop leaving stuck inputs.
        gamepadInput.stop()
        clearSessionInputs()
        if (pipPlayback && !isChangingConfigurations) {
            pipPlayback = false
            viewModel.disconnectSession()
        }
        super.onStop()
    }

    private fun pictureInPictureAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun requestPictureInPicture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !pictureInPictureAvailable()) return false
        clearSessionInputs()
        videoOnly = true
        val entered = runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }.getOrDefault(false)
        if (!entered) videoOnly = false
        pipPlayback = entered
        return entered
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        clearSessionInputs()
        videoOnly = isInPictureInPictureMode
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureImmersiveLandscapeBars()
        } else {
            // System dialogs, notifications, or OEM edge gestures immediately clear all inputs.
            clearSessionInputs()
        }
    }

    private fun clearSessionInputs() {
        viewModel.triggerEmergencyClear()
    }

    private fun configureImmersiveLandscapeBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
