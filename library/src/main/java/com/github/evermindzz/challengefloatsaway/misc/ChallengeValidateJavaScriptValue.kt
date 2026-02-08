package com.github.evermindzz.challengefloatsaway.misc

import android.util.Log
import com.github.evermindzz.challengefloatsaway.ChallengeResult
import com.github.evermindzz.challengefloatsaway.ChallengeWebViewHandler
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Validation of javaScript evaluation and helpers methods.
 */
class ChallengeValidateJavaScriptValue {

    companion object {
        val TAG: String = ChallengeValidateJavaScriptValue::class.java.simpleName
    }

    /**
     * evaluate javascript result and set success and content in [challengeResult].
     */
    fun validateJSValue(
        value: String,
        evalSource: ChallengeWebViewHandler.EvalSource,
        challengeResult: ChallengeResult
    ) {
        val res = unescapeJsonFromWebView(value)
        try {
            val wrapper = JSONObject(res)
            if (!wrapper.has("error")) {
                var isJson = wrapper.getBoolean("isJson")
                val dataContent = wrapper.getString("dataContent")

                challengeResult.content = dataContent
                if (isJson || evalSource == ChallengeWebViewHandler.EvalSource.PAGE_FINISHED) {
                    challengeResult.success = true
                } else if (evalSource == ChallengeWebViewHandler.EvalSource.PROGRESS_CHANGED) {
                    // try one more time if json is wrapped inside
                    val likelyJsonString =
                        detectedJsonWrappedInWebViewTemporaryTags(dataContent)
                    if (likelyJsonString != null) {
                        if (isJsonComplete(likelyJsonString)) {
                            isJson = true
                            challengeResult.success = true
                            challengeResult.content = likelyJsonString
                        }
                    }
                    Log.d(
                        TAG,
                        "evaluateViaJavaScript(): isLikelyJsonString=${likelyJsonString != null} isValidJson=$isJson content=${challengeResult.content}"
                    )
                }

                Log.d(
                    TAG,
                    "evaluateViaJavaScript(): isJSON=$isJson content=${challengeResult.content}"
                )
            } else {
                // no success
                Log.e(
                    TAG,
                    "evaluateViaJavaScript(): JS evaluate error message: ${
                        wrapper.getString(
                            "error"
                        )
                    }"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "evaluateViaJavaScript(): error parsing wrapper", e)
        }
    }

    /**
     * try one more time if it is a json file.
     *
     * Precondition:
     * - called from [ChallengeWebViewHandler.EvalSource.PROGRESS_CHANGED] as the WebView's dom is not yet complete and still
     *   contains garbage tags
     * - has several WebView type wrapped html tags
     * - big file
     * @return null if no json detected
     */
    private fun detectedJsonWrappedInWebViewTemporaryTags(res: String): String? {
        if (res.length > 51200 && // around 100kb as java is UTF-16
            (res.startsWith("<html>", true) && res.endsWith("</html>", true))
        ) {
            // start of JSON object or array ( { oder [ )
            val firstBrace = res.indexOf('{')
            val firstBracket = res.indexOf('[')
            val start = when {
                firstBrace != -1 && firstBracket != -1 -> min(firstBrace, firstBracket)
                firstBrace != -1 -> firstBrace
                else -> firstBracket
            }

            // end of JSON object or array ( } oder ] )
            val startSearchIdx = res.length - 300
            val lastBrace = res.lastIndexOf('}', startSearchIdx)
            val lastBracket = res.lastIndexOf(']', startSearchIdx)
            val end = max(lastBrace, lastBracket)

            // minimal json object/array integrity test
            val hasArrayStart =
                firstBracket != -1 && (firstBrace == -1 || firstBracket < firstBrace)
            val hasArrayEnd = lastBracket != -1 && (lastBrace == -1 || lastBracket > lastBrace)
            if (hasArrayStart != hasArrayEnd) {
                return null
            }
            if (start != -1 && end != -1 && end > start) {
                val sanitizedData = res.substring(start, end + 1)
                return sanitizedData
            }
        }
        return null
    }

    /**
     * simplified json validity checker.
     *
     * It only counts {,[,] and } so basically no real validation
     */
    private fun isJsonComplete(json: String): Boolean {
        if (json.isEmpty()) return false

        var braceCount = 0
        var bracketCount = 0
        var inString = false
        var isEscaped = false

        for (i in json.indices) {
            val c = json[i]

            // track string state to ignore brace/brackets in strings
            if (c == '"' && !isEscaped) {
                inString = !inString
            }

            // track escaped chars  \" inside of strings
            isEscaped = c == '\\' && !isEscaped

            // count brackets only outside of strings
            if (!inString) {
                when (c) {
                    '{' -> braceCount++
                    '}' -> braceCount--
                    '[' -> bracketCount++
                    ']' -> bracketCount--
                }
            }

            // if counting negative JSON is corrupt
            if (braceCount < 0 || bracketCount < 0) return false
        }

        // complete only if all brace/brackets are closed and no string open anymore
        return braceCount == 0 && bracketCount == 0 && !inString
    }

    // Remove the surrounding quotation marks
    private fun unescapeJsonFromWebView(data: String): String {
        var value = data
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        return value.replace("\\\\", "\\").replace("\\\"", "\"")
    }
}
