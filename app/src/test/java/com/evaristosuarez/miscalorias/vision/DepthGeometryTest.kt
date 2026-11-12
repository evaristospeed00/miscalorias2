package com.evaristosuarez.miscalorias.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepthGeometryTest {
    private val intrinsics = CameraIntrinsics(
        imageWidth = 100,
        imageHeight = 2,
        focalLengthXPixels = 500.0,
        focalLengthYPixels = 500.0,
    )

    @Test
    fun integratesMetricDepthOverFoodMaskAndAppliesDensity() {
        val result = DepthGeometry.estimate(
            mask = BooleanArray(100) { true },
            depthMeters = FloatArray(100) { 0.5f },
            depthWidth = 100,
            depthHeight = 1,
            intrinsics = intrinsics.copy(imageHeight = 1),
            supportPlaneDepthMeters = 0.6,
            density = FoodDensity(1.0),
        )

        assertEquals(10.0, result?.volumeCubicCentimeters ?: 0.0, 1e-6)
        assertEquals(10.0, result?.massGrams ?: 0.0, 1e-6)
        assertEquals(1.0, result?.validMaskPixelFraction ?: 0.0, 0.0)
    }

    @Test
    fun scalesMassByFoodSpecificDensity() {
        val result = DepthGeometry.estimate(
            mask = BooleanArray(100) { true },
            depthMeters = FloatArray(100) { 0.5f },
            depthWidth = 100,
            depthHeight = 1,
            intrinsics = intrinsics.copy(imageHeight = 1),
            supportPlaneDepthMeters = 0.6,
            density = FoodDensity(1.05),
        )

        assertEquals(10.5, result?.massGrams ?: 0.0, 1e-6)
    }

    @Test
    fun rejectsInsufficientMetricDepthCoverage() {
        val result = DepthGeometry.estimate(
            mask = BooleanArray(10) { true },
            depthMeters = FloatArray(10) { if (it == 0) 0.5f else 0f },
            depthWidth = 10,
            depthHeight = 1,
            intrinsics = intrinsics.copy(imageWidth = 10, imageHeight = 1),
            supportPlaneDepthMeters = 0.6,
            density = FoodDensity(1.0),
        )

        assertNull(result)
    }

    @Test
    fun ignoresPixelsOutsideTheFoodMaskAndPixelsBelowSupportPlane() {
        val result = DepthGeometry.estimate(
            mask = BooleanArray(3) { it != 1 },
            depthMeters = floatArrayOf(0.5f, 0.5f, 0.7f),
            depthWidth = 3,
            depthHeight = 1,
            intrinsics = intrinsics.copy(imageWidth = 3, imageHeight = 1),
            supportPlaneDepthMeters = 0.6,
            density = FoodDensity(1.0),
            minimumValidPixelFraction = 0.5,
        )

        assertEquals(0.5, result?.validMaskPixelFraction ?: 0.0, 0.0)
    }

    @Test
    fun estimatesSupportPlaneFromAdjacentValidDepthSamples() {
        val mask = BooleanArray(25)
        val depths = FloatArray(25) { 0.6f }
        for (y in 1..3) {
            for (x in 1..3) {
                val index = y * 5 + x
                mask[index] = true
                depths[index] = 0.5f
            }
        }

        val result = DepthGeometry.estimateUsingAdjacentSupportPlane(
            mask = mask,
            depthMeters = depths,
            depthWidth = 5,
            depthHeight = 5,
            intrinsics = CameraIntrinsics(5, 5, 500.0, 500.0),
            density = FoodDensity(1.0),
            supportRadiusPixels = 1,
        )

        assertEquals(0.9, result?.volumeCubicCentimeters ?: 0.0, 1e-6)
        assertEquals(1.0, result?.validMaskPixelFraction ?: 0.0, 0.0)
    }

    @Test
    fun rejectsSupportPlaneWithoutEnoughSurroundingDepth() {
        val mask = BooleanArray(25) { true }

        assertNull(
            DepthGeometry.estimateUsingAdjacentSupportPlane(
                mask = mask,
                depthMeters = FloatArray(25) { 0.5f },
                depthWidth = 5,
                depthHeight = 5,
                intrinsics = CameraIntrinsics(5, 5, 500.0, 500.0),
                density = FoodDensity(1.0),
            ),
        )
    }

    @Test
    fun rejectsInvalidDepthDimensionsInPayload() {
        val payload = DepthCapturePayload(
            rgbFile = java.io.File("dummy.jpg"),
            depthMillimetersFile = java.io.File("dummy.raw"),
            depthWidth = 0,
            depthHeight = 10,
            cameraImageWidth = 100,
            cameraImageHeight = 100,
            focalLengthXPixels = 500f,
            focalLengthYPixels = 500f,
            principalPointXPixels = 50f,
            principalPointYPixels = 50f,
            cameraPose = FloatArray(16),
            frameTimestampNanos = 0L,
            rgbTimestampNanos = 0L,
            depthTimestampNanos = 0L,
        )
        assertNull(payload.readDepthMeters())
    }
}
