package com.example.controller.ui

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controller.ControllerStateManager
import com.example.controller.GamepadConstants
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Exact GeForce NOW on-screen controller overlay matching the reference screenshot:
 * - Floating top-center controls: [ ◀ ] ( 🎮▼ ) [ ▶ ]
 * - Left side: LT (top-left edge), LB (adjacent to LT), D-pad (4 circular buttons with chevrons),
 *   L3 (bottom-left corner), and large Left Analog Stick with dual concentric rings & dotted knob.
 * - Right side: RT (top-right edge), RB (adjacent to RT), ABXY diamond (Y, X, B, A),
 *   R3 (bottom-right corner), and large Right Analog Stick with dual concentric rings & dotted knob.
 * - Symmetrical, clean, dark-glass transparent design with crisp white borders and tactile grips.
 */
@Composable
fun GFNControllerOverlay(
    stateManager: ControllerStateManager,
    opacity: Float = 0.9f,
    hapticFeedbackEnabled: Boolean = true,
    pingMs: Int = 32,
    fps: Int = 60,
    showFps: Boolean = true,
    onToggleFps: () -> Unit = {},
    onTriggerHaptic: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val pressedMap = remember { mutableStateMapOf<Int, Boolean>() }

    fun handleButtonChange(btnIndex: Int, isPressed: Boolean) {
        val wasPressed = pressedMap[btnIndex] == true
        pressedMap[btnIndex] = isPressed
        stateManager.setButton(btnIndex, isPressed)
        if (isPressed && !wasPressed && hapticFeedbackEnabled) {
            onTriggerHaptic()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .alpha(opacity)
    ) {
        val screenHeight = maxHeight
        val centerY = screenHeight / 2

        // ==========================================
        // 1. TOP-CENTER CONTROLS [ ◀ ] ( 🎮▼ ) [ ▶ ]
        // ==========================================
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 22.dp)
                .testTag("top_center_controls"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // View button (Left arrow pill)
            PillIconButton(
                direction = ArrowDirection.LEFT,
                isPressed = pressedMap[GamepadConstants.BTN_VIEW] == true,
                onPressChange = { handleButtonChange(GamepadConstants.BTN_VIEW, it) },
                testTag = "btn_view"
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Center GeForce NOW Menu / Guide button (Controller + dropdown triangle)
            GfnGamepadMenuButton(
                isPressed = pressedMap[GamepadConstants.BTN_GUIDE] == true,
                onPressChange = { handleButtonChange(GamepadConstants.BTN_GUIDE, it) },
                onOpenSettings = onOpenSettings,
                testTag = "btn_guide"
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Menu button (Right arrow pill)
            PillIconButton(
                direction = ArrowDirection.RIGHT,
                isPressed = pressedMap[GamepadConstants.BTN_MENU] == true,
                onPressChange = { handleButtonChange(GamepadConstants.BTN_MENU, it) },
                testTag = "btn_menu"
            )
        }

        // Top-right network broadcast indicator ((•)) & Live Stream FPS Badge
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 18.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showFps) {
                FpsPillBadge(
                    fps = fps,
                    onClick = {
                        if (hapticFeedbackEnabled) onTriggerHaptic()
                        onToggleFps()
                    }
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            NetworkBroadcastIndicator()
        }

        // ==========================================
        // 2. LEFT SIDE CONTROLS
        // ==========================================

        // LT Button (Top-left corner)
        CircularGamepadButton(
            text = "LT",
            isPressed = pressedMap[GamepadConstants.BTN_LT] == true,
            size = 60.dp,
            fontSize = 18.sp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_LT, it) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 22.dp)
                .testTag("btn_lt")
        )

        // LB Button (Adjacent to LT)
        CircularGamepadButton(
            text = "LB",
            isPressed = pressedMap[GamepadConstants.BTN_LB] == true,
            size = 60.dp,
            fontSize = 18.sp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_LB, it) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 120.dp, top = 22.dp)
                .testTag("btn_lb")
        )

        // D-Pad Cross (4 separate circular buttons with chevrons)
        // Center of D-Pad is placed at start = 84.dp, vertically centered
        val dpadCenterX = 84.dp
        val dpadSpacing = 56.dp

        // Up: ∧
        ChevronGamepadButton(
            direction = ChevronDirection.UP,
            isPressed = pressedMap[GamepadConstants.BTN_DPAD_UP] == true,
            size = 54.dp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_DPAD_UP, it) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = dpadCenterX, top = centerY - dpadSpacing - 27.dp)
                .testTag("btn_dpad_up")
        )

        // Down: ∨
        ChevronGamepadButton(
            direction = ChevronDirection.DOWN,
            isPressed = pressedMap[GamepadConstants.BTN_DPAD_DOWN] == true,
            size = 54.dp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_DPAD_DOWN, it) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = dpadCenterX, top = centerY + dpadSpacing - 27.dp)
                .testTag("btn_dpad_down")
        )

        // Left: < (At start = 24.dp, directly below LT)
        ChevronGamepadButton(
            direction = ChevronDirection.LEFT,
            isPressed = pressedMap[GamepadConstants.BTN_DPAD_LEFT] == true,
            size = 54.dp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_DPAD_LEFT, it) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = centerY - 27.dp)
                .testTag("btn_dpad_left")
        )

        // Right: >
        ChevronGamepadButton(
            direction = ChevronDirection.RIGHT,
            isPressed = pressedMap[GamepadConstants.BTN_DPAD_RIGHT] == true,
            size = 54.dp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_DPAD_RIGHT, it) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = dpadCenterX + dpadSpacing + 4.dp, top = centerY - 27.dp)
                .testTag("btn_dpad_right")
        )

        // L3 Button (Bottom-left corner, directly below Left D-pad and LT)
        CircularGamepadButton(
            text = "L3",
            isPressed = pressedMap[GamepadConstants.BTN_L3] == true,
            size = 56.dp,
            fontSize = 17.sp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_L3, it) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 22.dp)
                .testTag("btn_l3")
        )

        // Left Analog Stick (Dual concentric rings + tactile dot-matrix knob)
        AnalogThumbStick(
            isLeftStick = true,
            onStickMove = { x, y -> stateManager.setStick(true, x, y) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 142.dp, bottom = 10.dp)
                .size(168.dp)
                .testTag("left_analog_stick")
        )

        // ==========================================
        // 3. RIGHT SIDE CONTROLS
        // ==========================================

        // RT Button (Top-right corner)
        CircularGamepadButton(
            text = "RT",
            isPressed = pressedMap[GamepadConstants.BTN_RT] == true,
            size = 60.dp,
            fontSize = 18.sp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_RT, it) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp, top = 22.dp)
                .testTag("btn_rt")
        )

        // RB Button (Adjacent to RT)
        CircularGamepadButton(
            text = "RB",
            isPressed = pressedMap[GamepadConstants.BTN_RB] == true,
            size = 60.dp,
            fontSize = 18.sp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_RB, it) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 120.dp, top = 22.dp)
                .testTag("btn_rb")
        )

        // ABXY Diamond
        // Center of ABXY diamond is at end = 84.dp, vertically centered
        val abxyCenterEnd = 84.dp
        val abxySpacing = 56.dp

        // Y (Top)
        CircularGamepadButton(
            text = "Y",
            isPressed = pressedMap[GamepadConstants.BTN_Y] == true,
            size = 54.dp,
            fontSize = 20.sp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_Y, it) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = abxyCenterEnd, top = centerY - abxySpacing - 27.dp)
                .testTag("btn_y")
        )

        // A (Bottom)
        CircularGamepadButton(
            text = "A",
            isPressed = pressedMap[GamepadConstants.BTN_A] == true,
            size = 54.dp,
            fontSize = 20.sp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_A, it) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = abxyCenterEnd, top = centerY + abxySpacing - 27.dp)
                .testTag("btn_a")
        )

        // X (Left)
        CircularGamepadButton(
            text = "X",
            isPressed = pressedMap[GamepadConstants.BTN_X] == true,
            size = 54.dp,
            fontSize = 20.sp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_X, it) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = abxyCenterEnd + abxySpacing + 4.dp, top = centerY - 27.dp)
                .testTag("btn_x")
        )

        // B (Right, at end = 24.dp directly below RT)
        CircularGamepadButton(
            text = "B",
            isPressed = pressedMap[GamepadConstants.BTN_B] == true,
            size = 54.dp,
            fontSize = 20.sp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_B, it) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp, top = centerY - 27.dp)
                .testTag("btn_b")
        )

        // R3 Button (Bottom-right corner, directly below B and RT)
        CircularGamepadButton(
            text = "R3",
            isPressed = pressedMap[GamepadConstants.BTN_R3] == true,
            size = 56.dp,
            fontSize = 17.sp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_R3, it) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 22.dp)
                .testTag("btn_r3")
        )

        // Right Analog Stick (Dual concentric rings + tactile dot-matrix knob)
        AnalogThumbStick(
            isLeftStick = false,
            onStickMove = { x, y -> stateManager.setStick(false, x, y) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 142.dp, bottom = 10.dp)
                .size(168.dp)
                .testTag("right_analog_stick")
        )
    }
}

