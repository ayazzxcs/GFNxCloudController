package com.example.controller

import android.webkit.JavascriptInterface

class AndroidControllerBridge(
    private val stateManager: ControllerStateManager,
    private val onVibrateRequested: (durationMs: Long, strongMagnitude: Double, weakMagnitude: Double) -> Unit,
    private val onFpsUpdated: ((Int) -> Unit)? = null,
    private val isClarityBoostEnabledProvider: (() -> Boolean)? = null,
    private val isForce60FpsEnabledProvider: (() -> Boolean)? = null,
    private val isFpsCounterEnabledProvider: (() -> Boolean)? = null,
    private val isVibrationEnabledProvider: (() -> Boolean)? = null,
    private val onCancelVibrationRequested: (() -> Unit)? = null
) {
    constructor(
        stateManager: ControllerStateManager,
        onVibrateRequested: (durationMs: Long, strongMagnitude: Double, weakMagnitude: Double) -> Unit
    ) : this(stateManager, onVibrateRequested, null, null, null, null, null, null)

    @JavascriptInterface
    fun getGamepadState(): String {
        return stateManager.getSnapshot().toJson()
    }

    @JavascriptInterface
    fun isVibrationEnabled(): Boolean {
        return isVibrationEnabledProvider?.invoke() ?: true
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long, strongMagnitude: Double, weakMagnitude: Double) {
        if (!isVibrationEnabled()) return
        onVibrateRequested(durationMs, strongMagnitude, weakMagnitude)
    }

    @JavascriptInterface
    fun cancelVibration() {
        onCancelVibrationRequested?.invoke()
    }

    @JavascriptInterface
    fun updateFps(fps: Int) {
        onFpsUpdated?.invoke(fps)
    }

    @JavascriptInterface
    fun isClarityBoostEnabled(): Boolean {
        return isClarityBoostEnabledProvider?.invoke() ?: false
    }

    @JavascriptInterface
    fun isForce60FpsEnabled(): Boolean {
        return isForce60FpsEnabledProvider?.invoke() ?: true
    }

    @JavascriptInterface
    fun isFpsCounterEnabled(): Boolean {
        return isFpsCounterEnabledProvider?.invoke() ?: true
    }
}
