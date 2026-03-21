package com.github.evermindzz.challengefloatsaway.perms

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.github.evermindzz.challengefloatsaway.R
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus

class PermissionActivity : ComponentActivity() {

    @RequiresApi(Build.VERSION_CODES.M)
    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val permResult = if (Settings.canDrawOverlays(applicationContext)) {
            PermsHelper.PermissionRequestResult.Granted
        } else {
            PermsHelper.PermissionRequestResult.Denied
        }

        sendMessageViaBraveBus(permResult, Manifest.permission.SYSTEM_ALERT_WINDOW)
        finish()
    }

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->

        val permResult = if (granted) {
            Log.d("Permission", "READ_PHONE_STATE OK")
            PermsHelper.PermissionRequestResult.Granted
        } else {
            PermsHelper.PermissionRequestResult.Denied
        }

        sendMessageViaBraveBus(permResult, Manifest.permission.READ_PHONE_STATE)
        finish()
    }

    fun sendMessageViaBraveBus(
        permRequestResult: PermsHelper.PermissionRequestResult,
        permissionName: String
    ) {
        EventBus.getDefault().post(
            PermsHelper.EventPermissionResult(
                permRequestResult,
                permissionName,
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedPermission = intent.getStringExtra(EXTRA_ANDROID_PERMISSION)
        val activity = this

        lifecycleScope.launch {
            if (requestedPermission == Manifest.permission.SYSTEM_ALERT_WINDOW) {
                val permResult = PermsHelper.checkSystemAlertWindowPermission(
                    activity,
                    getString(R.string.challenge_overlay_name)
                ) { intent ->
                    overlaySettingsLauncher.launch(intent)
                }
                sendMessageViaEventBusIfWeDontNeedCheckActivityResult(
                    permResult,
                    requestedPermission
                )
            } else if (requestedPermission == Manifest.permission.READ_PHONE_STATE) {
                val permResult = PermsHelper.requestPhonePermissionIfNeeded(
                    activity,
                    phonePermissionLauncher
                )
                sendMessageViaEventBusIfWeDontNeedCheckActivityResult(
                    permResult,
                    requestedPermission
                )
            }
        }
    }

    private fun sendMessageViaEventBusIfWeDontNeedCheckActivityResult(
        permResult: PermsHelper.PermissionRequestResult,
        permissionName: String
    ) {
        if (PermsHelper.PermissionRequestResult.CheckForActivityResult != permResult) {
            sendMessageViaBraveBus(permResult, permissionName)
            finish()
        }
    }

    companion object {
        const val EXTRA_ANDROID_PERMISSION = "extra_android_permission"

    }
}
