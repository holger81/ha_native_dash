package dev.holgerendt.hanative.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.holgerendt.hanative.ui.theme.ChipOnDark
import org.json.JSONObject

/** Seconds until expiry passed to HA frontend for a long-lived access token (~10 years). */
private const val LONG_LIVED_EXPIRES_IN = 315_360_000

private const val AUTH_CALLBACK_SET = "externalAuthSetToken"
private const val AUTH_CALLBACK_REVOKE = "externalAuthRevokeToken"
private const val LOAD_URL_TAG_KEY = 0x48415756 // "HAWV"
private const val WEBVIEW_LOG_TAG = "HaNativeWebView"
private const val CONTENT_CHECK_DELAY_MS = 4_000L

private const val INGRESS_DEFAULT_HASH = "#/home"

private const val INGRESS_POLYFILL_JS = """
(function() {
  if (typeof MediaMetadata === 'undefined') {
    window.MediaMetadata = function(init) {
      init = init || {};
      this.title = init.title || '';
      this.artist = init.artist || '';
      this.album = init.album || '';
      this.artwork = init.artwork || [];
    };
  }
  if (!navigator.mediaSession) {
    navigator.mediaSession = {
      metadata: null,
      playbackState: 'none',
      setActionHandler: function() {},
      setPositionState: function() {},
    };
  } else {
    if (!navigator.mediaSession.setActionHandler) navigator.mediaSession.setActionHandler = function() {};
    if (!navigator.mediaSession.setPositionState) navigator.mediaSession.setPositionState = function() {};
  }
})();
"""

private const val INGRESS_LAYOUT_FIX_JS = """
(function() {
  var meta = document.querySelector('meta[name=viewport]');
  if (!meta) {
    meta = document.createElement('meta');
    meta.name = 'viewport';
    meta.content = 'width=device-width, initial-scale=1, viewport-fit=cover';
    document.head.appendChild(meta);
  }
  var style = document.getElementById('ha-native-ingress-fix');
  if (!style) {
    style = document.createElement('style');
    style.id = 'ha-native-ingress-fix';
    document.head.appendChild(style);
  }
  style.textContent = 'html,body,#app{height:100%!important;min-height:100%!important;margin:0;padding:0;overflow:hidden!important}.v-application,.v-application__wrap{min-height:100%!important;height:100%!important;display:flex!important;flex-direction:column!important}main,.v-main,.v-main__wrap{flex:1 1 auto!important;min-height:0!important;overflow:auto!important;display:flex!important;flex-direction:column!important;height:auto!important}.v-main__wrap>.v-container,.v-main>.v-container,router-view,[data-v-app]{flex:1 1 auto!important;min-height:0!important}';
  if (!location.hash || location.hash === '#') {
    location.replace(location.pathname + location.search + '#/home');
  }
})();
"""

private const val CONTENT_READINESS_JS = """
(function() {
  function isReady(root) {
    if (!root) return false;
    var rect = root.getBoundingClientRect ? root.getBoundingClientRect() : { height: 0 };
    if (rect.height < 80) return false;
    var cards = root.querySelectorAll('img, button, a, [class*="card"], [class*="list"], [class*="grid"], [class*="item"], [class*="tile"]');
    if (cards.length >= 3) return true;
    var text = (root.innerText || '').replace(/\s+/g, ' ').trim();
    return text.length > 80;
  }

  var iframe = document.querySelector('iframe[src*="hassio_ingress"], iframe');
  if (iframe) {
    try {
      var doc = iframe.contentDocument || iframe.contentWindow.document;
      if (isReady(doc.querySelector('main') || doc.body)) return true;
    } catch (e) {}
    if (iframe.clientHeight > 120) return true;
  }

  var panel = document.querySelector('music-assistant-app, ha-panel-app, [data-panel-url*="music_assistant"]');
  if (panel && panel.clientHeight > 120) return true;

  var main = document.querySelector('.v-main__wrap') || document.querySelector('.v-main') || document.querySelector('main');
  if (main) {
    var view = main.querySelector('.v-container, [class*="home"], [class*="router"] > *');
    if (view && view.getBoundingClientRect().height > 100 && isReady(view)) return true;
    if (isReady(main)) return true;
  }

  return false;
})();
"""

