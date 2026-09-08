package com.example.controller

import java.util.concurrent.atomic.AtomicReference

object GamepadConstants {
    const val BTN_A = 0
    const val BTN_B = 1
    const val BTN_X = 2
    const val BTN_Y = 3
    const val BTN_LB = 4
    const val BTN_RB = 5
    const val BTN_LT = 6
    const val BTN_RT = 7
    const val BTN_VIEW = 8
    const val BTN_MENU = 9
    const val BTN_L3 = 10
    const val BTN_R3 = 11
    const val BTN_DPAD_UP = 12
    const val BTN_DPAD_DOWN = 13
    const val BTN_DPAD_LEFT = 14
    const val BTN_DPAD_RIGHT = 15
    const val BTN_GUIDE = 16

    const val AXIS_LS_X = 0
    const val AXIS_LS_Y = 1
    const val AXIS_RS_X = 2
    const val AXIS_RS_Y = 3
}

data class GamepadSnapshot(
    val buttons: FloatArray = FloatArray(17),
    val axes: FloatArray = FloatArray(4)
) {
    // Precomputed and cached JSON representation - avoids rebuilding JSON on every animation frame
    val cachedJson: String by lazy {
        val sb = java.lang.StringBuilder(128)
        sb.append("""{"b":[""")
        for (i in buttons.indices) {
            val v = buttons[i]
            when (v) {
                0.0f -> sb.append("0")
                1.0f -> sb.append("1")
                else -> sb.append(String.format(java.util.Locale.US, "%.2f", v))
            }
            if (i < buttons.size - 1) sb.append(",")
        }
        sb.append("""],"a":[""")
        for (i in axes.indices) {
            val v = axes[i]
            when (v) {
                0.0f -> sb.append("0")
                1.0f -> sb.append("1")
                -1.0f -> sb.append("-1")
                else -> sb.append(String.format(java.util.Locale.US, "%.3f", v))
            }
            if (i < axes.size - 1) sb.append(",")
        }
        sb.append("]}")
        sb.toString()
    }

    // Precomputed compact argument string for window.onControllerInput(buttons, axes)
    val fastJsArgs: String by lazy {
        val sb = java.lang.StringBuilder(140)
        sb.append("[")
        for (i in buttons.indices) {
            val v = buttons[i]
            when (v) {
                0.0f -> sb.append("0")
                1.0f -> sb.append("1")
                else -> sb.append(String.format(java.util.Locale.US, "%.2f", v))
            }
            if (i < buttons.size - 1) sb.append(",")
        }
        sb.append("], [")
        for (i in axes.indices) {
            val v = axes[i]
            when (v) {
                0.0f -> sb.append("0")
                1.0f -> sb.append("1")
                -1.0f -> sb.append("-1")
                else -> sb.append(String.format(java.util.Locale.US, "%.3f", v))
            }
            if (i < axes.size - 1) sb.append(",")
        }
        sb.append("]")
        sb.toString()
    }

    fun toJson(): String = cachedJson

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GamepadSnapshot
        return buttons.contentEquals(other.buttons) && axes.contentEquals(other.axes)
    }

    override fun hashCode(): Int {
        var result = buttons.contentHashCode()
        result = 31 * result + axes.contentHashCode()
        return result
    }
}

class ControllerStateManager {
    private val stateRef = AtomicReference(GamepadSnapshot())
    var onStateChanged: ((GamepadSnapshot) -> Unit)? = null

    fun getSnapshot(): GamepadSnapshot = stateRef.get()

    fun setButton(buttonIndex: Int, value: Float) {
        if (buttonIndex !in 0 until 17) return
        val current = stateRef.get()
        if (current.buttons[buttonIndex] == value) return

        val newButtons = current.buttons.clone()
        newButtons[buttonIndex] = value.coerceIn(0f, 1f)
        val newSnapshot = current.copy(buttons = newButtons)
        stateRef.set(newSnapshot)
        onStateChanged?.invoke(newSnapshot)
    }

    fun setButton(buttonIndex: Int, pressed: Boolean) {
        setButton(buttonIndex, if (pressed) 1.0f else 0.0f)
    }

    fun setStick(isLeft: Boolean, x: Float, y: Float) {
        val current = stateRef.get()
        val axisX = if (isLeft) GamepadConstants.AXIS_LS_X else GamepadConstants.AXIS_RS_X
        val axisY = if (isLeft) GamepadConstants.AXIS_LS_Y else GamepadConstants.AXIS_RS_Y

        val clampedX = x.coerceIn(-1f, 1f)
        val clampedY = y.coerceIn(-1f, 1f)

        if (current.axes[axisX] == clampedX && current.axes[axisY] == clampedY) return

        val newAxes = current.axes.clone()
        newAxes[axisX] = clampedX
        newAxes[axisY] = clampedY
        val newSnapshot = current.copy(axes = newAxes)
        stateRef.set(newSnapshot)
        onStateChanged?.invoke(newSnapshot)
    }

    fun releaseStick(isLeft: Boolean) {
        setStick(isLeft, 0f, 0f)
    }
}
