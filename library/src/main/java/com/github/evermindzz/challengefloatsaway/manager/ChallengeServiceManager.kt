package com.github.evermindzz.challengefloatsaway.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.github.evermindzz.challengefloatsaway.ChallengeEvents.EventCloudflareChallengeRequest
import com.github.evermindzz.challengefloatsaway.ChallengeEvents.EventCloudflareChallengeResponse
import com.github.evermindzz.challengefloatsaway.ChallengeEvents.EventCloudflareServiceReady
import com.github.evermindzz.challengefloatsaway.ChallengeEvents.EventServiceActions
import com.github.evermindzz.challengefloatsaway.ChallengeResult
import com.github.evermindzz.challengefloatsaway.ChallengeSettings
import com.github.evermindzz.challengefloatsaway.perms.PermissionActivity
import com.github.evermindzz.challengefloatsaway.perms.PermsHelper
import com.github.evermindzz.challengefloatsaway.perms.PermsHelper.OverlayPermissionResult
import com.github.evermindzz.challengefloatsaway.service.FloatingWebViewService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile

/**
 * Wrap around BypassClient to tell cloudflare, that a human is using the app.
 */
class ChallengeServiceManager(
    private val applicationContext: Context,
) :
    ChallengeManagerInterface,
    EventCloudflareChallengeResponse.Handler,
    EventCloudflareServiceReady.Handler,
    PermsHelper.EventOverlayPermissionResult.Handler {

    var isInteractive: Boolean = false
    private var serviceIntent: Intent

    @Volatile
    private var eventStartCloudflareServiceLatch: CountDownLatch? = null

    @Volatile
    private var eventCloudflareChallengeResponseLatch: CountDownLatch? = null

    @Volatile
    private var eventOverlayPermissionLatch: CountDownLatch? = null
    private var eventResult: EventCloudflareChallengeResponse? = null
    private var overlayPermissionResult: OverlayPermissionResult? = OverlayPermissionResult.Denied

    @Volatile
    private var currentCookies = ""

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    override fun handleEventCloudflareChallengeResponse(
        event: EventCloudflareChallengeResponse
    ) {
        eventResult = event
        Log.d(
            TAG,
            "handleEventCloudflareChallengeResponse() eventCloudflareChallengeResponseLatch=$eventCloudflareChallengeResponseLatch result.success=${event.result.success} result.requestId=${event.requestId}"
        )
        if (eventCloudflareChallengeResponseLatch != null) {
            eventCloudflareChallengeResponseLatch!!.countDown()
        } else {
            throw RuntimeException(
                "eventCloudflareChallengeResponseLatch == null -> that should never happen"
            )
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    override fun handleEventCloudflareServiceReady(
        event: EventCloudflareServiceReady
    ) {
        Log.d(
            TAG,
            "handleEventCloudflareServiceReady() eventStartCloudflareServiceLatch=$eventStartCloudflareServiceLatch"
        )
        if (eventStartCloudflareServiceLatch != null) {
            eventStartCloudflareServiceLatch!!.countDown()
        } else {
            throw RuntimeException(
                "eventStartCloudflareServiceLatch == null -> that should never happen"
            )
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    override fun handleEventOverlayPermissionResult(
        event: OverlayPermissionResult
    ) {
        if (eventOverlayPermissionLatch != null) {
            eventOverlayPermissionLatch!!.countDown()
            overlayPermissionResult = event
        } else {
            throw RuntimeException(
                "eventOverlayPermissionLatch == null -> that should never happen"
            )
        }
    }

    private fun canDrawOverlays(context: Context?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context)
        }
        return true
    }

    private fun checkPermsForStartingCloudflareChallengeService(): Boolean {
        var returnValue = false

        if (canDrawOverlays(applicationContext)) {
            returnValue = true
        } else {
            val permIntent = Intent(applicationContext, OverlayPermissionActivity::class.java)
            permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(permIntent)
            eventOverlayPermissionLatch = CountDownLatch(1)
            try {
                eventOverlayPermissionLatch!!.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.d(TAG, "checkPermsForStartingCloudflareChallengeService()", e)
            }

            when (overlayPermissionResult) {
                OverlayPermissionResult.Granted -> {
                    returnValue = true
                }

                OverlayPermissionResult.Denied -> {
                    Toast.makeText(
                        applicationContext,
                        "You did not grant overlay permission to the app, we cannot continue" +
                                " to start the needed window for the cloudflare challenge",
                        Toast.LENGTH_LONG
                    ).show()
                }

                else -> {
                    throw RuntimeException("unhandled case")
                }
            }
        }

        return returnValue
    }

    private fun startCloudflareChallengeService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(serviceIntent)
        } else {
            applicationContext.startService(serviceIntent)
        }
    }

    private val requestIdGenerator = AtomicInteger(0)

    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        serviceIntent = createServiceIntent()
        EventBus.getDefault().register(this)

        managerScope.launch {
            ChallengeSettings.config
                .map { it.isInteractive }
                .distinctUntilChanged()
                .collect { interactive ->
                    isInteractive = interactive
                }
        }
    }

    fun createServiceIntent(): Intent =
        Intent(applicationContext, FloatingWebViewService::class.java)

    @Synchronized
    override fun fetchContentViaWebView(
        url: String,
        timeoutMs: Long
    ): ChallengeResult {
        if (checkPermsForStartingCloudflareChallengeService()) {
            eventStartCloudflareServiceLatch = CountDownLatch(1)
            eventCloudflareChallengeResponseLatch = CountDownLatch(1)
            startCloudflareChallengeService()
        } else { // no permission given to launch the service
            return ChallengeResult(false, null, "")
        }
        eventResult = null

        try {
            val res = eventStartCloudflareServiceLatch!!
                .await(10000, TimeUnit.MILLISECONDS)
            Log.d(TAG, "fetchContentViaWebView() eventStartCloudflareServiceLatch res $res")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.d(TAG, "fetchContentViaWebView()", e)
            // TODO react here with some error
        }
        val currentRequestId = requestIdGenerator.getAndIncrement().toLong()
        Log.d(
            TAG,
            "fetchContentViaWebView() url=$url, send ChallengeRequest: currentRequestId=$currentRequestId"
        )
        EventBus.getDefault().post(
            EventCloudflareChallengeRequest(
                currentRequestId,
                url,
                timeoutMs
            )
        )

        EventBus.getDefault().post(
            EventServiceActions(
                EventServiceActions.Actions.HideError
            )
        )

        if (isInteractive) {
            EventBus.getDefault().post(
                EventServiceActions(
                    EventServiceActions.Actions.InteractiveOverlay
                )
            )
        }

        try {
            val res = eventCloudflareChallengeResponseLatch!!
                .await(timeoutMs, TimeUnit.MILLISECONDS)
            Log.d(TAG, "fetchContentViaWebView() eventCloudflareChallengeResponseLatch result $res")
            if (!res) {
                EventBus.getDefault().post(
                    EventServiceActions(
                        EventServiceActions.Actions.ShowError,
                        "no response for $timeoutMs ms"
                    )
                )
            }
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
            return ChallengeResult(false, null, "")
        }

        if (eventResult != null) {
            if (eventResult!!.requestId != currentRequestId) {
                Log.e(
                    TAG,
                    "fetchContentViaWebView() url=$url, Response:eventResult.requestId=${eventResult!!.requestId} is not what we expect: $currentRequestId"
                )
                return ChallengeResult(false, null, "")
            }
            if (eventResult!!.result.success) {
                EventBus.getDefault().post(
                    EventServiceActions(
                        EventServiceActions.Actions.MinimizeOverlay
                    )
                )
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
        EventBus.getDefault().post(
            EventServiceActions(
                EventServiceActions.Actions.ShutdownService
            )
        )
        EventBus.getDefault().unregister(this)
    }

    companion object {
        val TAG: String = ChallengeServiceManager::class.java.simpleName
    }
}
