package com.example.controller

import android.webkit.JavascriptInterface

class AndroidControllerBridge(
    private val stateManager: ControllerStateManager,
    private val onVibrateRequested: (durationMs: Long, strongMagnitude: Double, weakMagnitude: Double) -> Unit,
    private val onFpsUpdated: ((Int) -> Unit)? = null
) {
    constructor(
        stateManager: ControllerStateManager,
        onVibrateRequested: (durationMs: Long, strongMagnitude: Double, weakMagnitude: Double) -> Unit
    ) : this(stateManager, onVibrateRequested, null)

    @JavascriptInterface
    fun getGamepadState(): String {
        return stateManager.getSnapshot().toJson()
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long, strongMagnitude: Double, weakMagnitude: Double) {
        onVibrateRequested(durationMs, strongMagnitude, weakMagnitude)
    }

    @JavascriptInterface
    fun updateFps(fps: Int) {
        onFpsUpdated?.invoke(fps)
    }
}
