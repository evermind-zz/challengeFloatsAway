package com.github.evermindzz.challengefloatsaway.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.Dimension
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.github.evermindzz.challengefloatsaway.ChallengeEvents
import com.github.evermindzz.challengefloatsaway.ChallengeWebViewHandler
import com.github.evermindzz.challengefloatsaway.R
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import kotlin.math.abs

/**
 * create an service that runs a webview inside a floating window.
 *
 * Key features:
 * - The floating window can be controlled by events. [ChallengeEvents.EventServiceActions]
 * - stops after idle time of [IDLE_TIMEOUT_MS]
 * - startup and webview access is handled by [com.github.evermindzz.challengefloatsaway.manager.ChallengeServiceManager]
 */
class FloatingWebViewService :
    Service(),
    ChallengeEvents.EventServiceActions.Handler,
    ChallengeEvents.EventCloudflareChallengeRequest.Handler {

    private val mainFloatingWindowPercentScreenSize = 0.60

    private var lastStartId: Int = -1
    private var closeZoneAdded = false
    private var webViewAdded = false
    private lateinit var windowManager: WindowManager

    private lateinit var webView: WebView
    private lateinit var mainFloatingWindowLayoutParams: WindowManager.LayoutParams

    private lateinit var mainFloatingRootLayout: LinearLayout
    private lateinit var mainFloatingButtonContainer: LinearLayout
    private lateinit var messageBox: TextView

    private lateinit var closeZoneView: View
    private lateinit var closeZoneParams: WindowManager.LayoutParams

    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var isDragging = false

    private val IDLE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

    private lateinit var webViewHandler: ChallengeWebViewHandler

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable {
        closeOverlay()
    }

    private fun resetIdleTimer() {
        mainHandler.removeCallbacks(idleRunnable)
        mainHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
    }

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK -> pauseWebView()

                TelephonyManager.CALL_STATE_IDLE -> resumeWebView()
            }
        }
    }

    private fun pauseWebView() {
        webView.onPause()
        webView.pauseTimers()
        mainHandler.removeCallbacks(idleRunnable)
    }

    private fun resumeWebView() {
        webView.onResume()
        webView.resumeTimers()
        resetIdleTimer()
    }

    override fun onCreate() {
        super.onCreate()
        resetIdleTimer()

        startForeground(NOTIFICATION_ID, createNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        setupMainFloatingLayout(this)
        createMainFloatingWindow(mainFloatingRootLayout)
        setMainFloatingWindowTouchListener(mainFloatingButtonContainer)
        createCloseZoneFlowingWindow()

        val telephonyManager =
            getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(
            phoneStateListener,
            PhoneStateListener.LISTEN_CALL_STATE
        )

        EventBus.getDefault().register(this)

        webViewHandler = ChallengeWebViewHandler(applicationContext, webView)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        resetIdleTimer()
        EventBus.getDefault().post(ChallengeEvents.EventCloudflareServiceReady())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun safelyRemoveWebView() {
        if (::mainFloatingRootLayout.isInitialized &&
            webViewAdded &&
            mainFloatingRootLayout.isAttachedToWindow
        ) {
            try {
                windowManager.removeView(mainFloatingRootLayout)
            } catch (e: IllegalArgumentException) {
                print(e)
            } finally {
                webViewAdded = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM) // API 35+
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(idleRunnable)

        webViewHandler.destroy() // will call stopLoading() on the WebView
        EventBus.getDefault().unregister(this)

        safelyRemoveWebView()
        if (::webView.isInitialized) {
            webView.destroy()
        }

        safelyRemoveCloseZoneView()

        super.onDestroy()
    }

    private fun safelyRemoveCloseZoneView() {
        if (::closeZoneView.isInitialized &&
            closeZoneAdded &&
            closeZoneView.isAttachedToWindow
        ) {
            try {
                windowManager.removeView(closeZoneView)
            } catch (e: IllegalArgumentException) {
                print(e)
                // OEM or race-condition safety
            } finally {
                closeZoneAdded = false
            }
        }
    }

    // ----------------------------------------------------
    // Overlay creation
    // ----------------------------------------------------
    private fun setupMainFloatingLayout(context: Context) {
        // Container
        mainFloatingRootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x88FF0000.toInt()) // semi-transparent red
        }

        mainFloatingButtonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val toolbarLikeHeight = dpToPx(TOOLBAR_LIKE_HEIGHT_DP, context)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, toolbarLikeHeight)

            gravity = Gravity.END
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
        }

        val buttonSize = dpToPx(TOOLBAR_LIKE_HEIGHT_DP - 3, context)
        val closeButton = Button(context).apply {
            text = "x"
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
            setOnClickListener { closeOverlay() }
        }

        val minimizeButton = Button(context).apply {
            text = "–"
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
            setOnClickListener { minimize() }
        }

        mainFloatingButtonContainer.addView(minimizeButton)
        mainFloatingButtonContainer.addView(closeButton)

        // MessageBox (show errors - initial not visible)
        messageBox = TextView(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setPadding(15, 15, 15, 15)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        mainFloatingRootLayout.addView(mainFloatingButtonContainer)
        mainFloatingRootLayout.addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        mainFloatingRootLayout.addView(
            messageBox,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
        webViewAdded = true
    }

    private fun createMainFloatingWindow(container: LinearLayout) {
        mainFloatingWindowLayoutParams = WindowManager.LayoutParams(
            1, // start minimized
            1,
            getWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        windowManager.addView(container, mainFloatingWindowLayoutParams)
    }

    // Add this method in your service or activity class
    @SuppressLint("ClickableViewAccessibility")
    private fun setMainFloatingWindowTouchListener(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Record initial touch positions
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false

                    // Let the [mainFloatingButtonContainer] handle the DOWN action (so clicks still work)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY

                    // Check if the movement is greater than touch slop (start dragging)
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        showCloseZone()
                        startX = mainFloatingWindowLayoutParams.x
                        startY = mainFloatingWindowLayoutParams.y
                        isDragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true) // Prevent parent from intercepting
                    }

                    if (isDragging) {
                        // Move the main floating window with the drag
                        mainFloatingWindowLayoutParams.x = startX + dx.toInt()
                        mainFloatingWindowLayoutParams.y = startY + dy.toInt()
                        windowManager.updateViewLayout(
                            mainFloatingRootLayout,
                            mainFloatingWindowLayoutParams
                        )
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    hideCloseZone()

                    // Handle the drop logic, close or snap
                    if (isDragging) {
                        if (isInCloseZone(event.rawY)) {
                            closeOverlay()
                        } else {
                            snapToEdge()
                        }
                        isDragging = false
                        true // drag handled
                    } else {
                        // Let WebView handle the click (for tap/click interaction)
                        false
                    }
                }

                else -> false
            }
        }
    }

    private fun closeOverlay() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun showCloseZone() {
        if (!closeZoneView.isAttachedToWindow && !closeZoneAdded) {
            windowManager.addView(closeZoneView, closeZoneParams)
            closeZoneAdded = true
        }
    }

    private fun hideCloseZone() {
        safelyRemoveCloseZoneView()
    }

    private fun snapToEdge() {
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val viewWidth =
            if (mainFloatingRootLayout.width > 0) mainFloatingRootLayout.width else mainFloatingWindowLayoutParams.width

        // Simple logic: If center is left of screen middle, snap to 0, else snap to right
        mainFloatingWindowLayoutParams.x =
            if (mainFloatingWindowLayoutParams.x + viewWidth / 2 < screenWidth / 2) {
                0
            } else {
                screenWidth - viewWidth
            }

        try {
            if (mainFloatingRootLayout.windowToken != null) {
                windowManager.updateViewLayout(
                    mainFloatingRootLayout,
                    mainFloatingWindowLayoutParams
                )
            }
        } catch (ignored: IllegalArgumentException) {
            // Safe exit if view was removed
        }
    }

    // Close zone hit test (TOP zone)
    private fun isInCloseZone(rawY: Float): Boolean {
        return rawY <= dpToPx(80, baseContext)
    }

    private fun getWindowType(): Int {
        val windowType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        return windowType
    }

    private fun createCloseZoneFlowingWindow() {
        val closeTextInfo = TextView(this).apply {
            setPadding(15, 15, 15, 15)
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            text = getString(R.string.close)
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            gravity = Gravity.CENTER
        }

        closeZoneView = FrameLayout(this).apply {
            setBackgroundColor(0x88FF0000.toInt()) // semi-transparent red
            addView(closeTextInfo)
        }

        closeZoneParams = WindowManager.LayoutParams(
            MATCH_PARENT,
            dpToPx(80, baseContext),
            getWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }
        // zone NOT added yet — added only while dragging
    }

    private fun dpToPx(
        @Dimension(unit = Dimension.DP) dp: Int,
        context: Context
    ): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    // ----------------------------------------------------
    // control methods (call via EventBus)
    // ----------------------------------------------------
    private fun minimize() {
        mainFloatingWindowLayoutParams.width = 1
        mainFloatingWindowLayoutParams.height = 1
        mainFloatingWindowLayoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        windowManager.updateViewLayout(mainFloatingRootLayout, mainFloatingWindowLayoutParams)
        hideMessageBox()
    }

    private fun expandInteractive() {
        val dm = Resources.getSystem().displayMetrics

        mainFloatingWindowLayoutParams.width =
            (dm.widthPixels * mainFloatingWindowPercentScreenSize).toInt()
        mainFloatingWindowLayoutParams.height =
            (dm.heightPixels * mainFloatingWindowPercentScreenSize).toInt()
        // mainFloatingWindowLayoutParams.flags = 0 // allow focus + touch
        mainFloatingWindowLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        windowManager.updateViewLayout(mainFloatingRootLayout, mainFloatingWindowLayoutParams)

        webView.setBackgroundColor(Color.WHITE)
    }

    private fun expandNonInteractive() { // not used atm
        val dm = Resources.getSystem().displayMetrics

        mainFloatingWindowLayoutParams.width =
            (dm.widthPixels * mainFloatingWindowPercentScreenSize).toInt()
        mainFloatingWindowLayoutParams.height =
            (dm.heightPixels * mainFloatingWindowPercentScreenSize).toInt()
        mainFloatingWindowLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        windowManager.updateViewLayout(mainFloatingRootLayout, mainFloatingWindowLayoutParams)
    }

    private fun showErrorMessage(error: String) {
        mainHandler.post {
            messageBox.text = "⚠️ $error"
            messageBox.visibility = View.VISIBLE
        }
    }

    private fun hideMessageBox() {
        mainHandler.post {
            messageBox.visibility = View.GONE
        }
    }

    // ----------------------------------------------------
    // Foreground notification
    // ----------------------------------------------------
    private fun createNotification(): Notification {
        val channelId = "floating_webview"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Floating WebView",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.challenge_notification_title))
            .setContentText(getString(R.string.challenge_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val TOOLBAR_LIKE_HEIGHT_DP = 48
    }

    // /section handle the eventbus
    @Subscribe(threadMode = ThreadMode.MAIN)
    override fun handleEventServiceActions(event: ChallengeEvents.EventServiceActions) {
        resetIdleTimer()
        when (event.action) {
            ChallengeEvents.EventServiceActions.Actions.ShutdownService -> closeOverlay()
            ChallengeEvents.EventServiceActions.Actions.MinimizeOverlay -> minimize()
            ChallengeEvents.EventServiceActions.Actions.InteractiveOverlay -> expandInteractive()
            ChallengeEvents.EventServiceActions.Actions.ShowError -> showErrorMessage(event.msg)
            ChallengeEvents.EventServiceActions.Actions.HideError -> hideMessageBox()
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    override fun handleEventFetchContentRequest(event: ChallengeEvents.EventCloudflareChallengeRequest) {
        resetIdleTimer()
        webViewHandler.fetchContentAsync(event)
    }
}
