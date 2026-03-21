package com.github.evermindzz.challengefloatsaway.ui

import android.app.AlertDialog
import android.content.Context

/**
 * The library uses [android.app.AlertDialog] as the default Dialog
 *
 * This [SimpleDialogProvider] implementation looks very old, like
 * Android 2,3. The library does no theming or wants to have
 * additional dependencies.
 */
class DefaultDialogProvider : SimpleDialogProvider {

    override fun showConfirmDialog(
        context: Context,
        title: String?,
        message: CharSequence,
        positive: String,
        negative: String?,
        onPositive: () -> Unit,
        onNegative: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onPositive() }
            .apply {
                if (negative != null) {
                    setNegativeButton(negative) { _, _ -> onNegative() }
                } else {
                    setNegativeButton(null as String?, null)
                }
            }
            .setOnCancelListener { onNegative() }
            .show()
    }
}