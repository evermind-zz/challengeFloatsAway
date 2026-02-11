package com.github.evermindzz.challengefloatsaway.misc

import org.junit.Before
import org.junit.Test

class BraveCChallengeWebViewHandlerTest {
    private lateinit var validator: ChallengeValidateJavaScriptValue

    @Before
    fun setup() {
        validator = ChallengeValidateJavaScriptValue()
    }

    fun getValidJsonString() : String = """
        {
          "meta": {
            "version": "1.0",
            "description": "Example with special characters [ and } in payload"
          },
          "users": [
            {
              "id": 1,
              "name": "Alice",
              "notes": "Text with square bracket [ and curly brace } in string",
              "tags": ["admin", "test[case]", "role}x"]
            },
            {
              "id": 2,
              "name": "Bob",
              "notes": "Another example: array-like text [1,2,3}",
              "preferences": {
                "theme": "dark",
                "rawValue": "payload{key}[value]}"
              }
            }
          ],
          "messages": [
            "simple string",
            "contains [ bracket",
            "contains } brace",
            "contains both[[[ [ and }"
          ]
        }
        """.trimIndent()


    @Test
    fun isJsonCompleteTest() {
        val isValid = validator.isJsonComplete(getValidJsonString())
        assert(isValid)
        val isInvalid = !validator.isJsonComplete(getValidJsonString() + "}")
        assert(isInvalid)
    }
}
