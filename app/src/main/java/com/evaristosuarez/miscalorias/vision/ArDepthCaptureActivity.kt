package com.evaristosuarez.miscalorias.vision

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.YuvImage
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArDepthCaptureActivity : Activity() {
    private lateinit var preview: GLSurfaceView
    private lateinit var message: TextView
    private var session: Session? = null
    private var depthSupported = false
    private var installRequested = false

    private val renderer = CameraBackgroundRenderer(
        sessionProvider = { session },
        cacheDirectory = { cacheDir },
        displayRotation = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.rotation ?: android.view.Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
        },
        isDepthSupported = { depthSupported },
        onCaptured = ::finishCapture,
        onError = ::showCaptureError,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        message = TextView(this).apply {
            text = "Mueve el teléfono lentamente para obtener profundidad; después captura."
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 14f
            setPadding(20, 16, 20, 16)
        }
        val capture = Button(this).apply {
            text = "Capturar foto y profundidad"
            setOnClickListener {
                renderer.requestCapture()
                message.text = "Capturando el siguiente fotograma..."
            }
        }
        val root = FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(
                message,
                FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP),
            )
            addView(
                capture,
                FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
            )
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        startArSession()
    }

    private fun startArSession() {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            if (session == null) {
                session = Session(this).also { arSession ->
                    val config = arSession.config
                    depthSupported = arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    config.depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                    arSession.configure(config)
                }
            }
            session?.resume()
            preview.onResume()
            message.text = if (depthSupported) {
                "Profundidad métrica disponible. Mueve el teléfono lentamente y captura."
            } else {
                "Profundidad no disponible en este dispositivo. Se conservará solo la foto RGB."
            }
        } catch (error: Exception) {
            fallbackToSystemCamera()
        }
    }

    override fun onPause() {
        preview.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startArSession()
            } else {
                fallbackToSystemCamera()
            }
        }
    }

    private fun fallbackToSystemCamera() {
        setResult(
            RESULT_CANCELED,
            android.content.Intent().putExtra(DepthCapturePayload.EXTRA_FALLBACK_TO_SYSTEM_CAMERA, true),
        )
        finish()
    }

    private fun finishCapture(payload: DepthCapturePayload) {
        runOnUiThread {
            setResult(RESULT_OK, payload.toIntent())
            finish()
        }
    }

    private fun showCaptureError(error: Exception) {
        runOnUiThread {
            message.text = "No se pudo capturar: ${error.localizedMessage ?: "error de cámara"}"
            renderer.cancelCapture()
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 41
    }
}

