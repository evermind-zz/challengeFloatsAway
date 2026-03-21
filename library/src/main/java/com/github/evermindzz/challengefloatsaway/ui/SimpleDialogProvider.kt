package com.github.evermindzz.challengefloatsaway.ui

import android.content.Context

/**
 * Use this interface to wrap your preferred Dialog implementation.
 *
 * The library user is encouraged to provide their own dialog
 * implementation of [SimpleDialogProvider] that suits their app style.
 *
 * Default implementation [DefaultDialogProvider]
 */
interface SimpleDialogProvider {

    /**
     * Displays a simple dialog with a title, message, a positive button,
     * and an optional negative button.
     *
     * @param title       optional dialog title (nullable)
     * @param message     main message text (required)
     * @param positive    text for the "Yes/OK" button
     * @param negative    text for the "Cancel/No" button – null means no negative button
     * @param onPositive  invoked when the positive button is clicked
     * @param onNegative  invoked when the negative button is clicked or the dialog is dismissed
     */
    fun showConfirmDialog(
        context: Context,
        title: String?,
        message: CharSequence,
        positive: String,
        negative: String?,
        onPositive: () -> Unit,
        onNegative: () -> Unit = {}
    )
}