package com.example

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.mobileplay.engine.ControlMode
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.engine.PadState
import com.example.mobileplay.overlay.TouchOverlayView
import com.example.mobileplay.video.AspectRatioSurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun appNameMatchesMobilePlay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("MobilePlay", context.getString(R.string.app_name))
    }

    @Test
    fun touchOverlayViewHandlesMotionEventsPreservesProfileOnResizeAndClearsCleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overlay = TouchOverlayView(context)
        val drivingProfile = ControlProfile.drivingPreset()
        overlay.updateProfile(drivingProfile)

        // Layout to 1000x500 landscape
        overlay.layout(0, 0, 1000, 500)

        // Press Gas (R2) in Driving layout at (880, 370)
        val down = MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, 880f, 370f, 0)
        overlay.onTouchEvent(down)
        assertEquals(255, overlay.currentEngineState().r2)
        down.recycle()

        // Resize should clear active press AND retain Driving profile (fixes Phase 1 v2 bug)
        overlay.layout(0, 0, 1200, 600)
        assertEquals(PadState(), overlay.currentEngineState())

        // Press Gas at new scaled coordinates (1200 * 0.88 = 1056, 600 * 0.74 = 444)
        val downAfterResize = MotionEvent.obtain(30L, 30L, MotionEvent.ACTION_DOWN, 1056f, 444f, 0)
        overlay.onTouchEvent(downAfterResize)
        assertEquals(255, overlay.currentEngineState().r2)
        downAfterResize.recycle()

        // Emergency clearInput zeroes all triggers immediately
        overlay.clearInput()
        assertEquals(PadState(), overlay.currentEngineState())
    }

    @Test
    fun aspectRatioSurfaceViewMaintains16By9WithoutCropping() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val surface = AspectRatioSurfaceView(context)
        // Ultra-wide 2400x1080 phone display
        surface.measure(
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        )
        assertEquals(1920, surface.measuredWidth)
        assertEquals(1080, surface.measuredHeight)
        assertTrue(surface.measuredWidth <= 2400)
    }
}
