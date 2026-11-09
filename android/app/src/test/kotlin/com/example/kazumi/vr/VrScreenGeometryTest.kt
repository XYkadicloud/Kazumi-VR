package com.example.kazumi.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VrScreenGeometryTest {

    private fun mesh(
        scale: Float = 1f,
        curvature: Float = 0f,
        aspect: Float = 16f / 9f,
        segments: Int = 8,
    ): VrScreenGeometry.Mesh = VrScreenGeometry.build(
        settings = VrSettings(
            screenScale = scale,
            screenDistanceMeters = 2.2f,
            screenVerticalOffset = 0f,
            curvature = curvature,
        ),
        aspect = aspect,
        segments = segments,
    )

    private fun halfWidth(m: VrScreenGeometry.Mesh): Float = abs(m.positions[0])
    private fun halfHeight(m: VrScreenGeometry.Mesh): Float = abs(m.positions[1])

    @Test
    fun vertexAndUvCountsMatchTriangleStrip() {
        val m = mesh(segments = 8)
        assertEquals((8 + 1) * 2, m.vertexCount)
        assertEquals(m.vertexCount * 3, m.positions.size)
        assertEquals(m.vertexCount * 2, m.texCoords.size)
    }

    @Test
    fun segmentsIsClampedToAtLeastOne() {
        assertEquals(4, mesh(segments = 0).vertexCount)
        assertEquals(4, mesh(segments = -5).vertexCount)
    }

    /** The headline user requirement: screenScale really makes the flat stream window bigger. */
    @Test
    fun screenScaleMonotonicallyEnlargesWidthAndHeight() {
        val small = mesh(scale = 0.5f)
        val medium = mesh(scale = 1f)
        val large = mesh(scale = 3f)

        assertTrue(halfWidth(small) < halfWidth(medium))
        assertTrue(halfWidth(medium) < halfWidth(large))
        assertTrue(halfHeight(small) < halfHeight(medium))
        assertTrue(halfHeight(medium) < halfHeight(large))

        assertEquals(VrScreenGeometry.BASE_HALF_HEIGHT * 3f, halfHeight(large), 1e-5f)
    }

    @Test
    fun aspectChangesWidthButNotHeight() {
        val normal = mesh(aspect = 16f / 9f)
        val wide = mesh(aspect = 21f / 9f)
        assertTrue(halfWidth(wide) > halfWidth(normal))
        assertEquals(halfHeight(normal), halfHeight(wide), 1e-5f)
    }

    @Test
    fun invalidAspectFallsBackToDefault() {
        val bad = mesh(aspect = -1f)
        val normal = mesh(aspect = VrScreenGeometry.DEFAULT_ASPECT)
        assertEquals(halfWidth(normal), halfWidth(bad), 1e-5f)
    }

    @Test
    fun everyVertexStaysInFrontOfTheCameraEvenWhenCurved() {
        for (curvature in floatArrayOf(0f, 0.5f, 1f)) {
            val m = mesh(curvature = curvature, segments = 12)
            for (i in 0 until m.vertexCount) {
                assertTrue(
                    "curvature=$curvature vertex=$i z must be negative",
                    m.positions[i * 3 + 2] < 0f,
                )
            }
        }
    }

    @Test
    fun curvedScreenIsSymmetricAndWrapsEdgesTowardViewer() {
        val m = mesh(curvature = 1f, segments = 8)
        val lastTopX = m.positions[m.positions.size - 3]
        val lastTopZ = m.positions[m.positions.size - 1]
        val firstX = m.positions[0]
        val firstZ = m.positions[2]
        val centerZ = m.positions[8 * 3 + 2] // column 4 of 8 is the strip centre

        assertTrue(firstX < 0f)
        assertTrue(lastTopX > 0f)
        assertEquals(-firstX, lastTopX, 1e-4f)
        assertEquals(firstZ, lastTopZ, 1e-4f)
        assertTrue("edges should be closer than the centre", firstZ > centerZ)
    }

    @Test
    fun flatScreenHasConstantDepth() {
        val m = mesh(curvature = 0f, segments = 6)
        val expectedZ = m.positions[2]
        for (i in 0 until m.vertexCount) {
            assertEquals(expectedZ, m.positions[i * 3 + 2], 1e-6f)
        }
    }

    @Test
    fun uvCoordinatesSpanTheWholeTexture() {
        val m = mesh(segments = 4)
        assertEquals(0f, m.texCoords[0], 1e-6f)
        assertEquals(1f, m.texCoords[1], 1e-6f) // bottom row v = 1
        assertEquals(0f, m.texCoords[3], 1e-6f) // top row v = 0
        val lastBottomU = m.texCoords[(m.vertexCount - 2) * 2]
        assertEquals(1f, lastBottomU, 1e-6f)
    }

    @Test
    fun verticalOffsetShiftsBothRowsEqually() {
        val m = VrScreenGeometry.build(
            settings = VrSettings(screenVerticalOffset = 0.5f),
            segments = 2,
        )
        val h = VrScreenGeometry.halfHeight(1f)
        assertEquals(0.5f - h, m.positions[1], 1e-5f)
        assertEquals(0.5f + h, m.positions[4], 1e-5f)
    }

    @Test
    fun closerScreenKeepsTheSameGeometrySize() {
        val near = VrScreenGeometry.build(
            settings = VrSettings(screenDistanceMeters = 0.8f),
            segments = 4,
        )
        val far = VrScreenGeometry.build(
            settings = VrSettings(screenDistanceMeters = 6.0f),
            segments = 4,
        )
        assertEquals(halfWidth(near), halfWidth(far), 1e-5f)
        assertTrue(near.positions[2] > far.positions[2])
    }
}
