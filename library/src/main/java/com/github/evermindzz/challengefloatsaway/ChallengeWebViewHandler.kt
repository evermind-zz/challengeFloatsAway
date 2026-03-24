package com.github.evermindzz.challengefloatsaway

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.CookieSyncManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ead.lib.cloudflare_bypass.BypassClient
import com.github.evermindzz.challengefloatsaway.ChallengeEvents.EventCloudflareChallengeRequest
import com.github.evermindzz.challengefloatsaway.ChallengeEvents.EventCloudflareChallengeResponse
import com.github.evermindzz.challengefloatsaway.misc.ChallengeValidateJavaScriptValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.Volatile

class ChallengeWebViewHandler(
    private val applicationContext: Context,
    private val webView: WebView,
) {
    private val jsonMisc: ChallengeValidateJavaScriptValue =
        ChallengeValidateJavaScriptValue()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startLoadingUrlTime = 0L

    private lateinit var userAgent: String
    private lateinit var cookieDomains: Array<String>

    private var reloadAttempts = 0

    companion object {
        val TAG: String = ChallengeWebViewHandler::class.java.simpleName
        private const val COOKIES_PREF_FILE = "cf_cookies"
        private const val COOKIES_PREF_KEY = "webViewCookies"
        private const val MAX_RELOAD_ATTEMPTS = 3
    }

    @Volatile
    private var currentCookies = ""

    private data class FetchParameters(
        val requestedUrl: String,
        var requestId: Long,
        val timeoutMs: Long
    )

    private lateinit var currentFetchParameters: FetchParameters

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val requestChannel =
        Channel<EventCloudflareChallengeRequest>(Channel.UNLIMITED)

    // deferred object for ongoing request
    private var currentResult: CompletableDeferred<ChallengeResult>? = null

    /** determine which caller calls [evaluateViaJavaScript] */
    enum class EvalSource { PROGRESS_CHANGED, PAGE_FINISHED }
    data class EvalType(
        val url: String?,
        val source: EvalSource,
        val hasRealContent: Boolean? = null
    )

    private val evaluationChannel = Channel<EvalType>(Channel.CONFLATED)

    init {
        // fetch worker
        serviceScope.launch {
            for (request in requestChannel) {
                val result = suspendFetchContentViaWebView(
                    request.requestId,
                    request.url,
                    request.timeoutMs
                )
                EventBus.getDefault()
                    .post(EventCloudflareChallengeResponse(request.requestId, result))
            }
        }

        // evaluator worker
        serviceScope.launch(Dispatchers.Main) {
            for (source in evaluationChannel) {
                Log.d(TAG, "serviceScope: Evaluating JS triggered by: ${source.source}")
                evaluateViaJavaScript(source.url, source, currentFetchParameters)
            }
        }

        serviceScope.launch {
            ChallengeSettings.config
                .map { it.userAgent }
                .distinctUntilChanged()
                .collect { agent ->
                    userAgent = agent
                    withContext(Dispatchers.Main) {
                        setWebViewUserAgent()
                    }
                }
        }

        serviceScope.launch {
            ChallengeSettings.config
                .map { it.cookieDomains }
                .distinctUntilChanged()
                .collect { cookies ->
                    cookieDomains = cookies
                }
        }
        mainHandler.post {
            setupWebView()
            setupWebViewClient()
            setupWebChromeClient()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            // loadsImagesAutomatically = false
            // blockNetworkImage = true

            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setWebViewUserAgent()
        }

        val versionInfo = getDetailedWebViewVersion(applicationContext)
        Log.d(TAG, "setupWebView(): WebView Info – $versionInfo")
    }

    private fun setWebViewUserAgent() {
        if (userAgent.isNotBlank()) {
            webView.settings.userAgentString = userAgent
        }
    }

    private fun setupWebViewClient() {
        webView.webViewClient = object : BypassClient() {
            override fun onPageFinishedByPassed(
                view: WebView?,
                url: String?,
                isCloudflareChallenge: Boolean
            ) {
                super.onPageFinishedByPassed(view, url, isCloudflareChallenge)

                if (!isCloudflareChallenge) {
                    Log.d(
                        TAG,
                        "onPageFinishedByPassed(): loadedUrl=$url requestedUrl=${currentFetchParameters.requestedUrl}"
                    )
                    reloadAttempts = 0
                    view?.let {
                        saveAllCookies(view.context, cookieDomains)
                    }
                    evaluationChannel.trySend(EvalType(url, EvalSource.PAGE_FINISHED, true))
                } else if (reloadAttempts < MAX_RELOAD_ATTEMPTS) {
                    reloadAttempts++

                    val randomDelay = (800..1100).random()

                    view?.postDelayed({
                        Log.d(
                            TAG,
                            "onPageFinishedByPassed(): Reload after Challenge (retry $reloadAttempts) with delay $randomDelay ms"
                        )
                        view.loadUrl(currentFetchParameters.requestedUrl)
                    }, randomDelay.toLong())
                } else {
                    reloadAttempts = 0
                    Log.e(
                        TAG,
                        "Cloudflare Bypass did not work after $MAX_RELOAD_ATTEMPTS retries"
                    )
                    evaluationChannel.trySend(EvalType(url, EvalSource.PAGE_FINISHED, false))
                }
            }

            /**
             * We use the deprecated version as we want only main page errors.
             *
             * @param view The WebView that is initiating the callback.
             * @param errorCode The error code corresponding to an ERROR_* value.
             * @param description A String describing the error.
             * @param failingUrl The url that failed to load.
             */
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.d(
                    TAG,
                    (
                            "onReceivedError(): " +
                                    "view=" + view +
                                    "errorCode=" + errorCode +
                                    "description=" + description +
                                    "failingUrl=" + failingUrl
                            )
                )
            }
        }
    }

    private fun setupWebChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg = String.format(
                    Locale.ROOT,
                    "onConsoleMessage() WebViewConsoleLog: [%s:%d] %s",
                    consoleMessage.sourceId(),
                    consoleMessage.lineNumber(),
                    consoleMessage.message()
                )
                Log.d(TAG, msg)
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val loadedUrl: String = view!!.url.toString()
                val currentTime = System.currentTimeMillis()
                if (startLoadingUrlTime == 0L) {
                    startLoadingUrlTime = currentTime
                }

                Log.d(
                    TAG,
                    "onProgressChanged(): $newProgress% timeMsSinceStartLoadingUrl=${currentTime - startLoadingUrlTime} loadedUrl=$loadedUrl"
                )
                // 2/3 of the overall timeout
                // val timeout = (currentFetchParameters.timeoutMs * (2 / 3f)).toLong()
                val timeout = (currentFetchParameters.timeoutMs * (0.25f)).toLong()
                if (newProgress >= 10 && currentTime - startLoadingUrlTime > timeout) {
                    evaluationChannel.trySend(EvalType(loadedUrl, EvalSource.PROGRESS_CHANGED))
                }
            }
        }
    }

    /**
     * Non-Blocking method to fetch the [WebView]s content
     */
    fun fetchContentAsync(event: EventCloudflareChallengeRequest) {
        requestChannel.trySend(event)
    }

    /**
     * Blocking method to fetch the [WebView]'s content
     *
     * Prefer non-blocking [fetchContentAsync] method.
     */
    @Synchronized
    fun fetchContentBlocking(
        requestId: Long,
        url: String,
        timeoutMs: Long
    ): ChallengeResult {
        val result: ChallengeResult
        runBlocking {
            result = suspendFetchContentViaWebView(requestId, url, timeoutMs)
        }

        EventBus.getDefault().post(EventCloudflareChallengeResponse(requestId, result))

        return result
    }

    private suspend fun suspendFetchContentViaWebView(
        requestId: Long,
        url: String,
        timeoutMs: Long
    ): ChallengeResult {
        val deferred = CompletableDeferred<ChallengeResult>()
        currentResult = deferred

        // setup current FetchParameters here
        currentFetchParameters =
            FetchParameters(url, requestId, timeoutMs)

        withContext(Dispatchers.Main) {
            startLoadingUrlTime = 0L // reset for onProgressChanged()
            webView.stopLoading()
            restoreAllCookies(webView.context)
            webView.loadUrl(url)
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (ignored: Exception) {
            ChallengeResult(false, null, updateAndGetCookies())
        }
    }

    /**
     * script to extract either from document.{body,documentElement.outerHTML}.
     *
     * - This script provides the data we want without custom unescape java.
     * - After execution the 'value' is a json object which with this keys
     *   isJson, error, dataContent
     *   - isJson: dataContent is a json object
     *   - error: contains that an error occurred
     *   - dataContent: the actual page content (content of original url)
     *   - reqId: the requestId just to mark the result data
     *
     *   @param requestId just an identifier for data integrity the caller need's to handle that
     *   @return script suited to your requestId
     */
    private fun getJsTemplate(requestId: Long) = """
        (function() {
            try {
                var r = { isJson: false, dataContent: '', reqId: $requestId };
                var b = document.body;
                if (!b) return JSON.stringify({ error: 'no_body', reqId: $requestId });
                var c = b ? b.textContent : null;
                try {
                    if (!c) throw 'no_text';
                    JSON.parse(c);
                    r.isJson = true;
                    r.dataContent = c;
                } catch (e) {
                    r.dataContent = document.documentElement.outerHTML;
                }
                return JSON.stringify(r);
            } catch (err) {
                console.error(err.stack);
                return JSON.stringify({ error: err.toString(), reqId: $requestId });
            }
        })();
    """.trimIndent()

    // bridge: turn async callback into a suspend function
    private suspend fun WebView.evaluateJavascriptSuspend(javaScript: String): String? =
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript(javaScript) { value ->
                // block called when javascript evaluation is done
                if (continuation.isActive) continuation.resume(value) {}
            }
        }

    /**
     * extract the whole content of a url via javascript evaluation.
     */
    private suspend fun evaluateViaJavaScript(
        loadedUrl: String?,
        evalType: EvalType,
        currentFetchParameters: FetchParameters
    ) {
        val deferred = currentResult ?: return
        if (deferred.isCompleted) {
            Log.d(TAG, "evaluateViaJavaScript(): deferred already complete")
            return
        }

        val evalSource = evalType.source
        val requestId = currentFetchParameters.requestId
        val requestedUrl = currentFetchParameters.requestedUrl

        evalType.hasRealContent?.let { hasRealContent ->
            if (!hasRealContent && evalSource == EvalSource.PAGE_FINISHED) {
                // if onPageFinished was the caller, but we still have no real page with content
                deferred.complete(ChallengeResult(false, null, null))
                return
            }
        }

        Log.d(
            TAG,
            "evaluateViaJavaScript(): run JS evaluation: requestId=$requestId loadedUrl=$loadedUrl requestedUrl=$requestedUrl"
        )

        val finalScript = getJsTemplate(requestId)
        val value = webView.evaluateJavascriptSuspend(finalScript)

        val result = withContext(Dispatchers.Default) {
            val challengeResult = ChallengeResult(false, null, null)
            if (!value.isNullOrEmpty() && value != "null") {
                jsonMisc.validateJSValue(value, evalSource, challengeResult)
            }

            challengeResult.cookies = updateAndGetCookies()
            Log.d(
                TAG,
                "evaluateViaJavaScript(): JS evaluation: success=${challengeResult.success}  requestId=$requestId loadedUrl=$loadedUrl requestedUrl=$requestedUrl"
            )

            return@withContext challengeResult
        }

        if (result.success) {
            webView.stopLoading()
            deferred.complete(result)
        } else if (evalSource == EvalSource.PAGE_FINISHED) {
            // if onPageFinished was the caller, but we still had no success
            deferred.complete(result)
        }
    }

    private fun updateAndGetCookies(): String {
        var selectedCookies: String? = null

        for (domain in cookieDomains) {
            val cookies = CookieManager.getInstance().getCookie(domain)
            if (cookies != null && !cookies.isEmpty()) {
                if (cookies.contains("__cf")) {
                    selectedCookies = cookies
                    break // cancel we found CF-Cookie
                }
                if (selectedCookies == null) {
                    selectedCookies = cookies // fallback: first none empty cookie
                }
            }
        }

        currentCookies = if (selectedCookies != null) selectedCookies else ""
        return currentCookies
    }

    private fun dumpCookiesForKnownDomains() {
        val manager = CookieManager.getInstance()
        flushCookieManager(manager)

        for (domain in cookieDomains) {
            val cookies = manager.getCookie(domain)
            if (cookies != null && !cookies.isEmpty()) {
                Log.d(TAG, "dumpCookiesForKnownDomains(): $domain => $cookies")
            }
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun flushCookieManager(manager: CookieManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            manager.flush()
        } else {
            @Suppress("deprecation")
            val cookieSyncManager =
                CookieSyncManager.createInstance(applicationContext)
            cookieSyncManager.sync()
        }
    }

    private fun getWebViewPackageInfo(context: Context): String {
        val pm = context.packageManager
        try {
            val info = pm.getPackageInfo("com.google.android.webview", 0)
            // or "com.android.webview" on some devices
            return info.packageName + " v" + info.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            try {
                val info =
                    pm.getPackageInfo("com.android.webview", 0)
                return info.packageName + " v" + info.versionName
            } catch (ex: PackageManager.NameNotFoundException) {
                return "System WebView (version unknown)"
            }
        }
    }

    // Tries to get detailed Chromium version via reflection (works on many devices)
    private fun getDetailedWebViewVersion(context: Context): String {
        try {
            // Try to get Chromium version via reflection (common field)
            @SuppressLint("PrivateApi")
            val webViewFactory =
                Class.forName("android.webkit.WebViewFactory")
            val getProvider = webViewFactory.getMethod("getProvider")
            val provider = getProvider.invoke(null)
            val providerClass: Class<*> = provider!!.javaClass
            val getVersion = providerClass.getMethod("getVersion")
            val chromiumVersion = getVersion.invoke(provider) as String?

            return "Package: ${getWebViewPackageInfo(context)} | Chromium: $chromiumVersion"
        } catch (e: Exception) {
            return "Package: ${getWebViewPackageInfo(context)} (Chromium version unknown)"
        }
    }

    private fun stopFetching() {
        mainHandler.post {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
        }
    }

    fun destroy() {
        requestChannel.close()
        evaluationChannel.close()
        stopFetching()
    }

    fun saveAllCookies(context: Context, domains: Array<String>) {
        val cookieManager = CookieManager.getInstance()
        val cookieMap = JSONObject()

        try {
            for (domain in domains) {
                val cookies = cookieManager.getCookie(domain)
                if (!cookies.isNullOrEmpty()) {
                    cookieMap.put(domain, cookies)
                    Log.d(TAG, "Collected cookies for $domain cookies: $cookies")
                }
            }

            if (cookieMap.length() == 0) {
                Log.w(TAG, "No cookies found to save")
                return
            }

            val jsonString = cookieMap.toString()

            val prefs = context.getSharedPreferences(COOKIES_PREF_FILE, Context.MODE_PRIVATE)
            prefs.edit().putString(COOKIES_PREF_KEY, jsonString).apply()

            flushCookieManager(cookieManager)

            Log.d(
                TAG,
                "All cookies saved successfully. Domains: ${cookieMap.keys().asSequence().toList()}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cookies", e)
        }
    }

    fun restoreAllCookies(context: Context) {
        val prefs = context.getSharedPreferences(COOKIES_PREF_FILE, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(COOKIES_PREF_KEY, null) ?: return

        try {
            val cookieMap = JSONObject(jsonString)
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val keys = cookieMap.keys()
            while (keys.hasNext()) {
                val domain = keys.next() as String
                val cookiesString = cookieMap.getString(domain)

                if (cookiesString.isNotEmpty()) {
                    cookiesString.split(";").forEach { cookie ->
                        val trimmed = cookie.trim()
                        if (trimmed.isNotEmpty()) {
                            cookieManager.setCookie(domain, trimmed)
                            Log.d(TAG, "Restored cookies for domain: $domain Cookie: $trimmed")
                        }
                    }
                    Log.d(TAG, "Restored cookies for domain: $domain")
                }
            }

            flushCookieManager(cookieManager)
            Log.d(TAG, "All cookies restored from storage")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore cookies", e)
        }
    }
}
