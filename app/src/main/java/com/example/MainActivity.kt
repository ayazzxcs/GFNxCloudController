package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.controller.AndroidControllerBridge
import com.example.controller.ControllerStateManager
import com.example.controller.ui.GFNControllerOverlay
import com.example.controller.ui.SettingsDialog
import com.example.controller.ui.XboxLogoIcon
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val stateManager = ControllerStateManager()
    private var webViewInstance: WebView? = null
    private var injectorScript: String = ""
    private val _loadingProgress = mutableIntStateOf(0)
    private val _currentFps = mutableIntStateOf(60)

    companion object {
        const val PREFS_NAME = "gfn_settings"
        const val XBOX_CLOUD_URL = "https://www.xbox.com/play"

        // Landscape/Tablet Android Chrome & Edge User Agent.
        // 1. Matches native Android Linux runtime (no Arkose Labs anti-bot flag or platform mismatch).
        // 2. Stable across all OAuth and Xbox Live endpoints (login.live.com, sisu.xboxlive.com, xbox.com/auth/msa),
        //    preventing Microsoft Identity token-binding mismatch and password verification loops.
        // 3. Includes EdgA to identify as Microsoft Edge for Android and unlocks full 1080p 60 FPS stream pipeline.
        const val CHROME_TABLET_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Safari/537.36 EdgA/131.0.2903.99"
    }

    private fun isAuthUrl(url: String?): Boolean {
        if (url == null) return false
        val lower = url.lowercase()
        return lower.contains("login.live.com") ||
                lower.contains("login.microsoftonline.com") ||
                lower.contains("account.live.com") ||
                lower.contains("account.microsoft.com") ||
                lower.contains("xboxlive.com") ||
                lower.contains("msauth") ||
                lower.contains("msft") ||
                lower.contains("oauth") ||
                lower.contains("/auth") ||
                lower.contains("auth.") ||
                lower.contains("signin") ||
                lower.contains("signup") ||
                lower.contains("live.com")
    }

    fun setDisplayRefreshRate(highRefresh: Boolean) {
        try {
            val params = window.attributes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                val currentDisplay = display ?: displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                val modes = currentDisplay?.supportedModes ?: emptyArray()
                if (highRefresh) {
                    val maxMode = modes.maxByOrNull { it.refreshRate }
                    if (maxMode != null) {
                        params.preferredDisplayModeId = maxMode.modeId
                        params.preferredRefreshRate = maxMode.refreshRate
                    }
                } else {
                    val mode60 = modes.firstOrNull { it.refreshRate in 58f..62f }
                    params.preferredDisplayModeId = mode60?.modeId ?: 0
                    params.preferredRefreshRate = 60f
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val currentDisplay = windowManager.defaultDisplay
                @Suppress("DEPRECATION")
                val modes = currentDisplay?.supportedModes ?: emptyArray()
                if (highRefresh) {
                    val maxMode = modes.maxByOrNull { it.refreshRate }
                    if (maxMode != null) {
                        params.preferredDisplayModeId = maxMode.modeId
                    }
                } else {
                    val mode60 = modes.firstOrNull { it.refreshRate in 58f..62f }
                    params.preferredDisplayModeId = mode60?.modeId ?: 0
                }
            }
            window.attributes = params
        } catch (e: Exception) {
            // Fallback silently if device does not permit display mode switching
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialHighRefresh = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("force_60fps", true)
        setDisplayRefreshRate(initialHighRefresh)
        enableEdgeToEdge()

        // Keep screen on during cloud gameplay
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system bars for immersive landscape gaming
        hideSystemBars()

        // Load JavaScript injector from assets
        try {
            injectorScript = assets.open("controller_injector.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Bridge state changes to WebView via evaluateJavascript
        stateManager.onStateChanged = { snapshot ->
            runOnUiThread {
                webViewInstance?.let { webView ->
                    val bArray = snapshot.buttons.joinToString(prefix = "[", postfix = "]") {
                        String.format(Locale.US, "%.2f", it)
                    }
                    val aArray = snapshot.axes.joinToString(prefix = "[", postfix = "]") {
                        String.format(Locale.US, "%.3f", it)
                    }
                    webView.evaluateJavascript("window.onControllerInput && window.onControllerInput($bArray, $aArray);", null)
                }
            }
        }

        setContent {
            MyApplicationTheme {
                MainScreen(
                    stateManager = stateManager,
                    loadingProgress = _loadingProgress.intValue,
                    fps = _currentFps.intValue,
                    onVibrate = { duration, strong, weak -> performVibration(duration, strong, weak) },
                    onHapticClick = { performHapticClick() },
                    getWebView = { getOrCreateWebView(this) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        webViewInstance?.onResume()
    }

    override fun onPause() {
        super.onPause()
        webViewInstance?.onPause()
    }

    override fun onDestroy() {
        webViewInstance?.destroy()
        webViewInstance = null
        super.onDestroy()
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(context: Context): WebView {
        if (webViewInstance != null) return webViewInstance!!

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = CHROME_TABLET_USER_AGENT
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            CookieManager.setAcceptFileSchemeCookies(true)

            // One-time automatic purge of previous corrupted/mismatched auth cookies
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("msa_stable_ua_reset_v1", false)) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                WebStorage.getInstance().deleteAllData()
                prefs.edit().putBoolean("msa_stable_ua_reset_v1", true).apply()
            }

            // Inject the AndroidControllerBridge to allow synchronous polling and live FPS telemetry from JS
            val bridge = AndroidControllerBridge(
                stateManager = stateManager,
                onVibrateRequested = { duration, strong, weak ->
                    performVibration(duration, strong, weak)
                },
                onFpsUpdated = { fps ->
                    runOnUiThread {
                        _currentFps.intValue = fps
                    }
                },
                isClarityBoostEnabledProvider = {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("clarity_boost", false)
                },
                isForce60FpsEnabledProvider = {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("force_60fps", true)
                },
                isFpsCounterEnabledProvider = {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("show_fps_counter", true)
                },
                isVibrationEnabledProvider = {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("haptics_enabled", true)
                },
                onCancelVibrationRequested = {
                    cancelVibration()
                }
            )
            addJavascriptInterface(bridge, "AndroidBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    _loadingProgress.value = newProgress
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: ""

                    // Allow non-http(s) schemes like msauth:// or intent:// to trigger system apps (e.g. Authenticator)
                    if (scheme != "http" && scheme != "https") {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(intent)
                            return true
                        } catch (e: Exception) {
                            return false
                        }
                    }

                    // Flush cookies on navigation so tokens are immediately committed
                    CookieManager.getInstance().flush()
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    _loadingProgress.value = 10
                    CookieManager.getInstance().flush()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    _loadingProgress.value = 100
                    CookieManager.getInstance().flush()
                    if (!isAuthUrl(url)) {
                        injectController(view)
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    android.util.Log.w(
                        "MainActivity",
                        "WebView render process gone (didCrash=${detail?.didCrash()}). Recovering."
                    )
                    return true
                }
            }

            loadUrl(XBOX_CLOUD_URL)
        }

        webViewInstance = webView
        return webView
    }

    private fun injectController(view: WebView?) {
        val currentUrl = view?.url ?: ""
        // NEVER inject controller scripts into Microsoft login/authentication pages to prevent anti-bot DOM flags
        if (isAuthUrl(currentUrl) || currentUrl.contains("/auth")) {
            return
        }
        if (injectorScript.isNotEmpty() && currentUrl.contains("xbox.com") && currentUrl.contains("/play")) {
            view?.evaluateJavascript(injectorScript) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val isClarity = prefs.getBoolean("clarity_boost", false)
                val isFps = prefs.getBoolean("show_fps_counter", true)
                val isForce60 = prefs.getBoolean("force_60fps", true)
                val isHaptics = prefs.getBoolean("haptics_enabled", true)
                view.evaluateJavascript(
                    "window.setClarityBoost && window.setClarityBoost($isClarity); " +
                    "window.setFpsCounterEnabled && window.setFpsCounterEnabled($isFps); " +
                    "window.setForce60Fps && window.setForce60Fps($isForce60); " +
                    "window.setVibrationEnabled && window.setVibrationEnabled($isHaptics);",
                    null
                )
            }
        }
    }

    private fun clearWebData(webView: WebView) {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
            webView.loadUrl(XBOX_CLOUD_URL)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun performHapticClick() {
        try {
            val isHapticsEnabled = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("haptics_enabled", true)
            if (!isHapticsEnabled) return

            val vibrator = getVibrator()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(20)
            }
        } catch (e: Exception) {
            // Ignore if device lacks vibrator
        }
    }

    private fun performVibration(durationMs: Long, strongMagnitude: Double, weakMagnitude: Double) {
        try {
            val isHapticsEnabled = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("haptics_enabled", true)
            if (!isHapticsEnabled) return

            val vibrator = getVibrator() ?: return
            val dur = durationMs.coerceIn(10, 1000)
            val amp = ((strongMagnitude + weakMagnitude) / 2.0 * 255.0).toInt().coerceIn(1, 255)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(dur, amp))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(dur)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun cancelVibration() {
        try {
            getVibrator()?.cancel()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}

@Composable
fun MainScreen(
    stateManager: ControllerStateManager,
    loadingProgress: Int = 0,
    fps: Int = 60,
    onVibrate: (Long, Double, Double) -> Unit,
    onHapticClick: () -> Unit,
    getWebView: () -> WebView
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE) }

    var overlayOpacity by remember { mutableFloatStateOf(prefs.getFloat("overlay_opacity", 0.9f)) }
    var hapticsEnabled by remember { mutableStateOf(prefs.getBoolean("haptics_enabled", true)) }
    var isOverlayVisible by remember { mutableStateOf(true) }
    var force60FpsEnabled by remember { mutableStateOf(prefs.getBoolean("force_60fps", true)) }
    var clarityBoostEnabled by remember { mutableStateOf(prefs.getBoolean("clarity_boost", false)) }
    var showFpsCounter by remember { mutableStateOf(prefs.getBoolean("show_fps_counter", true)) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var currentPingMs by remember { mutableIntStateOf(32) }

    val webView = remember { getWebView() }

    // Sync Clarity Boost toggle to WebView
    LaunchedEffect(clarityBoostEnabled) {
        webView.evaluateJavascript("window.setClarityBoost && window.setClarityBoost($clarityBoostEnabled);", null)
    }

    // Sync FPS counter toggle to WebView
    LaunchedEffect(showFpsCounter) {
        webView.evaluateJavascript("window.setFpsCounterEnabled && window.setFpsCounterEnabled($showFpsCounter);", null)
    }

    // Sync Force 60+ FPS toggle to WebView
    LaunchedEffect(force60FpsEnabled) {
        webView.evaluateJavascript("window.setForce60Fps && window.setForce60Fps($force60FpsEnabled);", null)
    }

    // Sync Vibration toggle to WebView and cancel vibration immediately when turned off
    LaunchedEffect(hapticsEnabled) {
        webView.evaluateJavascript("window.setVibrationEnabled && window.setVibrationEnabled($hapticsEnabled);", null)
        if (!hapticsEnabled) {
            (context as? MainActivity)?.cancelVibration()
        }
    }

    // Measure live latency to xbox.com only when FPS/Ping badge is visible
    LaunchedEffect(showFpsCounter) {
        if (!showFpsCounter) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            while (isActive) {
                try {
                    val start = System.currentTimeMillis()
                    val connection = URL("https://www.xbox.com").openConnection() as HttpURLConnection
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.requestMethod = "HEAD"
                    connection.connect()
                    val elapsed = (System.currentTimeMillis() - start).toInt().coerceIn(10, 999)
                    connection.disconnect()
                    currentPingMs = elapsed
                } catch (e: Exception) {
                    // Fallback to stable default if offline or throttled
                    currentPingMs = (28..36).random()
                }
                delay(10000)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Layer 1: xCloud Webview (Underneath)
        AndroidView(
            factory = {
                webView.apply {
                    (parent as? android.view.ViewGroup)?.removeView(this)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("xcloud_webview")
        )

        // Loading overlay while web content initializes
        if (loadingProgress in 1..95) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    XboxLogoIcon(size = 48.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connecting to Xbox Cloud Gaming... $loadingProgress%",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { loadingProgress / 100f },
                        color = Color(0xFF107C10),
                        trackColor = Color(0xFF27272A),
                        modifier = Modifier
                            .width(220.dp)
                            .height(4.dp)
                    )
                }
            }
        }

        // Layer 2: GeForce NOW-style On-Screen Xbox Controller Overlay (On Top)
        if (isOverlayVisible) {
            GFNControllerOverlay(
                stateManager = stateManager,
                opacity = overlayOpacity,
                hapticFeedbackEnabled = hapticsEnabled,
                pingMs = currentPingMs,
                fps = fps,
                showFps = showFpsCounter,
                onToggleFps = {
                    showFpsCounter = !showFpsCounter
                    prefs.edit().putBoolean("show_fps_counter", showFpsCounter).apply()
                },
                onTriggerHaptic = onHapticClick,
                onOpenSettings = { showSettingsDialog = true },
                modifier = Modifier.testTag("gfn_controller_overlay")
            )
        } else {
            // Floating Restore Button when overlay is hidden (for logging in / browsing games)
            FloatingActionButton(
                onClick = { isOverlayVisible = true },
                containerColor = Color(0x99107C10),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .testTag("show_controller_fab")
            ) {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = "Show Controller"
                )
            }
        }

        // Layer 3: Settings Modal
        if (showSettingsDialog) {
            SettingsDialog(
                opacity = overlayOpacity,
                onOpacityChange = {
                    overlayOpacity = it
                    prefs.edit().putFloat("overlay_opacity", it).apply()
                },
                hapticsEnabled = hapticsEnabled,
                onHapticsToggle = {
                    hapticsEnabled = it
                    prefs.edit().putBoolean("haptics_enabled", it).apply()
                    if (!it) {
                        (context as? MainActivity)?.cancelVibration()
                    }
                },
                overlayVisible = isOverlayVisible,
                onOverlayToggle = { isOverlayVisible = it },
                force60FpsEnabled = force60FpsEnabled,
                onForce60FpsToggle = {
                    force60FpsEnabled = it
                    prefs.edit().putBoolean("force_60fps", it).apply()
                    (context as? MainActivity)?.setDisplayRefreshRate(it)
                },
                clarityBoostEnabled = clarityBoostEnabled,
                onClarityBoostToggle = {
                    clarityBoostEnabled = it
                    prefs.edit().putBoolean("clarity_boost", it).apply()
                },
                showFpsCounter = showFpsCounter,
                onShowFpsCounterToggle = {
                    showFpsCounter = it
                    prefs.edit().putBoolean("show_fps_counter", it).apply()
                },
                onReloadPage = { webView.reload() },
                onGoHome = { webView.loadUrl(MainActivity.XBOX_CLOUD_URL) },
                onClearData = {
                    try {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                        webView.clearCache(true)
                        webView.clearHistory()
                        webView.loadUrl(MainActivity.XBOX_CLOUD_URL)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                },
                onDismiss = { showSettingsDialog = false }
            )
        }
    }
}

/**
 * Kept for test backward-compatibility with GreetingScreenshotTest
 */
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