/**
 * Clean circular button matching the GeForce NOW overlay aesthetic.
 */
@Composable
fun CircularGamepadButton(
    text: String,
    isPressed: Boolean,
    size: Dp = 56.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 18.sp,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isPressed) Color.White else Color(0x75FFFFFF)
    val bgColor = if (isPressed) Color(0x66FFFFFF) else Color(0x30000000)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor, CircleShape)
            .border(1.5.dp, borderColor, CircleShape)
            .pointerInput(text) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onPressChange(true)
                    val pointerId = down.id

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            onPressChange(false)
                            break
                        }
                        change.consume()
                    }
                }
            }
    ) {
        Text(
            text = text,
            color = Color(0xF5FFFFFF),
            fontSize = fontSize,
            fontWeight = FontWeight.Medium
        )
    }
}

enum class ChevronDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Circular D-Pad button drawing the crisp vector chevrons from the GeForce NOW overlay.
 */
@Composable
fun ChevronGamepadButton(
    direction: ChevronDirection,
    isPressed: Boolean,
    size: Dp = 54.dp,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isPressed) Color.White else Color(0x75FFFFFF)
    val bgColor = if (isPressed) Color(0x66FFFFFF) else Color(0x30000000)
    val strokeColor = if (isPressed) Color.White else Color(0xF5FFFFFF)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor, CircleShape)
            .border(1.5.dp, borderColor, CircleShape)
            .pointerInput(direction) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onPressChange(true)
                    val pointerId = down.id

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            onPressChange(false)
                            break
                        }
                        change.consume()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.size(size * 0.42f)) {
            val w = this.size.width
            val h = this.size.height
            val strokeW = 2.4.dp.toPx()

            val path = Path()
            when (direction) {
                ChevronDirection.UP -> {
                    path.moveTo(0f, h * 0.72f)
                    path.lineTo(w / 2f, h * 0.18f)
                    path.lineTo(w, h * 0.72f)
                }
                ChevronDirection.DOWN -> {
                    path.moveTo(0f, h * 0.28f)
                    path.lineTo(w / 2f, h * 0.82f)
                    path.lineTo(w, h * 0.28f)
                }
                ChevronDirection.LEFT -> {
                    path.moveTo(w * 0.72f, 0f)
                    path.lineTo(w * 0.18f, h / 2f)
                    path.lineTo(w * 0.72f, h)
                }
                ChevronDirection.RIGHT -> {
                    path.moveTo(w * 0.28f, 0f)
                    path.lineTo(w * 0.82f, h / 2f)
                    path.lineTo(w * 0.28f, h)
                }
            }

            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(
                    width = strokeW,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

enum class ArrowDirection { LEFT, RIGHT }

/**
 * Capsule / stadium pill button for View (◀) and Menu (▶) buttons.
 */
@Composable
fun PillIconButton(
    direction: ArrowDirection,
    isPressed: Boolean,
    onPressChange: (Boolean) -> Unit,
    testTag: String
) {
    val borderColor = if (isPressed) Color.White else Color(0x75FFFFFF)
    val bgColor = if (isPressed) Color(0x66FFFFFF) else Color(0x30000000)
    val arrowColor = if (isPressed) Color.White else Color(0xF5FFFFFF)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 54.dp, height = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor, RoundedCornerShape(16.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
            .testTag(testTag)
            .pointerInput(direction) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onPressChange(true)
                    val pointerId = down.id

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            onPressChange(false)
                            break
                        }
                        change.consume()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            val w = this.size.width
            val h = this.size.height
            val path = Path()

            if (direction == ArrowDirection.LEFT) {
                path.moveTo(w, 0f)
                path.lineTo(0f, h / 2f)
                path.lineTo(w, h)
                path.close()
            } else {
                path.moveTo(0f, 0f)
                path.lineTo(w, h / 2f)
                path.lineTo(0f, h)
                path.close()
            }

            drawPath(path = path, color = arrowColor, style = Fill)
        }
    }
}

