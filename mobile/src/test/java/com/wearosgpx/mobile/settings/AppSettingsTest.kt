package com.wearosgpx.mobile.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider auto-detection from an API key prefix (so the user only pastes the key). */
class AppSettingsTest {

    @Test
    fun detectProvider_byKeyPrefix() {
        assertEquals("OpenRouter", AppSettings.detectProvider("sk-or-v1-abc").name)
        assertEquals("Groq", AppSettings.detectProvider("gsk_abc").name)
        assertEquals("Google Gemini", AppSettings.detectProvider("AIzaSyabc").name)
        assertEquals("OpenAI", AppSettings.detectProvider("sk-proj-abc").name)
        assertEquals("OpenAI", AppSettings.detectProvider("sk-abc").name)
    }

    @Test
    fun detectProvider_openRouterCheckedBeforeOpenAi() {
        // "sk-or-" also starts with "sk-", so order matters.
        assertEquals("https://openrouter.ai/api/v1", AppSettings.detectProvider("sk-or-v1-x").baseUrl)
    }

    @Test
    fun detectProvider_unknownFallsBackToOpenAiCompatible() {
        val p = AppSettings.detectProvider("someMistralOrOtherKey")
        assertEquals("https://api.openai.com/v1", p.baseUrl)
    }
}
