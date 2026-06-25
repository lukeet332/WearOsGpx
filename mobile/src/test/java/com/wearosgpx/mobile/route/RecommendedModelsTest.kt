package com.wearosgpx.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the bundled, self-describing model menu (each entry carries its provider). */
class RecommendedModelsTest {

    private val sample = """
        {
          "models": [
            {"provider": "Google Gemini", "base_url": "https://gen.example/openai", "key_url": "https://get.key", "model": "gemini-2.5-flash", "label": "Gemini 2.5 Flash · recommended"},
            {"provider": "Groq", "base_url": "https://groq.example/openai", "key_url": "https://groq.key", "model": "llama-3.3-70b", "label": "Llama 3.3 70B"}
          ]
        }
    """.trimIndent()

    @Test
    fun parse_readsSelfDescribingEntriesInOrder() {
        val es = RecommendedModels.parse(sample)
        assertEquals(2, es.size)
        // first = recommended default, with its own provider/base/key
        assertEquals("gemini-2.5-flash", es.first().model)
        assertEquals("Google Gemini", es.first().provider)
        assertEquals("https://gen.example/openai", es.first().baseUrl)
        assertEquals("https://get.key", es.first().keyUrl)
        // entries can mix providers
        assertEquals("Groq", es[1].provider)
        assertEquals("https://groq.example/openai", es[1].baseUrl)
    }

    @Test
    fun parse_badJsonIsEmpty() {
        assertTrue(RecommendedModels.parse("not json").isEmpty())
    }

    @Test
    fun parse_skipsEntriesMissingBaseUrlOrModel() {
        val es = RecommendedModels.parse(
            """{"models":[{"provider":"X","model":"m"},{"provider":"Y","base_url":"https://y","model":"good"}]}"""
        )
        assertEquals(1, es.size)            // first dropped (no base_url)
        assertEquals("good", es.first().model)
    }
}
