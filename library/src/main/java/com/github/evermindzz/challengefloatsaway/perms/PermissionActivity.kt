package com.github.evermindzz.challengefloatsaway.perms

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import com.github.evermindzz.challengefloatsaway.R
import org.greenrobot.eventbus.EventBus

class PermissionActivity : ComponentActivity() {

    @RequiresApi(Build.VERSION_CODES.M)
    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val permResult = if (Settings.canDrawOverlays(applicationContext)) {
            PermsHelper.OverlayPermissionResult.Granted
        } else {
            PermsHelper.OverlayPermissionResult.Denied
        }
        sendMessageViaBraveBus(permResult)
        finish()
    }

    fun sendMessageViaBraveBus(perms: PermsHelper.OverlayPermissionResult) {
        EventBus.getDefault().post(PermsHelper.EventOverlayPermissionResult(perms))
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permResult = PermsHelper.checkSystemAlertWindowPermission(
            this,
            getString(R.string.permission_display_over_apps_message)
        ) { intent ->
            overlaySettingsLauncher.launch(intent)
        }

        if (PermsHelper.OverlayPermissionResult.CheckForActivityResult != permResult) {
            sendMessageViaBraveBus(permResult)
            finish()
        }
    }
}