/**
 * Embeds a Home Assistant frontend route in a WebView using the external auth bridge
 * (`?external_auth=1` + `window.externalApp`), or a Supervisor ingress URL with
 * `ingress_session` cookie auth.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HaAuthenticatedWebView(
    baseUrl: String,
    token: String,
    path: String,
    modifier: Modifier = Modifier,
    useExternalAuth: Boolean = true,
    ingressSession: String? = null,
    bootstrapNavigatePath: String? = null,
    debugInfo: List<String> = emptyList(),
    onLoadError: ((String) -> Unit)? = null,
    onContentBlank: ((String) -> Unit)? = null,
    onPageLoaded: (() -> Unit)? = null,
) {
    val needsToken = useExternalAuth
    if (baseUrl.isBlank() || (needsToken && token.isBlank())) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Connect Home Assistant in Settings to open this panel.",
                color = ChipOnDark,
                fontSize = 14.sp,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    val normalizedPath = remember(path) { normalizeHaPath(path) }
    val pageUrl = remember(baseUrl, normalizedPath, useExternalAuth, ingressSession) {
        if (useExternalAuth) {
            haFrontendUrl(baseUrl, normalizedPath)
        } else if (!ingressSession.isNullOrBlank()) {
            ingressLoadUrl(baseUrl, normalizedPath)
        } else {
            haAbsoluteUrl(baseUrl, normalizedPath)
        }
    }
    val ingressMode = !useExternalAuth && !ingressSession.isNullOrBlank()
    var loadError by remember(pageUrl, ingressSession) { mutableStateOf<String?>(null) }
    val contentCheckHandler = remember { Handler(Looper.getMainLooper()) }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    configureForHaPanel(ingressMode)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    if (useExternalAuth) {
                        addJavascriptInterface(
                            HaExternalAuthBridge(this, token.trim()),
                            "externalApp",
                        )
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val message = consoleMessage?.message()?.trim().orEmpty()
                            if (message.isNotEmpty()) {
                                Log.d(
                                    WEBVIEW_LOG_TAG,
                                    "${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()} $message",
                                )
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        private var didBootstrapNavigate = false
                        private var contentCheckToken = 0
                        private var reportedBlankContent = false

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loadError = null
                            reportedBlankContent = false
                            contentCheckToken++
                            if (ingressMode) {
                                view?.evaluateJavascript(INGRESS_POLYFILL_JS, null)
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.requestFocus(View.FOCUS_DOWN)
                            onPageLoaded?.invoke()

                            if (ingressMode) {
                                view?.evaluateJavascript(INGRESS_LAYOUT_FIX_JS, null)
                                contentCheckHandler.postDelayed({
                                    view?.evaluateJavascript(INGRESS_LAYOUT_FIX_JS, null)
                                }, 600)
                                contentCheckHandler.postDelayed({
                                    view?.evaluateJavascript(INGRESS_LAYOUT_FIX_JS, null)
                                }, 2_000)
                            }

                            val navigatePath = bootstrapNavigatePath?.trim()?.takeIf { it.isNotEmpty() }
                            if (navigatePath != null && !didBootstrapNavigate && !url.isNullOrBlank()) {
                                val normalizedNavigatePath =
                                    if (navigatePath.startsWith("/")) navigatePath else "/$navigatePath"
                                if (!url.contains(normalizedNavigatePath.trimStart('/'))) {
                                    didBootstrapNavigate = true
                                    view?.loadUrl(haFrontendUrl(baseUrl, normalizedNavigatePath))
                                    return
                                }
                            }

                            scheduleContentReadinessCheck(view, ++contentCheckToken)
                        }

                        private fun scheduleContentReadinessCheck(view: WebView?, token: Int) {
                            if (onContentBlank == null || view == null) return
                            contentCheckHandler.postDelayed({
                                if (token != contentCheckToken || reportedBlankContent) return@postDelayed
                                view.evaluateJavascript(CONTENT_READINESS_JS) { result ->
                                    if (token != contentCheckToken || reportedBlankContent) return@evaluateJavascript
                                    if (result == "true") return@evaluateJavascript
                                    reportedBlankContent = true
                                    onContentBlank.invoke("Panel content did not render")
                                }
                            }, CONTENT_CHECK_DELAY_MS)
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            if (request?.isForMainFrame != true) return
                            val status = errorResponse?.statusCode ?: return
                            if (status < 400) return
                            val message = "HTTP $status"
                            loadError = message
                            onLoadError?.invoke(message)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame != true) return
                            val message = error?.description?.toString()?.takeIf { it.isNotBlank() }
                                ?: "Could not load panel"
                            loadError = message
                            onLoadError?.invoke(message)
                        }
                    }
                    applyConfiguredLoad(baseUrl, pageUrl, ingressSession)
                }
            },
            update = { view ->
                val configured = view.getTag(LOAD_URL_TAG_KEY) as? String
                if (configured != pageUrl) {
                    view.applyConfiguredLoad(baseUrl, pageUrl, ingressSession)
                }
            },
            onRelease = { view ->
                contentCheckHandler.removeCallbacksAndMessages(null)
                view.destroy()
            },
            modifier = Modifier.fillMaxSize(),
        )
        loadError?.let { message ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                val debugBlock = buildString {
                    append("\n\nURL:\n$pageUrl")
                    if (debugInfo.isNotEmpty()) {
                        append("\n\nDetected:\n${debugInfo.joinToString("\n")}")
                    }
                }
                Text(
                    "$message$debugBlock\n\nOpen this panel in the Home Assistant app if it keeps failing.",
                    color = ChipOnDark,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForHaPanel(ingressMode: Boolean) {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        mediaPlaybackRequiresUserGesture = false
        loadWithOverviewMode = true
        useWideViewPort = true
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        allowContentAccess = true
        cacheMode = WebSettings.LOAD_DEFAULT
        if (ingressMode) {
            javaScriptCanOpenWindowsAutomatically = true
        }
    }
    isFocusable = true
    isFocusableInTouchMode = true
    overScrollMode = View.OVER_SCROLL_NEVER
    setLayerType(View.LAYER_TYPE_HARDWARE, null)
}

private fun WebView.applyConfiguredLoad(baseUrl: String, pageUrl: String, ingressSession: String?) {
    if (!ingressSession.isNullOrBlank()) {
        applyIngressSessionCookie(baseUrl, ingressSession)
    }
    setTag(LOAD_URL_TAG_KEY, pageUrl)
    loadUrl(pageUrl)
}

private fun applyIngressSessionCookie(baseUrl: String, ingressSession: String) {
    val cookieBase = baseUrl.trim().trimEnd('/')
    val secure = cookieBase.startsWith("https://", ignoreCase = true)
    val cookie = buildString {
        append("ingress_session=")
        append(ingressSession.trim())
        append("; Path=/api/hassio_ingress/; SameSite=Strict")
        if (secure) append("; Secure")
    }
    val manager = CookieManager.getInstance()
    manager.setCookie(cookieBase, "ingress_session=; Path=/api/hassio_ingress/; Max-Age=0")
    manager.setCookie(cookieBase, cookie)
    manager.flush()
}

fun haFrontendUrl(baseUrl: String, path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val normalizedPath = normalizeHaPath(path)
    val withQuery = if (normalizedPath.contains('?')) {
        "$normalizedPath&external_auth=1"
    } else {
        "$normalizedPath?external_auth=1"
    }
    return if (normalizedPath.startsWith("http")) {
        withQuery
    } else {
        "$base$withQuery"
    }
}

fun haAbsoluteUrl(baseUrl: String, path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val normalizedPath = normalizeIngressPath(normalizeHaPath(path))
    return if (normalizedPath.startsWith("http")) {
        normalizedPath
    } else {
        "$base$normalizedPath"
    }
}

private fun normalizeHaPath(path: String): String = when {
    path.startsWith("http://") || path.startsWith("https://") -> path
    path.startsWith("/") -> path
    else -> "/$path"
}

/** Ingress addon assets resolve relative to the ingress base; keep a trailing slash. */
fun normalizeIngressPath(path: String): String {
    val hashIndex = path.indexOf('#')
    val pathOnly = if (hashIndex >= 0) path.substring(0, hashIndex) else path
    val hash = if (hashIndex >= 0) path.substring(hashIndex) else ""

    if (pathOnly.startsWith("http://") || pathOnly.startsWith("https://")) {
        val withoutQuery = pathOnly.substringBefore('?')
        val query = pathOnly.substringAfter('?', missingDelimiterValue = "")
        val normalized = if (withoutQuery.endsWith("/")) withoutQuery else "$withoutQuery/"
        val withQuery = if (query.isEmpty()) normalized else "$normalized?$query"
        return withQuery + hash
    }
    if (!pathOnly.contains("/api/hassio_ingress/")) return path
    val pathPart = pathOnly.substringBefore('?')
    val query = pathOnly.substringAfter('?', missingDelimiterValue = "")
    val normalized = if (pathPart.endsWith("/")) pathPart else "$pathPart/"
    val withQuery = if (query.isEmpty()) normalized else "$normalized?$query"
    return withQuery + hash
}

