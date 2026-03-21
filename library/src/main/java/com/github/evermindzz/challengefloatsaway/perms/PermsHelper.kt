package com.github.evermindzz.challengefloatsaway.perms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.Html
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.github.evermindzz.challengefloatsaway.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
    suspend fun checkSystemAlertWindowPermission(
        context: Context,
        overlayName: String,
        intentLauncher: (Intent) -> Unit
    ): PermissionRequestResult = suspendCancellableCoroutine { continuation ->

        if (Settings.canDrawOverlays(context)) {
            continuation.resume(PermissionRequestResult.Granted)
            return@suspendCancellableCoroutine
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                ("package:" + context.packageName).toUri()
            )
            try {
                intentLauncher(intent)
            } catch (ignored: ActivityNotFoundException) {
            }
            continuation.resume(PermissionRequestResult.CheckForActivityResult)
            return@suspendCancellableCoroutine

        } else {
            // starting with Android R the ACTION_MANAGE_OVERLAY_PERMISSION will only point to the
            // menu, so let’s add a dialog that points the user to the right setting.
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
                        continuation.resume(PermissionRequestResult.CheckForActivityResult)
                    }
                )
                .setNegativeButton(
                    "Cancel"
                ) { p0, p1 ->
                    continuation.resume(PermissionRequestResult.Denied)
                }
                .setCancelable(true)
                .show()
        }
    }

    suspend fun requestPhonePermissionIfNeeded(
        activity: Activity,
        intentLauncher: ActivityResultLauncher<String?>
    ): PermissionRequestResult = suspendCancellableCoroutine { continuation ->
        val permission = Manifest.permission.READ_PHONE_STATE

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || ContextCompat.checkSelfPermission(activity, permission)
            == PackageManager.PERMISSION_GRANTED
        ) {
            continuation.resume(PermissionRequestResult.Granted)
            return@suspendCancellableCoroutine
        }

        if (activity.shouldShowRequestPermissionRationale(permission)) {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.phone_permission_title))
                .setMessage(activity.getString(R.string.phone_permission_message))
                .setPositiveButton(activity.getString(R.string.ok)) { _, _ ->
                    intentLauncher.launch(permission)
                    continuation.resume(PermissionRequestResult.CheckForActivityResult)
                }
                .setNegativeButton(
                    activity.getString(R.string.cancel)
                ) { p0, p1 ->
                    continuation.resume(PermissionRequestResult.Denied)
                }
                .show()
        } else {
            intentLauncher.launch(permission)
            continuation.resume(PermissionRequestResult.CheckForActivityResult)
        }
    }

    enum class PermissionRequestResult {
        Granted,
        Denied,
        CheckForActivityResult
    }

    class EventPermissionResult(
        val permResult: PermissionRequestResult,
        val whichPermission: String
    ) {
        interface Handler {
            fun handleEventOverlayPermissionResult(event: EventPermissionResult)
        }
    }
}