private class CameraBackgroundRenderer(
    private val sessionProvider: () -> Session?,
    private val cacheDirectory: () -> File,
    private val displayRotation: () -> Int,
    private val isDepthSupported: () -> Boolean,
    private val onCaptured: (DepthCapturePayload) -> Unit,
    private val onError: (Exception) -> Unit,
) : GLSurfaceView.Renderer {
    private val captureRequested = AtomicBoolean(false)
    private var cameraTexture = 0
    private var textureSession: Session? = null
    private var program = 0
    private var positionAttribute = 0
    private var textureAttribute = 0
    private var textureUniform = 0
    private val vertices = directFloatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val textureCoordinates = directFloatBuffer(FloatArray(8))

    fun requestCapture() {
        captureRequested.set(true)
    }

    fun cancelCapture() {
        captureRequested.set(false)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        textureSession = null
        cameraTexture = IntArray(1).also {
            GLES20.glGenTextures(1, it, 0)
        }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "aPosition")
        textureAttribute = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "sTexture")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        sessionProvider()?.setDisplayGeometry(displayRotation(), width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val session = sessionProvider() ?: return
        try {
            if (textureSession !== session) {
                session.setCameraTextureNames(intArrayOf(cameraTexture))
                textureSession = session
            }
            val frame = session.update()
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                vertices,
                Coordinates2d.TEXTURE_NORMALIZED,
                textureCoordinates,
            )
            drawBackground()
            if (captureRequested.compareAndSet(true, false)) {
                capture(frame)
            }
        } catch (error: CameraNotAvailableException) {
            onError(error)
        } catch (error: Exception) {
            onError(error)
        }
    }

    private fun drawBackground() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        vertices.position(0)
        textureCoordinates.position(0)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(textureAttribute)
        GLES20.glVertexAttribPointer(textureAttribute, 2, GLES20.GL_FLOAT, false, 0, textureCoordinates)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(textureAttribute)
    }

    private fun capture(frame: Frame) {
        try {
            frame.acquireCameraImage().use { cameraImage ->
                val rgbFile = File.createTempFile("meal-", ".jpg", cacheDirectory())
                FileOutputStream(rgbFile).use { output ->
                    val yuv = cameraImage.toNv21()
                    check(YuvImage(yuv, android.graphics.ImageFormat.NV21, cameraImage.width, cameraImage.height, null)
                        .compressToJpeg(Rect(0, 0, cameraImage.width, cameraImage.height), 92, output)) {
                        "Could not encode camera frame."
                    }
                }

                val camera = frame.camera
                val intrinsics = camera.imageIntrinsics
                val focal = intrinsics.focalLength
                val principal = intrinsics.principalPoint
                val dimensions = intrinsics.imageDimensions
                val pose = FloatArray(16)
                camera.pose.toMatrix(pose, 0)
                val depth = acquireDepth(frame, cacheDirectory())
                onCaptured(
                    DepthCapturePayload(
                        rgbFile = rgbFile,
                        depthMillimetersFile = depth?.file,
                        depthWidth = depth?.width ?: 0,
                        depthHeight = depth?.height ?: 0,
                        cameraImageWidth = dimensions[0],
                        cameraImageHeight = dimensions[1],
                        focalLengthXPixels = focal[0],
                        focalLengthYPixels = focal[1],
                        principalPointXPixels = principal[0],
                        principalPointYPixels = principal[1],
                        cameraPose = pose,
                        frameTimestampNanos = frame.timestamp,
                        rgbTimestampNanos = cameraImage.timestamp,
                        depthTimestampNanos = depth?.timestampNanos,
                    ),
                )
            }
        } catch (error: Exception) {
            onError(error)
        }
    }

    private fun acquireDepth(frame: Frame, cacheDir: File): AcquiredDepth? {
        if (!isDepthSupported()) return null
        return try {
            frame.acquireDepthImage16Bits().use { image ->
                val plane = image.planes[0]
                val source = plane.buffer.order(ByteOrder.nativeOrder())
                val origin = source.position()
                val output = ByteBuffer.allocate(image.width * image.height * Short.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        val offset = origin + y * plane.rowStride + x * plane.pixelStride
                        output.putShort(source.getShort(offset))
                    }
                }
                val file = File.createTempFile("depth-", ".raw", cacheDir)
                FileOutputStream(file).use { it.write(output.array()) }
                AcquiredDepth(file, image.width, image.height, image.timestamp)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { programId ->
            GLES20.glAttachShader(programId, vertex)
            GLES20.glAttachShader(programId, fragment)
            GLES20.glLinkProgram(programId)
            val status = IntArray(1)
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(programId) }
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        }

    private fun directFloatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }

    private fun android.media.Image.toNv21(): ByteArray {
        require(width % 2 == 0 && height % 2 == 0) { "Unsupported odd-sized YUV camera image." }
        val output = ByteArray(width * height * 3 / 2)
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        var index = 0
        for (row in 0 until height) {
            val rowStart = yPlane.buffer.position() + row * yPlane.rowStride
            for (column in 0 until width) {
                output[index++] = yPlane.buffer.get(rowStart + column * yPlane.pixelStride)
            }
        }
        for (row in 0 until height / 2) {
            for (column in 0 until width / 2) {
                output[index++] = vPlane.buffer.get(vPlane.buffer.position() + row * vPlane.rowStride + column * vPlane.pixelStride)
                output[index++] = uPlane.buffer.get(uPlane.buffer.position() + row * uPlane.rowStride + column * uPlane.pixelStride)
            }
        }
        return output
    }

    private data class AcquiredDepth(
        val file: File,
        val width: Int,
        val height: Int,
        val timestampNanos: Long,
    )

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
