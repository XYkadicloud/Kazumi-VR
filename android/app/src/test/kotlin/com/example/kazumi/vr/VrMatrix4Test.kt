package com.example.kazumi.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class VrMatrix4Test {

    private fun assertEqualsMatrix(expected: FloatArray, actual: FloatArray, delta: Float = 1e-5f) {
        assertEquals(VrMatrix4.SIZE, actual.size)
        for (i in 0 until VrMatrix4.SIZE) {
            assertEquals("matrix element $i", expected[i], actual[i], delta)
        }
    }

    @Test
    fun identityHasOnesOnTheDiagonal() {
        val m = VrMatrix4.identity()
        assertEquals(VrMatrix4.SIZE, m.size)
        for (column in 0..3) {
            for (row in 0..3) {
                val expected = if (column == row) 1f else 0f
                assertEquals("[$column,$row]", expected, m[column * 4 + row], 0f)
            }
        }
    }

    @Test
    fun multiplyingByIdentityIsNeutralOnBothSides() {
        val rot = VrMatrix4.rotation(0f, 1f, 0f, 0.7f)
        assertEqualsMatrix(rot, VrMatrix4.multiply(VrMatrix4.identity(), rot))
        assertEqualsMatrix(rot, VrMatrix4.multiply(rot, VrMatrix4.identity()))
    }

    @Test
    fun multiplicationIsAssociative() {
        val a = VrMatrix4.rotation(1f, 0f, 0f, 0.3f)
        val b = VrMatrix4.rotation(0f, 1f, 0f, 0.8f)
        val c = VrMatrix4.rotation(0f, 0f, 1f, 0.5f)
        val left = VrMatrix4.multiply(VrMatrix4.multiply(a, b), c)
        val right = VrMatrix4.multiply(a, VrMatrix4.multiply(b, c))
        assertEqualsMatrix(left, right)
    }

    @Test
    fun multiplicationIsNotCommutative() {
        val a = VrMatrix4.rotation(1f, 0f, 0f, 0.9f)
        val b = VrMatrix4.rotation(0f, 1f, 0f, 0.9f)
        val ab = VrMatrix4.multiply(a, b)
        val ba = VrMatrix4.multiply(b, a)
        var different = false
        for (i in 0 until VrMatrix4.SIZE) {
            if (kotlin.math.abs(ab[i] - ba[i]) > 1e-4f) different = true
        }
        assertTrue("rotations about different axes must not commute", different)
    }

    @Test
    fun yRotationMatchesTheAnalyticColumnMajorForm() {
        val angle = 0.6f
        val m = VrMatrix4.rotation(0f, 1f, 0f, angle)
        assertEquals(cos(angle), m[0], 1e-6f)
        assertEquals(-sin(angle), m[2], 1e-6f)
        assertEquals(1f, m[5], 1e-6f)
        assertEquals(sin(angle), m[8], 1e-6f)
        assertEquals(cos(angle), m[10], 1e-6f)
        assertEquals(1f, m[15], 1e-6f)
    }

    @Test
    fun axisIsNormalisedBeforeUse() {
        val unit = VrMatrix4.rotation(0f, 1f, 0f, 0.4f)
        val scaled = VrMatrix4.rotation(0f, 10f, 0f, 0.4f)
        assertEqualsMatrix(unit, scaled)
    }

    @Test
    fun zeroLengthAxisReturnsIdentity() {
        assertEqualsMatrix(
            VrMatrix4.identity(),
            VrMatrix4.rotation(0f, 0f, 0f, 1f),
            delta = 0f,
        )
    }

    @Test
    fun rejectsNonSquare4x4Input() {
        assertThrows(IllegalArgumentException::class.java) {
            VrMatrix4.multiply(FloatArray(9), VrMatrix4.identity())
        }
        assertThrows(IllegalArgumentException::class.java) {
            VrMatrix4.multiply(VrMatrix4.identity(), FloatArray(17))
        }
    }

    // --- Zero-allocation render-loop paths ---------------------------------------------------

    @Test
    fun multiplyIntoMatchesAllocatingMultiplyAndReusesTheArray() {
        val a = VrMatrix4.rotation(1f, 0f, 0f, 0.3f)
        val b = VrMatrix4.rotation(0f, 1f, 0f, 0.8f)
        val out = FloatArray(VrMatrix4.SIZE)
        val returned = VrMatrix4.multiplyInto(out, a, b)
        assertEqualsMatrix(VrMatrix4.multiply(a, b), out)
        assertTrue("multiplyInto must write into the caller's array", returned === out)
    }

    @Test
    fun multiplyIntoCanBeCalledRepeatedlyWithoutStaleValues() {
        val a = VrMatrix4.rotation(1f, 0f, 0f, 0.3f)
        val b = VrMatrix4.rotation(0f, 1f, 0f, 0.8f)
        val out = FloatArray(VrMatrix4.SIZE)
        VrMatrix4.multiplyInto(out, a, b)
        VrMatrix4.multiplyInto(out, VrMatrix4.identity(), VrMatrix4.identity())
        assertEqualsMatrix(VrMatrix4.identity(), out)
    }

    @Test
    fun multiplyIntoRejectsAliasedOutput() {
        val a = VrMatrix4.rotation(0f, 1f, 0f, 0.4f)
        assertThrows(IllegalArgumentException::class.java) {
            VrMatrix4.multiplyInto(a, a, VrMatrix4.identity())
        }
        assertThrows(IllegalArgumentException::class.java) {
            VrMatrix4.multiplyInto(a, VrMatrix4.identity(), a)
        }
    }

    @Test
    fun transposeRotationIntoIsTheInverseRotation() {
        val r = VrMatrix4.rotation(0.3f, 0.8f, -0.5f, 0.9f)
        val inverse = VrMatrix4.transposeRotationInto(FloatArray(VrMatrix4.SIZE), r)
        assertEqualsMatrix(VrMatrix4.identity(), VrMatrix4.multiply(r, inverse))
        assertEqualsMatrix(VrMatrix4.identity(), VrMatrix4.multiply(inverse, r))
    }

    @Test
    fun transposeRotationIntoSupportsAliasedOutput() {
        val r = VrMatrix4.rotation(1f, 0f, 0f, 0.4f)
        val expected = VrMatrix4.transposeRotationInto(FloatArray(VrMatrix4.SIZE), r)
        val aliased = r.copyOf()
        VrMatrix4.transposeRotationInto(aliased, aliased)
        assertEqualsMatrix(expected, aliased)
    }

    @Test
    fun identityIntoAndRotationIntoMatchAllocatingVariants() {
        val identityOut = FloatArray(VrMatrix4.SIZE).also { it.fill(7f) }
        assertEqualsMatrix(VrMatrix4.identity(), VrMatrix4.identityInto(identityOut))

        val rotationOut = FloatArray(VrMatrix4.SIZE)
        VrMatrix4.rotationInto(rotationOut, 0f, 1f, 0f, 0.65f)
        assertEqualsMatrix(VrMatrix4.rotation(0f, 1f, 0f, 0.65f), rotationOut)
    }

    // --- Effective IPD / 瞳距 ---------------------------------------------------------------

    @Test
    fun ipdShiftIsANoOpAtDefaultScaleAndForMonocular() {
        // Non-trivial w row so a wrong implementation cannot accidentally pass.
        val mvp = VrMatrix4.rotation(0f, 1f, 0f, 0.4f).also {
            it[3] = 0.2f; it[7] = -0.3f; it[11] = 0.4f; it[15] = 1.1f
        }
        val before = mvp.copyOf()
        assertTrue("must write into the caller's array", VrMatrix4.applyIpdShiftInto(mvp, 1f, 1f) === mvp)
        assertEqualsMatrix(before, mvp, 0f)
        VrMatrix4.applyIpdShiftInto(mvp, 0.5f, 0f) // MONOCULAR / SIMULATION
        assertEqualsMatrix(before, mvp, 0f)
    }

    @Test
    fun ipdShiftAddsKTimesWRowToXRowOnly() {
        val mvp = VrMatrix4.identity().also {
            it[3] = 0.5f; it[7] = -0.25f; it[11] = 0.75f; it[15] = 2f
        }
        val before = mvp.copyOf()
        val scale = 0.5f
        val k = (1f - scale) * VrMatrix4.IPD_SHIFT_GAIN * 1f
        VrMatrix4.applyIpdShiftInto(mvp, scale, 1f)
        assertEquals(before[0] + k * before[3], mvp[0], 1e-6f)
        assertEquals(before[4] + k * before[7], mvp[4], 1e-6f)
        assertEquals(before[8] + k * before[11], mvp[8], 1e-6f)
        assertEquals(before[12] + k * before[15], mvp[12], 1e-6f)
        for (i in 0 until VrMatrix4.SIZE) {
            if (i % 4 != 0) assertEquals("row $i must be untouched", before[i], mvp[i], 0f)
        }
    }

    @Test
    fun ipdShiftIsEqualAndOppositeForTheTwoEyes() {
        val base = VrMatrix4.identity().also { it[3] = 0.6f; it[15] = 1.5f }
        val left = base.copyOf().also { VrMatrix4.applyIpdShiftInto(it, 0.5f, 1f) }
        val right = base.copyOf().also { VrMatrix4.applyIpdShiftInto(it, 0.5f, -1f) }
        val dl = left[0] - base[0]
        val dr = right[0] - base[0]
        assertTrue("reducing IPD must pull the two eyes' images together", dl > 0f)
        assertEquals(dl, -dr, 1e-6f)
        // Increasing IPD must push them apart (reverse direction).
        val wide = base.copyOf().also { VrMatrix4.applyIpdShiftInto(it, 1.6f, 1f) }
        assertTrue("increasing IPD must push the two eyes' images apart", wide[0] < base[0])
    }

    @Test
    fun ipdShiftRejectsBadSize() {
        assertThrows(IllegalArgumentException::class.java) {
            VrMatrix4.applyIpdShiftInto(FloatArray(9), 1.2f, 1f)
        }
    }
}