/**
 * Center GeForce NOW gamepad circular button:
 * Features the controller silhouette with the downward dropdown triangle below it.
 * Tapping opens the quick settings/overlay control menu and triggers Guide input.
 */
@Composable
fun GfnGamepadMenuButton(
    isPressed: Boolean,
    onPressChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    testTag: String
) {
    val borderColor = if (isPressed) Color.White else Color(0x75FFFFFF)
    val bgColor = if (isPressed) Color(0x66FFFFFF) else Color(0x30000000)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bgColor, CircleShape)
            .border(1.5.dp, borderColor, CircleShape)
            .testTag(testTag)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onPressChange(true)
                    val pointerId = down.id

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            onPressChange(false)
                            onOpenSettings()
                            break
                        }
                        change.consume()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val w = size.width
            val h = size.height
            val iconColor = Color.White

            // Draw game controller outline
            val ctrlBody = Path().apply {
                // Top curve
                moveTo(w * 0.22f, h * 0.24f)
                lineTo(w * 0.78f, h * 0.24f)
                // Right shoulder
                quadraticTo(w * 0.94f, h * 0.28f, w * 0.92f, h * 0.52f)
                // Right grip
                quadraticTo(w * 0.90f, h * 0.76f, w * 0.76f, h * 0.74f)
                // Right inner crook
                quadraticTo(w * 0.65f, h * 0.70f, w * 0.58f, h * 0.54f)
                // Center valley
                lineTo(w * 0.42f, h * 0.54f)
                // Left inner crook
                quadraticTo(w * 0.35f, h * 0.70f, w * 0.24f, h * 0.74f)
                // Left grip
                quadraticTo(w * 0.10f, h * 0.76f, w * 0.08f, h * 0.52f)
                // Left shoulder
                quadraticTo(w * 0.06f, h * 0.28f, w * 0.22f, h * 0.24f)
                close()
            }

            drawPath(
                path = ctrlBody,
                color = iconColor,
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // D-Pad cross on left
            val dpadSize = 3.dp.toPx()
            drawLine(
                color = iconColor,
                start = Offset(w * 0.30f - dpadSize, h * 0.42f),
                end = Offset(w * 0.30f + dpadSize, h * 0.42f),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = iconColor,
                start = Offset(w * 0.30f, h * 0.42f - dpadSize),
                end = Offset(w * 0.30f, h * 0.42f + dpadSize),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Action buttons on right
            drawCircle(
                color = iconColor,
                radius = 1.2.dp.toPx(),
                center = Offset(w * 0.70f, h * 0.38f)
            )
            drawCircle(
                color = iconColor,
                radius = 1.2.dp.toPx(),
                center = Offset(w * 0.70f, h * 0.47f)
            )

            // Downward triangle ▼ below controller
            val tri = Path().apply {
                moveTo(w * 0.38f, h * 0.82f)
                lineTo(w * 0.62f, h * 0.82f)
                lineTo(w * 0.50f, h * 0.98f)
                close()
            }
            drawPath(path = tri, color = iconColor, style = Fill)
        }
    }
}

