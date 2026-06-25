package com.wearosgpx.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the bundled recommended-models list (provider -> ordered model options). */
class RecommendedModelsTest {

    private val sample = """
        {
          "gemini": [
            {"model":"gemini-2.5-flash","label":"Gemini 2.5 Flash · recommended"},
            {"model":"gemini-2.0-flash","label":"Gemini 2.0 Flash"}
          ],
          "groq": [
            {"model":"llama-3.3-70b-versatile","label":"Llama 3.3 70B"}
          ]
        }
    """.trimIndent()

    @Test
    fun parse_readsProvidersInOrder() {
        val map = RecommendedModels.parse(sample)
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.0-flash"), map["gemini"]!!.map { it.model })
        assertEquals("Gemini 2.5 Flash · recommended", map["gemini"]!!.first().label)
        assertEquals("llama-3.3-70b-versatile", map["groq"]!!.first().model)
    }

    @Test
    fun parse_recommendedIsFirst() {
        val map = RecommendedModels.parse(sample)
        assertEquals("gemini-2.5-flash", RecommendedModels.firstModel(map, "gemini"))
    }

    @Test
    fun parse_badJsonYieldsEmpty() {
        assertTrue(RecommendedModels.parse("not json").isEmpty())
    }
}
