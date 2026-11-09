package com.example.kazumi.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class VrHeadPoseTest {

    /** Rotation of `angle` radians around +Y, encoded as (x, y, z, w). */
    private fun quaternion(angle: Float): FloatArray {
        val half = angle / 2f
        return floatArrayOf(0f, sin(half), 0f, cos(half))
    }

    private fun assertEqualsMatrix(expected: FloatArray, actual: FloatArray, delta: Float = 1e-5f) {
        for (i in 0 until VrMatrix4.SIZE) {
            assertEquals("matrix element $i", expected[i], actual[i], delta)
        }
    }

    @Test
    fun identityQuaternionGivesIdentityMatrix() {
        assertEqualsMatrix(
            VrMatrix4.identity(),
            VrHeadPose.scaledRotation(floatArrayOf(0f, 0f, 0f, 1f), 1f),
            delta = 1e-6f,
        )
    }

    @Test
    fun zeroSensitivityLocksTheView() {
        val actual = VrHeadPose.scaledRotation(quaternion(1.2f), 0f)
        assertEqualsMatrix(VrMatrix4.identity(), actual, delta = 0f)
    }

    @Test
    fun negativeSensitivityAlsoLocksTheView() {
        val actual = VrHeadPose.scaledRotation(quaternion(1.2f), -3f)
        assertEqualsMatrix(VrMatrix4.identity(), actual, delta = 0f)
    }

    @Test
    fun unitSensitivityReproducesTheExactRotation() {
        val angle = 1.0f
        assertEqualsMatrix(
            VrMatrix4.rotation(0f, 1f, 0f, angle),
            VrHeadPose.scaledRotation(quaternion(angle), 1f),
        )
    }

    @Test
    fun sensitivityScalesTheRotationAngle() {
        val angle = 1.0f
        assertEqualsMatrix(
            VrMatrix4.rotation(0f, 1f, 0f, angle * 0.5f),
            VrHeadPose.scaledRotation(quaternion(angle), 0.5f),
        )
        assertEqualsMatrix(
            VrMatrix4.rotation(0f, 1f, 0f, angle * 1.5f),
            VrHeadPose.scaledRotation(quaternion(angle), 1.5f),
        )
    }

    @Test
    fun unnormalisedQuaternionIsNormalisedFirst() {
        val q = quaternion(0.8f).map { it * 5f }.toFloatArray()
        assertEqualsMatrix(
            VrMatrix4.rotation(0f, 1f, 0f, 0.8f),
            VrHeadPose.scaledRotation(q, 1f),
        )
    }

    @Test
    fun negatedQuaternionDescribesTheSameRotation() {
        val q = quaternion(0.8f)
        val negated = q.map { -it }.toFloatArray()
        assertEqualsMatrix(
            VrHeadPose.scaledRotation(q, 1f),
            VrHeadPose.scaledRotation(negated, 1f),
        )
    }

    @Test
    fun zeroQuaternionFallsBackToIdentity() {
        assertEqualsMatrix(
            VrMatrix4.identity(),
            VrHeadPose.scaledRotation(FloatArray(4), 1f),
            delta = 0f,
        )
    }

    @Test
    fun rejectsShortQuaternion() {
        assertThrows(IllegalArgumentException::class.java) {
            VrHeadPose.scaledRotation(FloatArray(3), 1f)
        }
    }

    // --- Zero-allocation render-loop path ----------------------------------------------------

    @Test
    fun scaledRotationIntoMatchesAllocatingVariantAndReusesTheArray() {
        val q = quaternion(0.7f)
        val out = FloatArray(VrMatrix4.SIZE)
        val returned = VrHeadPose.scaledRotationInto(out, q, 0.5f)
        assertEqualsMatrix(VrHeadPose.scaledRotation(q, 0.5f), out)
        assertTrue("scaledRotationInto must write into the caller's array", returned === out)
    }

    @Test
    fun viewRotationIsTheInverseOfScaledRotation() {
        val q = quaternion(0.9f)
        val view = VrHeadPose.viewRotationInto(FloatArray(VrMatrix4.SIZE), q, 1f)
        val forward = VrHeadPose.scaledRotation(q, 1f)
        assertEqualsMatrix(VrMatrix4.identity(), VrMatrix4.multiply(forward, view))
        assertEqualsMatrix(VrMatrix4.identity(), VrMatrix4.multiply(view, forward))
    }

    @Test
    fun viewRotationAtZeroSensitivityIsIdentity() {
        assertEqualsMatrix(
            VrMatrix4.identity(),
            VrHeadPose.viewRotationInto(FloatArray(VrMatrix4.SIZE), quaternion(1.2f), 0f),
        )
    }

    @Test
    fun scaledRotationIntoOverwritesStaleValuesOnIdentity() {
        val out = FloatArray(VrMatrix4.SIZE).also { it.fill(9f) }
        VrHeadPose.scaledRotationInto(out, floatArrayOf(0f, 0f, 0f, 1f), 1f)
        assertEqualsMatrix(VrMatrix4.identity(), out, delta = 0f)
    }
}
