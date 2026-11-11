package com.evaristosuarez.miscalorias.recognition

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

class FoodImageLabeler(context: Context) {
    private val appContext = context.applicationContext
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    fun analyze(uri: Uri, onResult: (Result<List<ImageLabel>>) -> Unit) {
        try {
            val image = InputImage.fromFilePath(appContext, uri)
            labeler.process(image)
                .addOnSuccessListener { labels -> onResult(Result.success(labels)) }
                .addOnFailureListener { error -> onResult(Result.failure(error)) }
        } catch (error: Exception) {
            onResult(Result.failure(error))
        }
    }

    fun analyze(bitmap: Bitmap, onResult: (Result<List<ImageLabel>>) -> Unit) {
        labeler.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { labels -> onResult(Result.success(labels)) }
            .addOnFailureListener { error -> onResult(Result.failure(error)) }
    }

    fun close() = labeler.close()
}
