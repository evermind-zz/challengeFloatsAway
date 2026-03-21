package com.github.evermindzz.challengefloatsaway

import com.github.evermindzz.challengefloatsaway.ui.DefaultDialogProvider
import com.github.evermindzz.challengefloatsaway.ui.SimpleDialogProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChallengeConfig(
    var userAgent: String = "",
    var cookieDomains: Array<String> = emptyArray(),
    var isInteractive: Boolean = false,
    var dialogProvider: SimpleDialogProvider = DefaultDialogProvider()
)

object ChallengeSettings {
    private val _config = MutableStateFlow(ChallengeConfig())

    val config = _config.asStateFlow()

    /**
     * This method takes the current state and allows to change only fields you want to change.
     */
    fun update(transform: (ChallengeConfig) -> ChallengeConfig) {
        _config.update { current ->
            transform(current)
        }
    }
}
