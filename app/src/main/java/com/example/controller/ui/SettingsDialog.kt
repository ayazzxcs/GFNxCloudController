package com.example.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SettingsDialog(
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    hapticsEnabled: Boolean,
    onHapticsToggle: (Boolean) -> Unit,
    overlayVisible: Boolean,
    onOverlayToggle: (Boolean) -> Unit,
    gfnStickCurveEnabled: Boolean = true,
    onGfnStickCurveToggle: (Boolean) -> Unit = {},
    force60FpsEnabled: Boolean = true,
    onForce60FpsToggle: (Boolean) -> Unit = {},
    motionSmoothingEnabled: Boolean = true,
    onMotionSmoothingToggle: (Boolean) -> Unit = {},
    gfnReflexEnabled: Boolean = true,
    onGfnReflexToggle: (Boolean) -> Unit = {},
    gfnVividEnabled: Boolean = true,
    onGfnVividToggle: (Boolean) -> Unit = {},
    clarityBoostEnabled: Boolean = true,
    onClarityBoostToggle: (Boolean) -> Unit = {},
    showFpsCounter: Boolean = true,
    onShowFpsCounterToggle: (Boolean) -> Unit = {},
    onReloadPage: () -> Unit,
    onGoHome: () -> Unit,
    onClearData: () -> Unit = {},
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.92f)
                .padding(vertical = 10.dp)
                .testTag("settings_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                // Header (Pinned at top so Close button is always accessible)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SportsEsports,
                            contentDescription = null,
                            tint = Color(0xFF107C10), // Xbox Green
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Controller Settings",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("settings_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable container enabling sliding down options in landscape & compact screens
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                ) {
                    // Virtual Gamepad Status Banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1F2937), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Xbox 360 Controller (XInput Standard)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Synthetic Gamepad API active in WebView",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Opacity Slider
                Text(
                    text = "Overlay Opacity: ${(opacity * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = opacity,
                    onValueChange = onOpacityChange,
                    valueRange = 0.2f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF107C10),
                        activeTrackColor = Color(0xFF107C10),
                        inactiveTrackColor = Color(0xFF374151)
                    ),
                    modifier = Modifier.testTag("opacity_slider")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Haptic Feedback Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Vibration,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Button Haptics & Vibration",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                    Switch(
                        checked = hapticsEnabled,
                        onCheckedChange = onHapticsToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF107C10)
                        ),
                        modifier = Modifier.testTag("haptics_switch")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Overlay Visibility Switch (Allows hiding when signing in or navigating menus)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Show On-Screen Controller",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                    Switch(
                        checked = overlayVisible,
                        onCheckedChange = onOverlayToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF107C10)
                        ),
                        modifier = Modifier.testTag("overlay_visibility_switch")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // GeForce NOW Reflex Stick Curve Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = Color(0xFF76B900), // NVIDIA GeForce Green
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GeForce NOW Reflex Stick Curve",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Inner deadzone (0.04) and progressive curve for surgical micro-aiming without drift or lag",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                        )
                    }
                    Switch(
                        checked = gfnStickCurveEnabled,
                        onCheckedChange = onGfnStickCurveToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF76B900)
                        ),
                        modifier = Modifier.testTag("gfn_stick_curve_switch")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Force 60+ FPS & High Performance Stream Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Unlock 120Hz / 60+ FPS & Max Bitrate",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Requests up to 120 FPS via WebRTC SDP, 30 Mbps bandwidth & native 120Hz display refresh",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                        )
                    }
                    Switch(
                        checked = force60FpsEnabled,
                        onCheckedChange = onForce60FpsToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF107C10)
                        ),
                        modifier = Modifier.testTag("force_60fps_switch")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 30-to-60 FPS Motion Smoothing & Frame Pacing Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = Color(0xFF10B981), // Emerald
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "30-to-60 FPS Motion Smoothing",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Hardware-interpolates and frame-paces 30 FPS console titles to smooth 60Hz display cadence",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                        )
                    }
                    Switch(
                        checked = motionSmoothingEnabled,
                        onCheckedChange = onMotionSmoothingToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF10B981)
                        ),
                        modifier = Modifier.testTag("motion_smoothing_switch")
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Console 30 vs 60 FPS Notice Card
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp).padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "🎮 Why do some games run at 30 FPS?",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Games like Forza Horizon, Cyberpunk 2077, Fallout 4, and Starfield default to 30 FPS 'Visuals/Quality' mode on Xbox servers. Open the game's in-game Settings > Video/Graphics and switch to 'Performance Mode' to run natively at 60 FPS. Our stream pipeline and motion smoothing guarantee 60Hz presentation.",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // GeForce NOW Reflex Ultra-Low Latency Switch (Zero Jitter Buffer Queueing)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B), // Amber bolt
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GeForce NOW Reflex (0ms Jitter Buffer)",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Bypasses WebRTC buffer delay (playoutDelayHint=0, jitterBufferTarget=0) for instant response",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                        )
                    }
                    Switch(
                        checked = gfnReflexEnabled,
                        onCheckedChange = onGfnReflexToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFF59E0B)
                        ),
                        modifier = Modifier.testTag("gfn_reflex_switch")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // GeForce NOW Vivid Mode Switch (Digital Vibrance & Rich Contrast)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = null,
                                tint = Color(0xFFEC4899), // Pink / vibrant accent
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GeForce NOW Vivid Colors & Contrast",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Digital Vibrance profile enhancing dynamic range and punchy console colors",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                        )
                    }
                    Switch(
                        checked = gfnVividEnabled,
                        onCheckedChange = onGfnVividToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFEC4899)
                        ),
                        modifier = Modifier.testTag("gfn_vivid_switch")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Clarity Boost Switch (Sharpens stream video layer)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF06B6D4), // Cyan accent
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Clarity Boost (Visual Sharpening)",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Sharpens stream edges and enhances texture contrast (similar to Microsoft Edge)",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                        )
                    }
                    Switch(
                        checked = clarityBoostEnabled,
                        onCheckedChange = onClarityBoostToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF06B6D4)
                        ),
                        modifier = Modifier.testTag("clarity_boost_switch")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Show Live Stream FPS Counter Switch (On / Off)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(if (showFpsCounter) Color(0xFF10B981) else Color(0xFF4B5563), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "60",
                                    color = if (showFpsCounter) Color.Black else Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Show 60 FPS Stream Badge",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Toggle on/off the real-time FPS badge on screen (or tap badge to quickly hide)",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                        )
                    }
                    Switch(
                        checked = showFpsCounter,
                        onCheckedChange = onShowFpsCounterToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF107C10)
                        ),
                        modifier = Modifier.testTag("fps_badge_switch")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Actions: Reload and Go Home
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onReloadPage()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("reload_page_button")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reload", color = Color.White)
                    }

                    Button(
                        onClick = {
                            onDismiss()
                            onGoHome()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_page_button")
                    ) {
                        Icon(imageVector = Icons.Default.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("xCloud Home", color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Reset Login & Clear Cookies (fixes blocked Microsoft sign-in / lockouts)
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onClearData()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("clear_cookies_button")
                ) {
                    Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset Login Session & Clear Cookies", fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
}