/**
 * Top-right red radiating waves network broadcast indicator matching the screenshot ((•)).
 */
@Composable
fun NetworkBroadcastIndicator(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(34.dp)) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val red = Color(0xFFEF4444) // Bright warning red

        // Center dot
        drawCircle(
            color = red,
            radius = 2.4.dp.toPx(),
            center = center
        )

        // Left inner wave
        drawArc(
            color = red,
            startAngle = 135f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - 7.dp.toPx(), center.y - 7.dp.toPx()),
            size = Size(14.dp.toPx(), 14.dp.toPx()),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Left outer wave
        drawArc(
            color = red,
            startAngle = 135f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - 12.dp.toPx(), center.y - 12.dp.toPx()),
            size = Size(24.dp.toPx(), 24.dp.toPx()),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Right inner wave
        drawArc(
            color = red,
            startAngle = 315f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - 7.dp.toPx(), center.y - 7.dp.toPx()),
            size = Size(14.dp.toPx(), 14.dp.toPx()),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Right outer wave
        drawArc(
            color = red,
            startAngle = 315f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - 12.dp.toPx(), center.y - 12.dp.toPx()),
            size = Size(24.dp.toPx(), 24.dp.toPx()),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * Analog stick matching the reference image:
 * - Large outer concentric circle ring (thin border)
 * - Large middle concentric circle ring (thin border)
 * - Solid light-gray thumb knob with tactile dot matrix grid in the center
 * - Continuous smooth analog movement and spring-back on release
 */
