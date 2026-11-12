package com.evaristosuarez.miscalorias.vision

import android.content.Intent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DepthCapturePayload(
    val rgbFile: File,
    val depthMillimetersFile: File?,
    val depthWidth: Int,
    val depthHeight: Int,
    val cameraImageWidth: Int,
    val cameraImageHeight: Int,
    val focalLengthXPixels: Float,
    val focalLengthYPixels: Float,
    val principalPointXPixels: Float,
    val principalPointYPixels: Float,
    val cameraPose: FloatArray,
    val frameTimestampNanos: Long,
    val rgbTimestampNanos: Long,
    val depthTimestampNanos: Long?,
) {
    fun deleteFiles() {
        rgbFile.delete()
        depthMillimetersFile?.delete()
    }

    fun readDepthMeters(): FloatArray? {
        val file = depthMillimetersFile ?: return null
        if (depthWidth <= 0 || depthHeight <= 0) return null
        val expectedBytes = depthWidth * depthHeight * 2
        require(file.length() == expectedBytes.toLong()) { "Depth payload size does not match its dimensions." }
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(depthWidth * depthHeight) {
            val millimeters = buffer.short.toInt() and 0xffff
            if (millimeters == 0) 0f else millimeters / 1000f
        }
    }

    fun toIntent(): Intent = Intent().apply {
        putExtra(EXTRA_RGB_FILE, rgbFile.absolutePath)
        putExtra(EXTRA_DEPTH_FILE, depthMillimetersFile?.absolutePath)
        putExtra(EXTRA_DEPTH_WIDTH, depthWidth)
        putExtra(EXTRA_DEPTH_HEIGHT, depthHeight)
        putExtra(EXTRA_IMAGE_WIDTH, cameraImageWidth)
        putExtra(EXTRA_IMAGE_HEIGHT, cameraImageHeight)
        putExtra(EXTRA_FOCAL_X, focalLengthXPixels)
        putExtra(EXTRA_FOCAL_Y, focalLengthYPixels)
        putExtra(EXTRA_PRINCIPAL_X, principalPointXPixels)
        putExtra(EXTRA_PRINCIPAL_Y, principalPointYPixels)
        putExtra(EXTRA_CAMERA_POSE, cameraPose)
        putExtra(EXTRA_FRAME_TIMESTAMP, frameTimestampNanos)
        putExtra(EXTRA_RGB_TIMESTAMP, rgbTimestampNanos)
        depthTimestampNanos?.let { putExtra(EXTRA_DEPTH_TIMESTAMP, it) }
    }

    companion object {
        const val EXTRA_RGB_FILE = "com.evaristosuarez.miscalorias.vision.RGB_FILE"
        const val EXTRA_DEPTH_FILE = "com.evaristosuarez.miscalorias.vision.DEPTH_FILE"
        const val EXTRA_DEPTH_WIDTH = "com.evaristosuarez.miscalorias.vision.DEPTH_WIDTH"
        const val EXTRA_DEPTH_HEIGHT = "com.evaristosuarez.miscalorias.vision.DEPTH_HEIGHT"
        const val EXTRA_IMAGE_WIDTH = "com.evaristosuarez.miscalorias.vision.IMAGE_WIDTH"
        const val EXTRA_IMAGE_HEIGHT = "com.evaristosuarez.miscalorias.vision.IMAGE_HEIGHT"
        const val EXTRA_FOCAL_X = "com.evaristosuarez.miscalorias.vision.FOCAL_X"
        const val EXTRA_FOCAL_Y = "com.evaristosuarez.miscalorias.vision.FOCAL_Y"
        const val EXTRA_PRINCIPAL_X = "com.evaristosuarez.miscalorias.vision.PRINCIPAL_X"
        const val EXTRA_PRINCIPAL_Y = "com.evaristosuarez.miscalorias.vision.PRINCIPAL_Y"
        const val EXTRA_CAMERA_POSE = "com.evaristosuarez.miscalorias.vision.CAMERA_POSE"
        const val EXTRA_FRAME_TIMESTAMP = "com.evaristosuarez.miscalorias.vision.FRAME_TIMESTAMP"
        const val EXTRA_RGB_TIMESTAMP = "com.evaristosuarez.miscalorias.vision.RGB_TIMESTAMP"
        const val EXTRA_DEPTH_TIMESTAMP = "com.evaristosuarez.miscalorias.vision.DEPTH_TIMESTAMP"
        const val EXTRA_FALLBACK_TO_SYSTEM_CAMERA =
            "com.evaristosuarez.miscalorias.vision.FALLBACK_TO_SYSTEM_CAMERA"

        fun fromIntent(intent: Intent): DepthCapturePayload? {
            val rgbPath = intent.getStringExtra(EXTRA_RGB_FILE) ?: return null
            val depthPath = intent.getStringExtra(EXTRA_DEPTH_FILE)
            val pose = intent.getFloatArrayExtra(EXTRA_CAMERA_POSE) ?: return null
            if (pose.size != 16) return null
            return DepthCapturePayload(
                rgbFile = File(rgbPath),
                depthMillimetersFile = depthPath?.let(::File),
                depthWidth = intent.getIntExtra(EXTRA_DEPTH_WIDTH, 0),
                depthHeight = intent.getIntExtra(EXTRA_DEPTH_HEIGHT, 0),
                cameraImageWidth = intent.getIntExtra(EXTRA_IMAGE_WIDTH, 0),
                cameraImageHeight = intent.getIntExtra(EXTRA_IMAGE_HEIGHT, 0),
                focalLengthXPixels = intent.getFloatExtra(EXTRA_FOCAL_X, 0f),
                focalLengthYPixels = intent.getFloatExtra(EXTRA_FOCAL_Y, 0f),
                principalPointXPixels = intent.getFloatExtra(EXTRA_PRINCIPAL_X, 0f),
                principalPointYPixels = intent.getFloatExtra(EXTRA_PRINCIPAL_Y, 0f),
                cameraPose = pose,
                frameTimestampNanos = intent.getLongExtra(EXTRA_FRAME_TIMESTAMP, 0L),
                rgbTimestampNanos = intent.getLongExtra(EXTRA_RGB_TIMESTAMP, 0L),
                depthTimestampNanos = if (intent.hasExtra(EXTRA_DEPTH_TIMESTAMP)) {
                    intent.getLongExtra(EXTRA_DEPTH_TIMESTAMP, 0L)
                } else {
                    null
                },
            )
        }
    }
}
