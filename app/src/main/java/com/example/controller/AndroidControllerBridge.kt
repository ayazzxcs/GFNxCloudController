package com.example.controller

import android.webkit.JavascriptInterface

class AndroidControllerBridge(
    private val stateManager: ControllerStateManager,
    private val onVibrateRequested: (durationMs: Long, strongMagnitude: Double, weakMagnitude: Double) -> Unit
) {
    @JavascriptInterface
    fun getGamepadState(): String {
        return stateManager.getSnapshot().toJson()
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long, strongMagnitude: Double, weakMagnitude: Double) {
        onVibrateRequested(durationMs, strongMagnitude, weakMagnitude)
    }
}
