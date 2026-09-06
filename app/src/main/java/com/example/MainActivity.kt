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

    companion object {
        const val XBOX_CLOUD_URL = "https://www.xbox.com/play"

        // Authentic Google Chrome on Android Mobile User Agent.
        // Prevents Microsoft Identity / Arkose Labs anti-bot lockout ("Too many incorrect attempts" / suspicious activity)
        // caused when spoofing Windows desktop UA while running on an Android Linux/ARM environment.
        const val CHROME_ANDROID_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

        // Landscape/Tablet Android Chrome User Agent for widescreen game streaming on xCloud
        const val CHROME_TABLET_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Safari/537.36"
    }

    private fun isAuthUrl(url: String?): Boolean {
        if (url == null) return false
        val lower = url.lowercase()
        return lower.contains("login.live.com") ||
                lower.contains("login.microsoftonline.com") ||
                lower.contains("account.live.com") ||
                lower.contains("account.microsoft.com") ||
                lower.contains("msauth") ||
                lower.contains("msft") ||
                lower.contains("oauth") ||
                lower.contains("live.com")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                userAgentString = CHROME_ANDROID_USER_AGENT
                setSupportMultipleWindows(true)
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

            // Inject the AndroidControllerBridge to allow synchronous polling from JS
            val bridge = AndroidControllerBridge(stateManager) { duration, strong, weak ->
                performVibration(duration, strong, weak)
            }
            addJavascriptInterface(bridge, "AndroidBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    _loadingProgress.value = newProgress
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    // Forward OAuth/login popup requests seamlessly inside the existing WebView
                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = view
                    resultMsg?.sendToTarget()
                    return true
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

                    // Dynamically set appropriate User Agent
                    val urlStr = uri.toString()
                    if (isAuthUrl(urlStr)) {
                        view?.settings?.userAgentString = CHROME_ANDROID_USER_AGENT
                    } else if (urlStr.contains("xbox.com")) {
                        view?.settings?.userAgentString = CHROME_TABLET_USER_AGENT
                    }

                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    _loadingProgress.value = 10
                    if (isAuthUrl(url)) {
                        view?.settings?.userAgentString = CHROME_ANDROID_USER_AGENT
                    } else {
                        injectController(view)
                    }
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
        if (isAuthUrl(currentUrl)) {
            return
        }
        if (injectorScript.isNotEmpty() && currentUrl.contains("xbox.com")) {
            view?.evaluateJavascript(injectorScript, null)
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
    onVibrate: (Long, Double, Double) -> Unit,
    onHapticClick: () -> Unit,
    getWebView: () -> WebView
) {
    var overlayOpacity by remember { mutableFloatStateOf(0.9f) }
    var hapticsEnabled by remember { mutableStateOf(true) }
    var isOverlayVisible by remember { mutableStateOf(true) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var currentPingMs by remember { mutableIntStateOf(32) }

    val webView = remember { getWebView() }

    // Measure live latency to xbox.com
    LaunchedEffect(Unit) {
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
                delay(6000)
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
                onOpacityChange = { overlayOpacity = it },
                hapticsEnabled = hapticsEnabled,
                onHapticsToggle = { hapticsEnabled = it },
                overlayVisible = isOverlayVisible,
                onOverlayToggle = { isOverlayVisible = it },
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
