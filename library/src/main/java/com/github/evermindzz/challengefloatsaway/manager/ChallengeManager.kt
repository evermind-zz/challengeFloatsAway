package com.github.evermindzz.challengefloatsaway.manager

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.github.evermindzz.challengefloatsaway.ChallengeEvents.EventCloudflareChallengeRequest
import com.github.evermindzz.challengefloatsaway.ChallengeEvents.EventCloudflareChallengeResponse
import com.github.evermindzz.challengefloatsaway.ChallengeResult
import com.github.evermindzz.challengefloatsaway.ChallengeWebViewHandler
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

/**
 * Wrap around BypassClient to tell cloudflare, that a human is using the app.
 *
 * Non-service version might not work in all cases, as there is no real screen for the webView
 *
 * @Deprecated
 */
@SuppressLint("StaticFieldLeak")
class ChallengeManager(
    private val applicationContext: Context,
) :
    EventCloudflareChallengeResponse.Handler,
    ChallengeManagerInterface {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var handler: ChallengeWebViewHandler? = null

    @Volatile
    private var challengeEventResultLatch: CountDownLatch? = null
    private var eventResult: EventCloudflareChallengeResponse? = null
    private lateinit var webView: WebView

    @Volatile
    private var currentCookies = ""

    init {
        EventBus.getDefault().register(this)

        if (Looper.myLooper() != Looper.getMainLooper()) {
            val initLatch = CountDownLatch(1)
            mainHandler.post {
                createWebView(applicationContext)
                initLatch.countDown()
            }
            try {
                initLatch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } else {
            createWebView(applicationContext)
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    override fun handleEventCloudflareChallengeResponse(
        event: EventCloudflareChallengeResponse
    ) {
        eventResult = event
        if (challengeEventResultLatch != null) {
            challengeEventResultLatch!!.countDown()
        } else {
            throw RuntimeException(
                "challengeEventResultLatch == null -> that should never happen"
            )
        }
    }

    private fun createWebView(context: Context) {
        webView = WebView(context)
        webView.layoutParams = ViewGroup.LayoutParams(1, 1)
        webView.visibility = View.GONE

        handler = ChallengeWebViewHandler(context, webView)
    }

    @Synchronized
    override fun fetchContentViaWebView(
        url: String,
        timeoutMs: Long
    ): ChallengeResult {
        challengeEventResultLatch = CountDownLatch(1)
        val currentRequestId = System.currentTimeMillis()

        EventBus.getDefault().post(
            EventCloudflareChallengeRequest(
                currentRequestId,
                url,
                timeoutMs
            )
        )

        try {
            challengeEventResultLatch!!.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt() // Status erhalten
            return ChallengeResult(false, null, "")
        }

        if (eventResult != null) {
            if (eventResult!!.requestId != currentRequestId) {
                Log.e("CF_DBG", "eventResult.requestId is not what we expect")
                return ChallengeResult(false, null, "")
            }
            return ChallengeResult(
                eventResult!!.result.success,
                eventResult!!.result.content,
                eventResult!!.result.cookies
            )
        } else {
            return ChallengeResult(false, null, "")
        }
    }

    override fun getCurrentCookies(): String {
        return currentCookies
    }

    override fun destroy() {
        EventBus.getDefault().unregister(this)
        handler!!.destroy()
        mainHandler.post {
            webView.destroy()
        }
    }
}
