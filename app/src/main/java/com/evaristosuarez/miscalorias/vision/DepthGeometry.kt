package com.evaristosuarez.miscalorias.vision

data class CameraIntrinsics(
    val imageWidth: Int,
    val imageHeight: Int,
    val focalLengthXPixels: Double,
    val focalLengthYPixels: Double,
)

data class FoodDensity(
    val gramsPerCubicCentimeter: Double,
)

data class DepthVolumeEstimate(
    val volumeCubicCentimeters: Double,
    val massGrams: Double,
    val validMaskPixelFraction: Double,
)

object DepthGeometry {
    fun estimateUsingAdjacentSupportPlane(
        mask: BooleanArray,
        depthMeters: FloatArray,
        depthWidth: Int,
        depthHeight: Int,
        intrinsics: CameraIntrinsics,
        density: FoodDensity,
        supportRadiusPixels: Int = 3,
        minimumSupportSamples: Int = 8,
        minimumValidPixelFraction: Double = 0.6,
        maximumFoodHeightMeters: Double = 0.35,
    ): DepthVolumeEstimate? {
        require(depthWidth > 0 && depthHeight > 0)
        require(mask.size == depthWidth * depthHeight)
        require(depthMeters.size == mask.size)
        require(supportRadiusPixels > 0)
        require(minimumSupportSamples > 0)

        val supportIndices = HashSet<Int>()
        mask.forEachIndexed { index, isFood ->
            if (!isFood) return@forEachIndexed
            val x = index % depthWidth
            val y = index / depthWidth
            for (offsetY in -supportRadiusPixels..supportRadiusPixels) {
                for (offsetX in -supportRadiusPixels..supportRadiusPixels) {
                    val sampleX = x + offsetX
                    val sampleY = y + offsetY
                    if (sampleX in 0 until depthWidth && sampleY in 0 until depthHeight) {
                        val sampleIndex = sampleY * depthWidth + sampleX
                        if (!mask[sampleIndex]) supportIndices.add(sampleIndex)
                    }
                }
            }
        }

        val supportDepths = supportIndices
            .asSequence()
            .map { depthMeters[it].toDouble() }
            .filter { it.isFinite() && it > 0.0 }
            .sorted()
            .toList()
        if (supportDepths.size < minimumSupportSamples) return null

        val middle = supportDepths.size / 2
        val supportDepth = if (supportDepths.size % 2 == 0) {
            (supportDepths[middle - 1] + supportDepths[middle]) / 2.0
        } else {
            supportDepths[middle]
        }
        return estimate(
            mask = mask,
            depthMeters = depthMeters,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            intrinsics = intrinsics,
            supportPlaneDepthMeters = supportDepth,
            density = density,
            minimumValidPixelFraction = minimumValidPixelFraction,
            maximumFoodHeightMeters = maximumFoodHeightMeters,
        )
    }

    /**
     * Integrates food height above a locally measured, approximately fronto-parallel support plane.
     * The mask and depth map must share pixel coordinates; depth values are metric meters.
     */
    fun estimate(
        mask: BooleanArray,
        depthMeters: FloatArray,
        depthWidth: Int,
        depthHeight: Int,
        intrinsics: CameraIntrinsics,
        supportPlaneDepthMeters: Double,
        density: FoodDensity,
        minimumValidPixelFraction: Double = 0.6,
        maximumFoodHeightMeters: Double = 0.35,
    ): DepthVolumeEstimate? {
        require(depthWidth > 0 && depthHeight > 0)
        require(mask.size == depthWidth * depthHeight)
        require(depthMeters.size == mask.size)
        require(intrinsics.imageWidth > 0 && intrinsics.imageHeight > 0)
        require(intrinsics.focalLengthXPixels > 0.0 && intrinsics.focalLengthYPixels > 0.0)
        require(supportPlaneDepthMeters.isFinite() && supportPlaneDepthMeters > 0.0)
        require(density.gramsPerCubicCentimeter.isFinite() && density.gramsPerCubicCentimeter > 0.0)
        require(minimumValidPixelFraction in 0.0..1.0)
        require(maximumFoodHeightMeters.isFinite() && maximumFoodHeightMeters > 0.0)

        val maskPixels = mask.count { it }
        if (maskPixels == 0) return null

        val focalX = intrinsics.focalLengthXPixels * depthWidth / intrinsics.imageWidth
        val focalY = intrinsics.focalLengthYPixels * depthHeight / intrinsics.imageHeight
        var validPixels = 0
        var volumeCubicMeters = 0.0

        mask.forEachIndexed { index, isFood ->
            if (!isFood) return@forEachIndexed
            val surfaceDepth = depthMeters[index].toDouble()
            val height = supportPlaneDepthMeters - surfaceDepth
            if (!surfaceDepth.isFinite() || surfaceDepth <= 0.0 ||
                height <= 0.0 || height > maximumFoodHeightMeters
            ) {
                return@forEachIndexed
            }

            val pixelAreaSquareMeters = surfaceDepth * surfaceDepth / (focalX * focalY)
            volumeCubicMeters += height * pixelAreaSquareMeters
            validPixels++
        }

        val validFraction = validPixels.toDouble() / maskPixels
        if (validPixels == 0 || validFraction < minimumValidPixelFraction) return null

        val volumeCubicCentimeters = volumeCubicMeters * 1_000_000.0
        return DepthVolumeEstimate(
            volumeCubicCentimeters = volumeCubicCentimeters,
            massGrams = volumeCubicCentimeters * density.gramsPerCubicCentimeter,
            validMaskPixelFraction = validFraction,
        )
    }

}
