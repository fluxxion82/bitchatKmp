package com.bitchat.mediautils

import com.bitchat.mediautils.model.ResizeOptions
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetExportPresetHighestQuality
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVMutableVideoCompositionInstruction
import platform.AVFoundation.AVMutableVideoCompositionLayerInstruction
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.naturalSize
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.tracksWithMediaType
import platform.AVFoundation.videoComposition
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970

actual suspend fun resizeVideo(inputVideoPath: String): ByteArray {
    val resizeOptions = ResizeOptions()
    val mediaPathUrl = NSURL.fileURLWithPath(inputVideoPath)

    val tempDirectory = NSTemporaryDirectory()
    val outputFileName = "video_resized_${NSDate().timeIntervalSince1970}.mp4"
    val outputPath = tempDirectory.plus("/$outputFileName")
    val outputUrl = NSURL.fileURLWithPath(outputPath)

    val success = resizeVideoInternal(
        mediaPathUrl,
        outputUrl,
        resizeOptions.width,
        resizeOptions.height
    )

    if (success) {
        val finalSize = getVideoSize(outputUrl)
        println("Processed video dimensions: width=${finalSize?.width}, height=${finalSize?.height}")
    }

    return if (success) {
        NSFileManager.defaultManager.contentsAtPath(outputPath)?.toByteArray() ?: ByteArray(0)
    } else {
        ByteArray(0)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun getVideoSize(url: NSURL): CGSize? {
    memScoped {
        val asset = AVAsset.assetWithURL(url)
        val track = asset.tracksWithMediaType(AVMediaTypeVideo)[0] as AVAssetTrack
        return track.naturalSize.useContents { this }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun resizeVideoInternal(inputUrl: NSURL, outputUrl: NSURL, targetWidth: Int, targetHeight: Int): Boolean {
    val asset = AVAsset.assetWithURL(inputUrl)
    val videoTracks = asset.tracksWithMediaType(AVMediaTypeVideo)
    val audioTracks = asset.tracksWithMediaType(AVMediaTypeAudio)
    if (videoTracks.isEmpty()) return false

    val assetTrack = videoTracks[0] as AVAssetTrack

    val composition = AVMutableComposition()
    val compositionTrack = composition.addMutableTrackWithMediaType(
        AVMediaTypeVideo,
        assetTrack.trackID
    ) ?: return false

    if (audioTracks.isNotEmpty()) {
        val audioTrack = audioTracks[0] as AVAssetTrack
        val compositionAudioTrack = composition.addMutableTrackWithMediaType(
            AVMediaTypeAudio,
            audioTrack.trackID
        )

        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val timeRange = CMTimeRangeMake(CMTimeMake(0, 1), asset.duration)
            compositionAudioTrack?.insertTimeRange(
                timeRange,
                ofTrack = audioTrack,
                atTime = CMTimeMake(0, 1),
                error = error.ptr
            )
        }
    }

    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val timeRange = CMTimeRangeMake(CMTimeMake(0, 1), asset.duration)

        val insertSuccess = compositionTrack.insertTimeRange(
            timeRange,
            ofTrack = assetTrack,
            atTime = CMTimeMake(0, 1),
            error = error.ptr
        )

        if (!insertSuccess) {
            println("Error inserting time range: ${error.value?.localizedDescription}")
            return false
        }
    }

    // Create video composition
    val vComposition = AVMutableVideoComposition().apply {
        setRenderSize(CGSizeMake(targetWidth.toDouble(), targetHeight.toDouble()))
        setFrameDuration(CMTimeMake(1, 30))

        val instruction = AVMutableVideoCompositionInstruction().apply {
            setTimeRange(CMTimeRangeMake(CMTimeMake(0, 1), asset.duration))

            val layerInstruction = AVMutableVideoCompositionLayerInstruction.videoCompositionLayerInstructionWithAssetTrack(compositionTrack)

            // Calculate scaling to maintain aspect ratio
            val naturalSize = assetTrack.naturalSize
            val assetWidth = naturalSize.useContents { width }
            val assetHeight = naturalSize.useContents { height }

            val transform = assetTrack.preferredTransform
            val isPortrait = (transform.useContents { a } == 0.0 && transform.useContents { d } == 0.0)

            // Calculate dimensions based on orientation
            val (effectiveWidth, effectiveHeight) = if (isPortrait) {
                assetHeight to assetWidth
            } else {
                assetWidth to assetHeight
            }

            val scale = targetWidth.toDouble() / effectiveWidth

            val scaledWidth = effectiveWidth * scale
            val scaledHeight = effectiveHeight * scale

            // Center the video
            val tx = 0.0
            val ty = (targetHeight - scaledHeight) / 2.0

            println("Target dimensions: ${targetWidth}x${targetHeight}")
            println("Scale factor: $scale")
            println("Translation: x=$tx, y=$ty")

            var finalTransform = transform
            finalTransform = CGAffineTransformConcat(finalTransform, CGAffineTransformMakeScale(scale, scale))
            finalTransform = CGAffineTransformConcat(finalTransform, CGAffineTransformMakeTranslation(tx, ty))


            layerInstruction.setTransform(finalTransform, atTime = CMTimeMake(0, 1))

            setLayerInstructions(listOf(layerInstruction))
        }

        setInstructions(listOf(instruction))
    }

    // Set up export session
    val exportSession = AVAssetExportSession.exportSessionWithAsset(
        asset = composition,
        presetName = AVAssetExportPresetHighestQuality,
    ) ?: return false

    exportSession.apply {
        outputURL = outputUrl
        outputFileType = AVFileTypeMPEG4
        videoComposition = vComposition
    }

    var success = false
    runBlocking {
        suspendCancellableCoroutine { continuation ->
            exportSession.exportAsynchronouslyWithCompletionHandler {
                success = (exportSession.status.toInt() == 3)
                if (!success) {
                    println("Export failed: ${exportSession.error?.localizedDescription}")
                } else {
                    val outputAsset = AVAsset.assetWithURL(outputUrl)
                    val outputTrack = outputAsset.tracksWithMediaType(AVMediaTypeVideo)[0] as AVAssetTrack
                    memScoped {
                        val finalSize = outputTrack.naturalSize
                        finalSize.useContents {
                            println("Export complete - Final dimensions: width=$width, height=$height")
                        }
                    }
                }
                continuation.resume(Unit) {}
            }
        }
    }

    return success
}
