package com.bitchat.mediautils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

fun getVideoThumbnail(context: Context, videoUri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, videoUri)
        retriever.getFrameAtTime(1000000)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        retriever.release()
    }
}

/**
 * Copy content from a content:// URI to a local file.
 * This is necessary because content:// URIs are only valid on the local device
 * and cannot be shared with other devices over the mesh network.
 *
 * @param context Android context
 * @param contentUri The content:// URI to copy from
 * @param subDir Optional subdirectory under the app's files dir (e.g., "images/outgoing")
 * @return The path to the local file, or null if copying failed
 */
fun copyContentToLocalFile(
    context: Context,
    contentUri: String,
    subDir: String = "images/outgoing"
): String? {
    return try {
        val uri = Uri.parse(contentUri)
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return null

        // Create output directory
        val outDir = File(context.filesDir, subDir)
        if (!outDir.exists()) {
            outDir.mkdirs()
        }

        // Generate unique filename with extension based on MIME type
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val extension = when {
            mimeType.contains("png") -> ".png"
            mimeType.contains("gif") -> ".gif"
            mimeType.contains("webp") -> ".webp"
            else -> ".jpg"
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(outDir, "img_$timestamp$extension")

        // Copy the content
        inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }

        outputFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