/** Supervisor ingress for Music Assistant needs a Vue hash route; default to home. */
fun ingressLoadUrl(baseUrl: String, ingressPath: String): String {
    val normalized = normalizeIngressPath(ingressPath)
    val withHash = if ('#' in normalized) normalized else "$normalized$INGRESS_DEFAULT_HASH"
    return haAbsoluteUrl(baseUrl, withHash)
}

private class HaExternalAuthBridge(
    private val webView: WebView,
    private val accessToken: String,
) {
    @JavascriptInterface
    fun getExternalAuth(payload: String) {
        val callback = runCatching {
            JSONObject(payload).getString("callback")
        }.getOrNull() ?: return
        if (callback != AUTH_CALLBACK_SET) return
        deliverAuth(callback, success = true)
    }

    @JavascriptInterface
    fun revokeExternalAuth(payload: String) {
        val callback = runCatching {
            JSONObject(payload).getString("callback")
        }.getOrNull() ?: return
        if (callback != AUTH_CALLBACK_REVOKE) return
        webView.post {
            webView.evaluateJavascript("window.$AUTH_CALLBACK_REVOKE(true);", null)
        }
    }

    private fun deliverAuth(callback: String, success: Boolean) {
        webView.post {
            if (!success) {
                webView.evaluateJavascript("window.$callback(false);", null)
                return@post
            }
            val tokenJson = JSONObject()
                .put("access_token", accessToken)
                .put("expires_in", LONG_LIVED_EXPIRES_IN)
            webView.evaluateJavascript("window.$callback(true, $tokenJson);", null)
        }
    }
}
