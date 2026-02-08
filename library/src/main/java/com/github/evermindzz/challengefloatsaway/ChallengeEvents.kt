package com.github.evermindzz.challengefloatsaway

import android.util.Log

class ChallengeEvents {
    class EventCloudflareChallengeRequest(
        val requestId: Long,
        val url: String,
        val timeoutMs: Long
    ) {
        init {
            Log.d(this.javaClass.simpleName, "() requestId=$requestId url=$url timeout=$timeoutMs")
        }

        interface Handler {
            fun handleEventFetchContentRequest(event: EventCloudflareChallengeRequest)
        }
    }

    class EventCloudflareChallengeResponse(
        @JvmField var requestId: Long,
        @JvmField val result: ChallengeResult
    ) {
        init {
            Log.d(this.javaClass.simpleName, "() requestId=$requestId success=${result.success}")
        }

        interface Handler {
            fun handleEventCloudflareChallengeResponse(event: EventCloudflareChallengeResponse)
        }
    }

    class EventCloudflareServiceReady {
        interface Handler {
            fun handleEventCloudflareServiceReady(event: EventCloudflareServiceReady)
        }
    }

    class EventServiceActions(
        val action: Actions,
        val msg: String = ""
    ) {

        enum class Actions {
            ShutdownService,
            MinimizeOverlay,
            InteractiveOverlay,
            ShowError,
            HideError
        }

        interface Handler {
            fun handleEventServiceActions(event: EventServiceActions)
        }
    }
}