@Composable
fun AnalogThumbStick(
    isLeftStick: Boolean,
    onStickMove: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var rawOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    val animatedOffset by animateOffsetAsState(
        targetValue = if (isDragging) rawOffset else Offset.Zero,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 850f),
        label = "stickSpring"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .pointerInput(isLeftStick) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isDragging = true

                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxTravelRadius = size.width / 2f * 0.65f
                    val pointerId = down.id

                    fun updatePosition(pos: Offset) {
                        val delta = pos - center
                        val distance = delta.getDistance()
                        val angle = atan2(delta.y, delta.x)
                        val clampedDistance = distance.coerceAtMost(maxTravelRadius)

                        val clampedOffset = if (distance > 0f) {
                            Offset(cos(angle) * clampedDistance, sin(angle) * clampedDistance)
                        } else Offset.Zero

                        rawOffset = clampedOffset
                        val normX = (clampedOffset.x / maxTravelRadius).coerceIn(-1f, 1f)
                        val normY = (clampedOffset.y / maxTravelRadius).coerceIn(-1f, 1f)
                        onStickMove(normX, normY)
                    }

                    updatePosition(down.position)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            isDragging = false
                            rawOffset = Offset.Zero
                            onStickMove(0f, 0f)
                            break
                        }
                        change.consume()
                        updatePosition(change.position)
                    }
                }
            }
    ) {
        // Dual Concentric Rings Background Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.width / 2f - 2.dp.toPx()
            val middleRadius = outerRadius * 0.72f

            // Outer concentric ring
            drawCircle(
                color = Color(0x45FFFFFF),
                radius = outerRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Middle concentric ring
            drawCircle(
                color = Color(0x35FFFFFF),
                radius = middleRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // Thumb Knob with tactile dot-matrix grip disc (exact match to screenshot)
        val knobSize = 70.dp
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { IntOffset(animatedOffset.x.roundToInt(), animatedOffset.y.roundToInt()) }
                .size(knobSize)
                .clip(CircleShape)
                .background(Color(0xFFCBD5E1), CircleShape)
                .border(2.dp, Color(0xFFE2E8F0), CircleShape)
        ) {
            // Tactile 4x4 dot-matrix grip pattern on knob
            Canvas(modifier = Modifier.size(34.dp)) {
                val dotRadius = 1.35.dp.toPx()
                val dotColor = Color(0xFF64748B)
                val rows = 4
                val cols = 4
                val spacing = size.width / (cols + 1)

                for (r in 1..rows) {
                    for (c in 1..cols) {
                        drawCircle(
                            color = dotColor,
                            radius = dotRadius,
                            center = Offset(c * spacing, r * spacing)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Custom vector canvas drawing the iconic Xbox sphere logo.
 */
@Composable
fun XboxLogoIcon(size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val center = Offset(radius, radius)

        // Draw outer ring
        drawCircle(
            color = Color.White,
            radius = radius - 1f,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Draw the distinctive curved 'X' paths of the Xbox logo
        val strokeWidth = 2.4.dp.toPx()
        val path1 = Path().apply {
            moveTo(center.x - radius * 0.52f, center.y - radius * 0.52f)
            quadraticTo(
                center.x - radius * 0.1f, center.y,
                center.x - radius * 0.55f, center.y + radius * 0.52f
            )
        }
        val path2 = Path().apply {
            moveTo(center.x + radius * 0.52f, center.y - radius * 0.52f)
            quadraticTo(
                center.x + radius * 0.1f, center.y,
                center.x + radius * 0.55f, center.y + radius * 0.52f
            )
        }
        drawPath(path1, color = Color.White, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        drawPath(path2, color = Color.White, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))

        // Center top crest arc
        val topCrest = Path().apply {
            moveTo(center.x - radius * 0.28f, center.y - radius * 0.45f)
            quadraticTo(center.x, center.y - radius * 0.2f, center.x + radius * 0.28f, center.y - radius * 0.45f)
        }
        drawPath(topCrest, color = Color.White, style = Stroke(width = strokeWidth * 0.9f, cap = StrokeCap.Round))
    }
}

/**
 * On-screen real-time FPS Pill badge displaying stream framerate and status.
 * Tapping it allows directly toggling off the badge without opening settings.
 */
@Composable
fun FpsPillBadge(
    fps: Int,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val displayFps = fps.coerceIn(15, 144)
    val isHighFps = displayFps >= 55
    val dotColor = if (isHighFps) Color(0xFF10B981) else Color(0xFFF59E0B)

    Row(
        modifier = modifier
            .background(Color(0x70000000), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0x35FFFFFF), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .testTag("fps_pill_badge"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$displayFps FPS",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

