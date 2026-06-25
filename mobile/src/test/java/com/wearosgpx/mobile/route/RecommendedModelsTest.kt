package com.wearosgpx.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsing of the bundled AI provider config (base URL + curated model menu). */
class RecommendedModelsTest {

    private val sample = """
        {
          "provider": "Google Gemini",
          "base_url": "https://generativelanguage.googleapis.com/v1beta/openai",
          "key_url": "https://aistudio.google.com/app/apikey",
          "models": [
            {"model": "gemini-2.5-flash", "label": "Gemini 2.5 Flash · recommended"},
            {"model": "gemini-2.5-pro", "label": "Gemini 2.5 Pro"}
          ]
        }
    """.trimIndent()

    @Test
    fun parse_readsProviderAndOrderedModels() {
        val c = RecommendedModels.parse(sample)!!
        assertEquals("Google Gemini", c.provider)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", c.baseUrl)
        assertEquals("https://aistudio.google.com/app/apikey", c.keyUrl)
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), c.models.map { it.model })
        // first = recommended default
        assertEquals("gemini-2.5-flash", c.models.first().model)
        assertEquals("Gemini 2.5 Flash · recommended", c.models.first().label)
    }

    @Test
    fun parse_badJsonIsNull() {
        assertNull(RecommendedModels.parse("not json"))
    }

    @Test
    fun parse_missingBaseUrlOrModelsIsNull() {
        assertNull(RecommendedModels.parse("""{"provider":"X","models":[{"model":"m"}]}"""))   // no base_url
        assertNull(RecommendedModels.parse("""{"base_url":"https://x","models":[]}"""))         // no models
    }
}
