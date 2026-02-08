package com.github.evermindzz.challengefloatsaway

data class ChallengeResult(
    @JvmField
    var success: Boolean,
    @JvmField
    var content: String?,
    @JvmField
    var cookies: String?
)
