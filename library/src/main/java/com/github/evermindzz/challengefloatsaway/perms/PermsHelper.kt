package com.github.evermindzz.challengefloatsaway.perms

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.Html
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.github.evermindzz.challengefloatsaway.R

object PermsHelper {
    /**
     * This method has to be called within an activity context.
     *
     * The calling activity should listen in [android.app.Activity.onActivityResult]
     * as [Settings.ACTION_MANAGE_OVERLAY_PERMISSION] will report the result back this way.
     *
     *  @param context the android context
     *  @param overlayName the name of the overlay window to be shown in a dialog for android >=R
     *  @param intentLauncher the method that gets feed an intent to launch an intent
     *
     *  @return it could be either Granted or CheckForActivityResult
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    fun checkSystemAlertWindowPermission(
        context: Context,
        overlayName: String,
        intentLauncher: (Intent) -> Unit
    ): OverlayPermissionResult {
        if (!Settings.canDrawOverlays(context)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    ("package:" + context.packageName).toUri()
                )
                try {
                    intentLauncher(intent)
                } catch (ignored: ActivityNotFoundException) {
                }
                // from Android R the ACTION_MANAGE_OVERLAY_PERMISSION will only point to the menu,
                // so let’s add a dialog that points the user to the right setting.
            } else {
                val appName = context.applicationInfo
                    .loadLabel(context.packageManager).toString()
                val title = context.getString(R.string.permission_display_over_apps)
                val permissionName =
                    context.getString(R.string.permission_display_over_apps_permission_name)
                val appNameItalic = "<i>$appName</i>"
                val permissionNameItalic = "<i>$permissionName</i>"
                val message =
                    context.getString(
                        R.string.permission_display_over_apps_message,
                        overlayName,
                        appNameItalic,
                        permissionNameItalic
                    )
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage(Html.fromHtml(message, Html.FROM_HTML_MODE_COMPACT))
                    .setPositiveButton(
                        "OK",
                        DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                            // we don’t need the package name here, since it won’t do anything on >R
                            val intent =
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            try {
                                intentLauncher(intent)
                            } catch (ignored: ActivityNotFoundException) {
                            }
                        }
                    )
                    .setCancelable(true)
                    .show()
            }
            return OverlayPermissionResult.CheckForActivityResult
        } else {
            return OverlayPermissionResult.Granted
        }
    }

    enum class OverlayPermissionResult {
        Granted,
        Denied,
        CheckForActivityResult
    }

    class EventOverlayPermissionResult(val permResult: OverlayPermissionResult) {
        interface Handler {
            fun handleEventOverlayPermissionResult(event: OverlayPermissionResult)
        }
    }
}
