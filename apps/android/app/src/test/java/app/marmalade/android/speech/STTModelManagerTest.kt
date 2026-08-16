package app.marmalade.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class STTModelManagerTest {

    @Test
    fun `model inventory is the single bundled distil default`() {
        // ADR 0012: distil-small.en is the sole bundled model; the Whisper Small
        // download was removed (dominated) and Nemotron retired 2026-07-04.
        assertEquals(1, STTModelManager.MODEL_DEFINITIONS.size)
        assertEquals("distil_small_en", STTModelManager.DEFAULT_MODEL_ID)
        assertEquals(
            STTModelManager.DEFAULT_MODEL_ID,
            STTModelManager.MODEL_DEFINITIONS.single().id,
        )
    }

    @Test
    fun `retired engine ids are gone`() {
        val ids = STTModelManager.MODEL_DEFINITIONS.map { it.id }
        assertFalse(ids.contains("nemotron"))
        assertFalse(ids.contains("whisper_tiny"))
        assertFalse(ids.contains("whisper_small"))
    }

    @Test
    fun `all models are WHISPER_OFFLINE type`() {
        STTModelManager.MODEL_DEFINITIONS.forEach { model ->
            assertEquals(ModelType.WHISPER_OFFLINE, model.modelType)
        }
    }

    @Test
    fun `the default model is bundled`() {
        val model = STTModelManager.MODEL_DEFINITIONS.first { it.id == STTModelManager.DEFAULT_MODEL_ID }
        assertTrue(model.isBundled)
    }

    @Test
    fun `all models have non-empty display names`() {
        STTModelManager.MODEL_DEFINITIONS.forEach { model ->
            assertTrue("Model ${model.id} has empty displayName", model.displayName.isNotEmpty())
        }
    }

    @Test
    fun `all models have non-empty descriptions`() {
        STTModelManager.MODEL_DEFINITIONS.forEach { model ->
            assertTrue("Model ${model.id} has empty description", model.description.isNotEmpty())
        }
    }

    @Test
    fun `all models have positive sizeBytes`() {
        STTModelManager.MODEL_DEFINITIONS.forEach { model ->
            assertTrue("Model ${model.id} has non-positive sizeBytes", model.sizeBytes > 0)
        }
    }

    @Test
    fun `downloadable models have non-empty file lists`() {
        // Vacuously true today (no downloadable models ship); the contract still
        // holds for any future download tier.
        STTModelManager.MODEL_DEFINITIONS.filter { !it.isBundled }.forEach { model ->
            assertTrue("Model ${model.id} has empty files list", model.files.isNotEmpty())
        }
    }

    @Test
    fun `findModelById returns correct model`() {
        val model = STTModelManager.findModelById(STTModelManager.DEFAULT_MODEL_ID)
        assertNotNull(model)
        assertEquals(STTModelManager.DEFAULT_MODEL_ID, model!!.id)
        assertEquals("Distil-Whisper Small", model.displayName)
    }

    @Test
    fun `findModelById returns null for unknown ID`() {
        assertNull(STTModelManager.findModelById("unknown_model"))
    }
}
