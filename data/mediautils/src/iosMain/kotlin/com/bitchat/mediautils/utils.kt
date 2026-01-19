package com.bitchat.mediautils

import com.bitchat.mediautils.model.FilterOptions
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.refTo
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetImageGenerator
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.getBytes
import platform.Foundation.setValue
import platform.Foundation.writeToFile
import platform.Foundation.writeToURL
import platform.PhotosUI.PHPickerResult
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun loadMediaData(result: PHPickerResult, isVideo: Boolean): NSData? =
    suspendCancellableCoroutine { continuation ->
        val typeIdentifier = if (isVideo) "public.movie" else "public.image"
        result.itemProvider.loadDataRepresentationForTypeIdentifier(
            typeIdentifier = typeIdentifier,
        ) { nsData, error ->
            if (nsData != null) {
                continuation.resume(nsData)
            } else {
                continuation.resumeWithException(Exception(error?.description ?: "Unknown error"))
            }
        }
    }

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val byteArray = ByteArray(this.length.toInt())
    byteArray.usePinned {
        this.getBytes(it.addressOf(0), this.length)
    }
    return byteArray
}

@OptIn(ExperimentalForeignApi::class)
fun UIImage.toByteArray(compressionQuality: Double): ByteArray {
    val validCompressionQuality = compressionQuality.coerceIn(0.0, 1.0)
    val jpegData = UIImageJPEGRepresentation(this, validCompressionQuality)!!
    return ByteArray(jpegData.length.toInt()).apply {
        memcpy(this.refTo(0), jpegData.bytes, jpegData.length)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun UIImage.toByteArray(): ByteArray {
    val imageData = UIImagePNGRepresentation(this)
    requireNotNull(imageData) { "Failed to get PNG representation of UIImage." }

    val dataLength = imageData.length.toInt()
    val byteArray = ByteArray(dataLength)

    byteArray.usePinned { pinned ->
        imageData.getBytes(pinned.addressOf(0), length = dataLength.toULong())
    }

    return byteArray
}

@OptIn(ExperimentalForeignApi::class)
fun UIImage.fitInto(
    maxWidth: Int,
    maxHeight: Int,
    resizeThresholdBytes: Long,
    // @FloatRange(from = 0.0, to = 1.0)
    compressionQuality: Double,
    filterOptions: FilterOptions,
): UIImage {
    val imageData = this.toByteArray(compressionQuality)
    if (imageData.size > resizeThresholdBytes) {
        val originalWidth = this.size.useContents { width }
        val originalHeight = this.size.useContents { height }
        val originalRatio = originalWidth / originalHeight

        val targetRatio = maxWidth.toDouble() / maxHeight.toDouble()
        val scale =
            if (originalRatio > targetRatio) {
                maxWidth.toDouble() / originalWidth
            } else {
                maxHeight.toDouble() / originalHeight
            }

        val newWidth = originalWidth * scale
        val newHeight = originalHeight * scale

        val targetSize = CGSizeMake(newWidth, newHeight)
        val resizedImage = this.resize(targetSize)

        return applyFilterToUIImage(resizedImage, filterOptions)
    } else {
        return applyFilterToUIImage(this, filterOptions)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun UIImage.resize(targetSize: CValue<CGSize>): UIImage {
    UIGraphicsBeginImageContextWithOptions(targetSize, false, 0.0)
    this.drawInRect(
        CGRectMake(
            0.0,
            0.0,
            targetSize.useContents { width },
            targetSize.useContents { height },
        ),
    )
    val newImage = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()

    return newImage!!
}

@OptIn(ExperimentalForeignApi::class)
fun UIImage.logImageDimensions() {
    val width = size.useContents { this.width }
    val height = size.useContents { this.height }
    println("Image dimensions: width = $width, height = $height")
}

@OptIn(ExperimentalForeignApi::class)
fun applyFilterToUIImage(
    image: UIImage,
    filterOptions: FilterOptions,
): UIImage {
    val ciImage = CIImage.imageWithCGImage(image.CGImage)

    val filteredCIImage =
        when (filterOptions) {
            FilterOptions.GrayScale -> {
                CIFilter.filterWithName("CIPhotoEffectNoir")?.apply {
                    setValue(ciImage, forKey = "inputImage")
                }?.outputImage
            }
            FilterOptions.Sepia -> {
                CIFilter.filterWithName("CISepiaTone")?.apply {
                    setValue(ciImage, forKey = "inputImage")
                    setValue(0.8, forKey = "inputIntensity")
                }?.outputImage
            }
            FilterOptions.Invert -> {
                CIFilter.filterWithName("CIColorInvert")?.apply {
                    setValue(ciImage, forKey = "inputImage")
                }?.outputImage
            }
            FilterOptions.Default -> ciImage
        }

    val context = CIContext.contextWithOptions(null)
    return filteredCIImage?.let {
        val filteredCGImage = context.createCGImage(it, fromRect = it.extent())
        UIImage.imageWithCGImage(filteredCGImage)
    } ?: image
}

@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData {
    return this.usePinned { pinnedByteArray ->
        NSData.create(bytes = pinnedByteArray.addressOf(0), length = this.size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
fun generateThumbnailFromVideoBytes(
    videoBytes: ByteArray,
): ByteArray? {
    val tempDir = NSTemporaryDirectory()
    val tempFileName = "tempVideo_${NSUUID().UUIDString}.mov"
    val tempFileURL = NSURL.fileURLWithPath(tempDir).URLByAppendingPathComponent(tempFileName)

    val videoData = videoBytes.toNSData()
    if (!videoData.writeToURL(tempFileURL!!, true)) {
        println("Failed to write video data to temp file")
        return null
    }

    val asset = AVAsset.assetWithURL(tempFileURL)

    val imageGenerator = AVAssetImageGenerator(asset)
    imageGenerator.appliesPreferredTrackTransform = true

    val time = CMTimeMakeWithSeconds(1.0, 1)

    return try {
        val cgImage = imageGenerator.copyCGImageAtTime(time, null, null)
        val uiImage = UIImage.imageWithCGImage(cgImage)

        uiImage.let { image ->
            image.fitInto(
                1080,
                1350,
                1048576L, // 1MB
                1.0,
                FilterOptions.Default,
            ).let {
                it.toByteArray(.75)
            }
        }
    } catch (e: Exception) {
        println("Error generating thumbnail: ${e.message}")
        null
    }
}

fun saveImageToTemporaryDirectory(image: UIImage, fileName: String): NSURL? {
    val tempDir = NSTemporaryDirectory()
    val filePath = tempDir + fileName
    val imageData = image.toJPEGRepresentation(1.0)
    imageData?.let {
        val success = it.writeToFile(filePath, true)
        if (success) {
            return NSURL.fileURLWithPath(filePath)
        }
    }
    return null
}

fun UIImage.toJPEGRepresentation(compressionQuality: Double): NSData? {
    return UIImageJPEGRepresentation(this, compressionQuality)
}
