package com.bitchat.mediautils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Convert a media URL/URI to a local file path.
 * On Desktop, file paths are already local file paths.
 */
actual suspend fun resolveMediaToLocalPath(mediaUrl: String): String? {
    // On Desktop, file pickers return local file paths
    // Just return as-is
    return if (mediaUrl.startsWith("file://")) {
        mediaUrl.removePrefix("file://")
    } else {
        mediaUrl
    }
}

/**
 * Read file bytes from a local file path.
 */
actual suspend fun readFileBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val file = File(path)
        if (file.exists() && file.isFile) {
            file.readBytes()
        } else {
            println("MediaFileUtils Desktop: File does not exist or is not a file: $path")
            null
        }
    } catch (e: Exception) {
        println("MediaFileUtils Desktop: Error reading file: ${e.message}")
        e.printStackTrace()
        null
    }
}

/**
 * Get the MIME type for a file based on its path/extension.
 */
actual fun getMimeType(path: String): String {
    val extension = path.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "aac" -> "audio/aac"
        else -> {
            try {
                Files.probeContentType(Paths.get(path)) ?: "application/octet-stream"
            } catch (e: Exception) {
                "application/octet-stream"
            }
        }
    }
}

/**
 * Save file bytes to local storage in a specified subdirectory.
 */
actual suspend fun saveFileToLocal(bytes: ByteArray, fileName: String, subDir: String): String? = withContext(Dispatchers.IO) {
    try {
        // Use user home directory for desktop
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".bitchat")
        val outDir = File(appDir, subDir)

        if (!outDir.exists()) {
            outDir.mkdirs()
        }

        val outputFile = File(outDir, fileName)
        outputFile.writeBytes(bytes)

        println("MediaFileUtils Desktop: Saved file to ${outputFile.absolutePath}")
        outputFile.absolutePath
    } catch (e: Exception) {
        println("MediaFileUtils Desktop: Error saving file: ${e.message}")
        e.printStackTrace()
        null
    }
}

/**
 * Compress an image file for BLE transfer.
 * Desktop implementation - uses Java ImageIO for compression.
 */
actual suspend fun compressImageForTransfer(path: String, maxSizeBytes: Int): PreparedImageForTransfer? = withContext(Dispatchers.IO) {
    try {
        val file = File(path)
        if (!file.exists()) {
            println("MediaFileUtils Desktop: Image file not found: $path")
            return@withContext null
        }

        val originalMime = getMimeType(path)
        val originalFileName = getFileName(path)

        // First, check if original is already small enough
        val originalBytes = file.readBytes()
        if (originalBytes.size <= maxSizeBytes) {
            println("MediaFileUtils Desktop: Image already under size limit (${originalBytes.size} bytes)")
            val detectedFormat = detectImageFormat(originalBytes)
            val normalizedName = normalizeImageFileName(originalFileName, detectedFormat)
            val normalizedMime = when (detectedFormat) {
                ImageFormat.PNG -> "image/png"
                ImageFormat.JPEG -> "image/jpeg"
                ImageFormat.WEBP -> "image/webp"
                ImageFormat.GIF -> "image/gif"
                ImageFormat.UNKNOWN -> originalMime
            }
            return@withContext PreparedImageForTransfer(
                bytes = originalBytes,
                mimeType = normalizedMime,
                fileName = normalizedName
            )
        }

        println("MediaFileUtils Desktop: Compressing image from ${originalBytes.size} bytes to under $maxSizeBytes bytes")

        // Read the image
        val originalImage = ImageIO.read(file) ?: return@withContext null
        val originalWidth = originalImage.width
        val originalHeight = originalImage.height

        println("MediaFileUtils Desktop: Original dimensions: ${originalWidth}x${originalHeight}")

        // Calculate target dimensions (max 800x800)
        var targetWidth = originalWidth
        var targetHeight = originalHeight
        while (targetWidth > 800 || targetHeight > 800) {
            targetWidth /= 2
            targetHeight /= 2
        }

        // Scale down if needed
        var image = if (targetWidth != originalWidth || targetHeight != originalHeight) {
            val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
            val g = scaled.createGraphics()
            g.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null)
            g.dispose()
            println("MediaFileUtils Desktop: Scaled to ${targetWidth}x${targetHeight}")
            scaled
        } else {
            // Convert to RGB if needed (for JPEG)
            if (originalImage.type != BufferedImage.TYPE_INT_RGB) {
                val rgb = BufferedImage(originalWidth, originalHeight, BufferedImage.TYPE_INT_RGB)
                val g = rgb.createGraphics()
                g.drawImage(originalImage, 0, 0, null)
                g.dispose()
                rgb
            } else {
                originalImage
            }
        }

        // Try progressively lower quality levels
        val qualities = listOf(0.85f, 0.70f, 0.55f, 0.40f, 0.25f, 0.15f)
        for (quality in qualities) {
            val compressed = compressToJpeg(image, quality)
            println("MediaFileUtils Desktop: Quality ${(quality * 100).toInt()}%: ${compressed.size} bytes")

            if (compressed.size <= maxSizeBytes) {
                println("MediaFileUtils Desktop: Successfully compressed to ${compressed.size} bytes at quality ${(quality * 100).toInt()}%")
                val targetName = normalizeImageFileName(originalFileName, ImageFormat.JPEG)
                return@withContext PreparedImageForTransfer(
                    bytes = compressed,
                    mimeType = "image/jpeg",
                    fileName = targetName
                )
            }
        }

        // If still too large, try scaling down more aggressively
        val scaledWidth = image.width / 2
        val scaledHeight = image.height / 2
        val smallerImage = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
        val g = smallerImage.createGraphics()
        g.drawImage(image, 0, 0, scaledWidth, scaledHeight, null)
        g.dispose()
        image = smallerImage

        println("MediaFileUtils Desktop: Scaled down to ${scaledWidth}x${scaledHeight}, trying again")

        for (quality in qualities) {
            val compressed = compressToJpeg(image, quality)
            println("MediaFileUtils Desktop: Scaled quality ${(quality * 100).toInt()}%: ${compressed.size} bytes")

            if (compressed.size <= maxSizeBytes) {
                println("MediaFileUtils Desktop: Successfully compressed scaled image to ${compressed.size} bytes")
                val targetName = normalizeImageFileName(originalFileName, ImageFormat.JPEG)
                return@withContext PreparedImageForTransfer(
                    bytes = compressed,
                    mimeType = "image/jpeg",
                    fileName = targetName
                )
            }
        }

        println("MediaFileUtils Desktop: Could not compress image to under $maxSizeBytes bytes")
        null
    } catch (e: Exception) {
        println("MediaFileUtils Desktop: Error compressing image: ${e.message}")
        e.printStackTrace()
        null
    }
}

/**
 * Compress a BufferedImage to JPEG with specified quality.
 */
private fun compressToJpeg(image: BufferedImage, quality: Float): ByteArray {
    val outputStream = ByteArrayOutputStream()
    val jpegWriter = ImageIO.getImageWritersByFormatName("jpeg").next()
    val writeParam = jpegWriter.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }
    val imageOutputStream = ImageIO.createImageOutputStream(outputStream)
    jpegWriter.output = imageOutputStream
    jpegWriter.write(null, IIOImage(image, null, null), writeParam)
    jpegWriter.dispose()
    imageOutputStream.close()
    return outputStream.toByteArray()
}
