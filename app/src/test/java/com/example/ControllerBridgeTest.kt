package com.example

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.controller.AndroidControllerBridge
import com.example.controller.ControllerStateManager
import com.example.controller.GamepadConstants
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ControllerBridgeTest {

    @Test
    fun testDefaultControllerState() {
        val manager = ControllerStateManager()
        val snapshot = manager.getSnapshot()

        assertEquals(17, snapshot.buttons.size)
        assertEquals(4, snapshot.axes.size)

        for (b in snapshot.buttons) {
            assertEquals(0f, b, 0.001f)
        }
        for (a in snapshot.axes) {
            assertEquals(0f, a, 0.001f)
        }
    }

    @Test
    fun testButtonInputsAndContinuousTriggers() {
        val manager = ControllerStateManager()

        // Test Digital Buttons
        manager.setButton(GamepadConstants.BTN_A, true)
        manager.setButton(GamepadConstants.BTN_B, true)
        manager.setButton(GamepadConstants.BTN_X, true)
        manager.setButton(GamepadConstants.BTN_Y, true)
        manager.setButton(GamepadConstants.BTN_LB, true)
        manager.setButton(GamepadConstants.BTN_RB, true)
        manager.setButton(GamepadConstants.BTN_VIEW, true)
        manager.setButton(GamepadConstants.BTN_MENU, true)
        manager.setButton(GamepadConstants.BTN_GUIDE, true)
        manager.setButton(GamepadConstants.BTN_L3, true)
        manager.setButton(GamepadConstants.BTN_R3, true)
        manager.setButton(GamepadConstants.BTN_DPAD_UP, true)

        var snap = manager.getSnapshot()
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_A], 0.001f)
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_B], 0.001f)
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_X], 0.001f)
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_Y], 0.001f)
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_LB], 0.001f)
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_RB], 0.001f)
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_VIEW], 0.001f)
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_MENU], 0.001f)
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_GUIDE], 0.001f)
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_L3], 0.001f)
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_R3], 0.001f)
        assertEquals(1.0f, snap.buttons[GamepadConstants.BTN_DPAD_UP], 0.001f)
        assertEquals(0.0f, snap.buttons[GamepadConstants.BTN_DPAD_DOWN], 0.001f)

        // Test Continuous Trigger (LT and RT float values from 0.0 to 1.0)
        manager.setButton(GamepadConstants.BTN_LT, 0.65f)
        manager.setButton(GamepadConstants.BTN_RT, 0.88f)

        snap = manager.getSnapshot()
        assertEquals(0.65f, snap.buttons[GamepadConstants.BTN_LT], 0.01f)
        assertEquals(0.88f, snap.buttons[GamepadConstants.BTN_RT], 0.01f)
    }

    @Test
    fun testContinuousAnalogSticks() {
        val manager = ControllerStateManager()

        // Left Stick
        manager.setStick(isLeft = true, x = -0.75f, y = 0.5f)
        // Right Stick
        manager.setStick(isLeft = false, x = 0.9f, y = -0.3f)

        val snap = manager.getSnapshot()
        assertEquals(-0.75f, snap.axes[GamepadConstants.AXIS_LS_X], 0.001f)
        assertEquals(0.5f, snap.axes[GamepadConstants.AXIS_LS_Y], 0.001f)
        assertEquals(0.9f, snap.axes[GamepadConstants.AXIS_RS_X], 0.001f)
        assertEquals(-0.3f, snap.axes[GamepadConstants.AXIS_RS_Y], 0.001f)

        // Test Stick Release
        manager.releaseStick(isLeft = true)
        val snapReleased = manager.getSnapshot()
        assertEquals(0.0f, snapReleased.axes[GamepadConstants.AXIS_LS_X], 0.001f)
        assertEquals(0.0f, snapReleased.axes[GamepadConstants.AXIS_LS_Y], 0.001f)
    }

    @Test
    fun testAndroidBridgeJsonSerialization() {
        val manager = ControllerStateManager()
        manager.setButton(GamepadConstants.BTN_A, 1.0f)
        manager.setStick(isLeft = true, x = 0.42f, y = -0.84f)

        var vibrateCalled = false
        val bridge = AndroidControllerBridge(manager) { _, _, _ ->
            vibrateCalled = true
        }

        val jsonStr = bridge.getGamepadState()
        assertNotNull(jsonStr)

        val json = JSONObject(jsonStr)
        val buttonsArray = json.getJSONArray("b")
        val axesArray = json.getJSONArray("a")

        assertEquals(17, buttonsArray.length())
        assertEquals(4, axesArray.length())

        assertEquals(1.0, buttonsArray.getDouble(GamepadConstants.BTN_A), 0.01)
        assertEquals(0.0, buttonsArray.getDouble(GamepadConstants.BTN_B), 0.01)
        assertEquals(0.42, axesArray.getDouble(GamepadConstants.AXIS_LS_X), 0.01)
        assertEquals(-0.84, axesArray.getDouble(GamepadConstants.AXIS_LS_Y), 0.01)

        bridge.vibrate(200, 0.8, 0.4)
        assertTrue(vibrateCalled)
    }

    @Test
    fun testAndroidBridgeFpsUpdate() {
        val manager = ControllerStateManager()
        var reportedFps = 0
        val bridge = AndroidControllerBridge(
            stateManager = manager,
            onVibrateRequested = { _, _, _ -> },
            onFpsUpdated = { fps -> reportedFps = fps }
        )

        bridge.updateFps(60)
        assertEquals(60, reportedFps)
        bridge.updateFps(120)
        assertEquals(120, reportedFps)
    }

    @Test
    fun testAndroidBridgeFeatureProviders() {
        val manager = ControllerStateManager()
        var clarity = false
        var force60 = true
        var fpsCounter = true
        var vibration = true
        var cancelCalled = false

        val bridge = AndroidControllerBridge(
            stateManager = manager,
            onVibrateRequested = { _, _, _ -> },
            onFpsUpdated = null,
            isClarityBoostEnabledProvider = { clarity },
            isForce60FpsEnabledProvider = { force60 },
            isFpsCounterEnabledProvider = { fpsCounter },
            isVibrationEnabledProvider = { vibration },
            onCancelVibrationRequested = { cancelCalled = true }
        )

        assertEquals(false, bridge.isClarityBoostEnabled())
        assertEquals(true, bridge.isForce60FpsEnabled())
        assertEquals(true, bridge.isFpsCounterEnabled())
        assertEquals(true, bridge.isVibrationEnabled())

        clarity = true
        force60 = false
        fpsCounter = false
        vibration = false

        assertEquals(true, bridge.isClarityBoostEnabled())
        assertEquals(false, bridge.isForce60FpsEnabled())
        assertEquals(false, bridge.isFpsCounterEnabled())
        assertEquals(false, bridge.isVibrationEnabled())

        bridge.cancelVibration()
        assertTrue(cancelCalled)

        // Test null provider defaults
        val defaultBridge = AndroidControllerBridge(manager) { _, _, _ -> }
        assertEquals(false, defaultBridge.isClarityBoostEnabled())
        assertEquals(true, defaultBridge.isForce60FpsEnabled())
        assertEquals(true, defaultBridge.isFpsCounterEnabled())
        assertEquals(true, defaultBridge.isVibrationEnabled())
        assertEquals(true, defaultBridge.isGfnVividEnabled())
        assertEquals(true, defaultBridge.isGfnReflexEnabled())
    }

    @Test
    fun testFastJsArgsEfficiency() {
        val manager = ControllerStateManager()
        manager.setButton(GamepadConstants.BTN_A, true)
        manager.setStick(isLeft = true, x = 0.555f, y = -0.777f)

        val snap = manager.getSnapshot()
        val jsArgs = snap.fastJsArgs
        assertTrue(jsArgs.startsWith("[1,"))
        assertTrue(jsArgs.contains(", [0.555,-0.777,0,0]"))
    }

    @Test
    fun testAndroidBridgeVibrateRespectsToggle() {
        val manager = ControllerStateManager()
        var vibrateCallCount = 0
        var vibrationAllowed = true

        val bridge = AndroidControllerBridge(
            stateManager = manager,
            onVibrateRequested = { _, _, _ -> vibrateCallCount++ },
            onFpsUpdated = null,
            isClarityBoostEnabledProvider = null,
            isForce60FpsEnabledProvider = null,
            isFpsCounterEnabledProvider = null,
            isVibrationEnabledProvider = { vibrationAllowed }
        )

        bridge.vibrate(100, 0.5, 0.5)
        assertEquals(1, vibrateCallCount)

        // Turn vibration off
        vibrationAllowed = false
        bridge.vibrate(100, 0.5, 0.5)
        // Should remain 1 because vibration was ignored!
        assertEquals(1, vibrateCallCount)
    }

    @Test
    fun testJavaScriptInjectorAssetValid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val script = context.assets.open("controller_injector.js").bufferedReader().use { it.readText() }

        assertTrue(script.isNotBlank())
        assertTrue(script.contains("navigator.getGamepads"))
        assertTrue(script.contains("Xbox 360 Controller (XInput STANDARD GAMEPAD)"))
        assertTrue(script.contains("gamepadconnected"))
        assertTrue(script.contains("window.onControllerInput"))
        assertTrue(script.contains("window.AndroidBridge"))
        assertTrue(script.contains("dual-rumble"))
        assertTrue(script.contains("force60FpsSdp"))
        assertTrue(script.contains("RTCPeerConnection"))
        assertTrue(script.contains("window.setVibrationEnabled"))
        assertTrue(script.contains("window.setGfnVivid"))
        assertTrue(script.contains("window.setGfnReflex"))
        assertTrue(script.contains("playoutDelayHint"))
        assertTrue(script.contains("jitterBufferTarget"))
    }
}
