package com.example.kazumi.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Verifies the pure-Kotlin specification behind the PHONE-SIDE VR settings panel.
 * No Android framework stubs are needed: the same specs drive the Compose sliders.
 */
class VrSettingSpecTest {

    @Test
    fun allSpecsHaveUniqueIdsAndSaneRanges() {
        val specs = VrSettingSpecs.ALL
        assertTrue("phone settings panel must expose controls", specs.isNotEmpty())
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
        specs.forEach { spec ->
            assertTrue("${spec.id}: min < max", spec.min < spec.max)
            assertTrue("${spec.id}: step > 0", spec.step > 0f)
            assertTrue("${spec.id}: sliderSteps >= 0", spec.sliderSteps >= 0)
            val default = spec.read(VrSettings.DEFAULT)
            assertTrue("${spec.id}: default $default in ${spec.min}..${spec.max}",
                default >= spec.min && default <= spec.max)
        }
    }

    @Test
    fun quantizeAlwaysLandsOnTheStepGridAndInsideTheRange() {
        VrSettingSpecs.ALL.forEach { spec ->
            repeat(20) { i ->
                val raw = spec.min - 1f + (spec.max - spec.min + 2f) * i / 19f
                val q = spec.quantize(raw)
                assertTrue("${spec.id}: $q below min", q >= spec.min - 1e-4f)
                assertTrue("${spec.id}: $q above max", q <= spec.max + 1e-4f)
                val ticks = (q - spec.min) / spec.step
                assertTrue("${spec.id}: $q not on step grid", abs(ticks - Math.round(ticks)) < 1e-3f)
            }
        }
    }

    @Test
    fun quantizeClampsOutOfRangeAndNonFiniteValues() {
        VrSettingSpecs.ALL.forEach { spec ->
            assertEquals(spec.id, spec.min, spec.quantize(spec.min - 100f), 1e-4f)
            assertEquals(spec.id, spec.max, spec.quantize(spec.max + 100f), 1e-4f)
            assertEquals(spec.id, spec.min, spec.quantize(Float.NaN), 1e-4f)
            assertEquals(spec.id, spec.min, spec.quantize(Float.POSITIVE_INFINITY), 1e-4f)
        }
    }

    @Test
    fun writeThenReadSurvivesSanitization() {
        VrSettingSpecs.ALL.forEach { spec ->
            val target = spec.quantize(spec.min + (spec.max - spec.min) * 0.37f)
            val updated = spec.write(VrSettings.DEFAULT, target).sanitized()
            assertEquals(spec.id, target, spec.read(updated), 1e-3f)
        }
    }

    @Test
    fun headlineWindowSizeControlCoversTheWholeScaleRange() {
        val scale = VrSettingSpecs.byId("screenScale")
        assertNotNull(scale)
        assertEquals(VrRanges.SCREEN_SCALE_MIN, scale!!.min, 0f)
        assertEquals(VrRanges.SCREEN_SCALE_MAX, scale.max, 0f)
        assertEquals(1.0f, scale.read(VrSettings.DEFAULT), 0f)
        assertEquals(3.0f, scale.quantize(3.0f), 1e-4f)
        assertEquals(3.0f, scale.read(scale.write(VrSettings.DEFAULT, 3.0f).sanitized()), 1e-4f)
    }

    @Test
    fun formatNeverReturnsAnEmptyString() {
        VrSettingSpecs.ALL.forEach { spec ->
            val text = spec.format(spec.read(VrSettings.DEFAULT))
            assertTrue("${spec.id}: empty label", text.isNotBlank())
            assertFalse("${spec.id}: NaN in label", text.contains("NaN"))
        }
    }

    /** "单纯放大" must be exposed on the phone side too, with 1.00x as the exact no-op. */
    @Test
    fun zoomSliderExposesTheWholeRangeIncludingTheNoOp() {
        val spec = VrSettingSpecs.byId("zoomScale")!!
        assertEquals(VrRanges.ZOOM_MIN, spec.min, 0f)
        assertEquals(VrRanges.ZOOM_MAX, spec.max, 0f)
        assertEquals(VrRanges.ZOOM_STEP, spec.step, 0f)
        assertEquals(VrRanges.ZOOM_MIN, spec.quantize(VrRanges.ZOOM_MIN), 1e-4f)
        val noOp = spec.write(VrSettings.DEFAULT, VrRanges.ZOOM_MIN).sanitized()
        assertEquals(VrRanges.ZOOM_MIN, spec.read(noOp), 1e-4f)
    }

    @Test
    fun repeatIntervalConvertsBetweenFloatSliderAndLongSetting() {
        val spec = VrSettingSpecs.byId("menuRepeatMs")!!
        val updated = spec.write(VrSettings.DEFAULT, 260f).sanitized()
        assertEquals(260L, updated.menuRepeatMs)
        assertEquals(260f, spec.read(updated), 0f)
    }

    /** The two new passthrough sliders are ordinary specs: bounded, stepped, sanitised. */
    @Test
    fun passthroughSlidersExistWithDeclaredBounds() {
        val opacity = VrSettingSpecs.byId("passthroughOpacity")!!
        assertEquals(VrRanges.PASSTHROUGH_OPACITY_MIN, opacity.min, 0f)
        assertEquals(VrRanges.PASSTHROUGH_OPACITY_MAX, opacity.max, 0f)
        assertEquals(VrRanges.PASSTHROUGH_OPACITY_STEP, opacity.step, 0f)
        assertEquals(1.00f, opacity.read(VrSettings.DEFAULT), 0f)
        val window = VrSettingSpecs.byId("passthroughWindowScale")!!
        assertEquals(VrRanges.PASSTHROUGH_WINDOW_MIN, window.min, 0f)
        assertEquals(VrRanges.PASSTHROUGH_WINDOW_MAX, window.max, 0f)
        assertEquals(VrRanges.PASSTHROUGH_WINDOW_STEP, window.step, 0f)
        assertEquals(0.85f, window.read(VrSettings.DEFAULT), 0f)
    }
}
