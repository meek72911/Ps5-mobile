package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mobileplay.remoteplay.ChiakiNativeBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
  @Test
  fun useAppContext() {
    // Context of the app under test.
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
  }

  @Test
  fun loadsNativeEngineAndResolvesConsoleControlsWithoutAConsole() {
    val bridge = ChiakiNativeBridge
    assertTrue(bridge.isLibraryLoaded)
    assertTrue(bridge.nativeGetBuildInfo().contains("JNI active (arm64-v8a + Opus)"))
    assertEquals(bridge.ERR_INVALID_ARGS, bridge.nativeWakeConsole("127.0.0.1", ByteArray(0)))
    assertEquals(bridge.ERR_INVALID_ARGS, bridge.nativeSetLoginPin(ByteArray(0)))
    assertEquals(bridge.ERR_INVALID_ARGS, bridge.nativeFinishKeyboard(null))
    assertEquals(bridge.ERR_INVALID_ARGS, bridge.nativeRequestStandby())
    assertEquals(0, bridge.nativeSetMicrophoneEnabled(false))
    assertFalse(bridge.nativeSendMicrophonePcm(ShortArray(0)))
  }
}
