package com.example.controller.ui

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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

@Composable
fun GFNControllerOverlay(
    stateManager: ControllerStateManager,
    opacity: Float = 0.9f,
    hapticFeedbackEnabled: Boolean = true,
    pingMs: Int = 32,
    onTriggerHaptic: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Keep local track of pressed states for immediate visual feedback
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
        val screenWidth = maxWidth
        val screenHeight = maxHeight

        // ==========================================
        // 1. TOP STATUS BAR (GeForce NOW style compact header)
        // ==========================================
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .background(
                    color = Color(0xC0141416),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.12f),
                    shape = CircleShape
                )
                .padding(horizontal = 14.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Xbox branding
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("status_xbox_branding")
            ) {
                XboxLogoIcon(size = 22.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Xbox Cloud",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Center buttons: View (◀), Xbox Guide, Menu (▶)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("center_nav_buttons")
            ) {
                // View / Back button (Pill with left triangle)
                PillIconButton(
                    iconText = "◀",
                    isPressed = pressedMap[GamepadConstants.BTN_VIEW] == true,
                    onPressChange = { handleButtonChange(GamepadConstants.BTN_VIEW, it) },
                    testTag = "btn_view"
                )

                Spacer(modifier = Modifier.width(10.dp))

                // Xbox Guide button (Center glowing circle)
                XboxGuideButton(
                    isPressed = pressedMap[GamepadConstants.BTN_GUIDE] == true,
                    onPressChange = { handleButtonChange(GamepadConstants.BTN_GUIDE, it) },
                    testTag = "btn_guide"
                )

                Spacer(modifier = Modifier.width(10.dp))

                // Menu / Start button (Pill with right triangle)
                PillIconButton(
                    iconText = "▶",
                    isPressed = pressedMap[GamepadConstants.BTN_MENU] == true,
                    onPressChange = { handleButtonChange(GamepadConstants.BTN_MENU, it) },
                    testTag = "btn_menu"
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Network info & Settings gear
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("status_network_info")
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = "Wi-Fi status",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "$pingMs ms",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ==========================================
        // 2. LEFT SIDE CONTROLS
        // ==========================================

        // LT Button (Top-left, placed upward at natural shoulder position)
        CircularGamepadButton(
            text = "LT",
            isPressed = pressedMap[GamepadConstants.BTN_LT] == true,
            size = 56.dp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_LT, it) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 28.dp, top = 16.dp)
                .testTag("btn_lt")
        )

        // LB Button (Adjacent to LT, placed upward)
        CircularGamepadButton(
            text = "LB",
            isPressed = pressedMap[GamepadConstants.BTN_LB] == true,
            size = 56.dp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_LB, it) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 98.dp, top = 16.dp)
                .testTag("btn_lb")
        )

        // D-Pad (Up, Left, Right, Down)
        val dpadCenterOffset = Offset(105f, 0f) // dp offsets from start
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 40.dp)
                .size(170.dp),
            contentAlignment = Alignment.Center
        ) {
            // Up
            CircularGamepadButton(
                text = "∧",
                isPressed = pressedMap[GamepadConstants.BTN_DPAD_UP] == true,
                size = 50.dp,
                fontSize = 20.sp,
                onPressChange = { handleButtonChange(GamepadConstants.BTN_DPAD_UP, it) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .testTag("btn_dpad_up")
            )
            // Down
            CircularGamepadButton(
                text = "∨",
                isPressed = pressedMap[GamepadConstants.BTN_DPAD_DOWN] == true,
                size = 50.dp,
                fontSize = 20.sp,
                onPressChange = { handleButtonChange(GamepadConstants.BTN_DPAD_DOWN, it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .testTag("btn_dpad_down")
            )
            // Left
            CircularGamepadButton(
                text = "<",
                isPressed = pressedMap[GamepadConstants.BTN_DPAD_LEFT] == true,
                size = 50.dp,
                fontSize = 20.sp,
                onPressChange = { handleButtonChange(GamepadConstants.BTN_DPAD_LEFT, it) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .testTag("btn_dpad_left")
            )
            // Right
            CircularGamepadButton(
                text = ">",
                isPressed = pressedMap[GamepadConstants.BTN_DPAD_RIGHT] == true,
                size = 50.dp,
                fontSize = 20.sp,
                onPressChange = { handleButtonChange(GamepadConstants.BTN_DPAD_RIGHT, it) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .testTag("btn_dpad_right")
            )
        }

        // Left Analog Stick (Dual concentric rings with dotted grip knob)
        AnalogThumbStick(
            isLeftStick = true,
            onStickMove = { x, y -> stateManager.setStick(true, x, y) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 155.dp, bottom = 24.dp)
                .size(150.dp)
                .testTag("left_analog_stick")
        )

        // L3 Button (Bottom-left corner)
        CircularGamepadButton(
            text = "L3",
            isPressed = pressedMap[GamepadConstants.BTN_L3] == true,
            size = 54.dp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_L3, it) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 40.dp, bottom = 28.dp)
                .testTag("btn_l3")
        )

        // ==========================================
        // 3. RIGHT SIDE CONTROLS
        // ==========================================

        // RB Button (Adjacent to RT, placed upward)
        CircularGamepadButton(
            text = "RB",
            isPressed = pressedMap[GamepadConstants.BTN_RB] == true,
            size = 56.dp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_RB, it) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 98.dp, top = 16.dp)
                .testTag("btn_rb")
        )

        // RT Button (Top-right, placed upward at natural shoulder position)
        CircularGamepadButton(
            text = "RT",
            isPressed = pressedMap[GamepadConstants.BTN_RT] == true,
            size = 56.dp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_RT, it) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 28.dp, top = 16.dp)
                .testTag("btn_rt")
        )

        // ABXY Diamond (Y on top, A on bottom, X on left, B on right)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 40.dp)
                .size(170.dp),
            contentAlignment = Alignment.Center
        ) {
            // Y (Top)
            CircularGamepadButton(
                text = "Y",
                isPressed = pressedMap[GamepadConstants.BTN_Y] == true,
                size = 50.dp,
                fontSize = 18.sp,
                onPressChange = { handleButtonChange(GamepadConstants.BTN_Y, it) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .testTag("btn_y")
            )
            // A (Bottom)
            CircularGamepadButton(
                text = "A",
                isPressed = pressedMap[GamepadConstants.BTN_A] == true,
                size = 50.dp,
                fontSize = 18.sp,
                onPressChange = { handleButtonChange(GamepadConstants.BTN_A, it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .testTag("btn_a")
            )
            // X (Left)
            CircularGamepadButton(
                text = "X",
                isPressed = pressedMap[GamepadConstants.BTN_X] == true,
                size = 50.dp,
                fontSize = 18.sp,
                onPressChange = { handleButtonChange(GamepadConstants.BTN_X, it) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .testTag("btn_x")
            )
            // B (Right)
            CircularGamepadButton(
                text = "B",
                isPressed = pressedMap[GamepadConstants.BTN_B] == true,
                size = 50.dp,
                fontSize = 18.sp,
                onPressChange = { handleButtonChange(GamepadConstants.BTN_B, it) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .testTag("btn_b")
            )
        }

        // Right Analog Stick (Dual concentric rings with dotted grip knob)
        AnalogThumbStick(
            isLeftStick = false,
            onStickMove = { x, y -> stateManager.setStick(false, x, y) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 155.dp, bottom = 24.dp)
                .size(150.dp)
                .testTag("right_analog_stick")
        )

        // R3 Button (Bottom-right corner)
        CircularGamepadButton(
            text = "R3",
            isPressed = pressedMap[GamepadConstants.BTN_R3] == true,
            size = 54.dp,
            onPressChange = { handleButtonChange(GamepadConstants.BTN_R3, it) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 40.dp, bottom = 28.dp)
                .testTag("btn_r3")
        )
    }
}

/**
 * Clean circular button matching the GFN overlay in the reference image.
 */
@Composable
fun CircularGamepadButton(
    text: String,
    isPressed: Boolean,
    size: Dp = 56.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isPressed) Color.White else Color(0x80FFFFFF)
    val bgColor = if (isPressed) Color(0x66FFFFFF) else Color(0x2E0F172A)
    val textColor = Color.White

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
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Capsule / stadium button for View (◀) and Menu (▶) buttons.
 */
@Composable
fun PillIconButton(
    iconText: String,
    isPressed: Boolean,
    onPressChange: (Boolean) -> Unit,
    testTag: String
) {
    val borderColor = if (isPressed) Color.White else Color(0x80FFFFFF)
    val bgColor = if (isPressed) Color(0x66FFFFFF) else Color(0x2E0F172A)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 56.dp, height = 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(bgColor, RoundedCornerShape(15.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(15.dp))
            .testTag(testTag)
            .pointerInput(iconText) {
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
            text = iconText,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

/**
 * Center Xbox Guide circular button with the glowing ring and white Xbox sphere logo.
 */
@Composable
fun XboxGuideButton(
    isPressed: Boolean,
    onPressChange: (Boolean) -> Unit,
    testTag: String
) {
    val borderColor = if (isPressed) Color.White else Color(0x99FFFFFF)
    val bgColor = if (isPressed) Color(0x66FFFFFF) else Color(0x2E0F172A)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(42.dp)
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
                            break
                        }
                        change.consume()
                    }
                }
            }
    ) {
        XboxLogoIcon(size = 24.dp)
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
 * Analog stick component matching the exact screenshot:
 * - Outer concentric circular ring
 * - Middle concentric circular ring
 * - Thumb knob disc with dotted tactile grip pattern (4x4 grid of circular dots)
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
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
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
        // Concentric Rings Background Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.width / 2f - 2.dp.toPx()
            val middleRadius = outerRadius * 0.72f

            // Outer ring
            drawCircle(
                color = Color(0x4DFFFFFF),
                radius = outerRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Middle ring
            drawCircle(
                color = Color(0x33FFFFFF),
                radius = middleRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // Thumb Knob with dotted grip pattern (as shown in the reference image)
        val knobSize = 68.dp
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { IntOffset(animatedOffset.x.roundToInt(), animatedOffset.y.roundToInt()) }
                .size(knobSize)
                .clip(CircleShape)
                .background(Color(0xFFCBD5E1), CircleShape)
                .border(2.dp, Color(0xFFE2E8F0), CircleShape)
        ) {
            // Tactile dot matrix grip pattern in center of the knob
            Canvas(modifier = Modifier.size(32.dp)) {
                val dotRadius = 1.3.dp.toPx()
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
