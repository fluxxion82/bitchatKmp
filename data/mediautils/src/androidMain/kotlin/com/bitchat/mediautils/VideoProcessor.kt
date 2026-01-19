//package com.pairi.mediautils
//
//import android.content.Context
//import android.graphics.SurfaceTexture
//import android.media.MediaCodec
//import android.media.MediaCodecInfo
//import android.media.MediaCodecList
//import android.media.MediaExtractor
//import android.media.MediaFormat
//import android.media.MediaMetadataRetriever
//import android.media.MediaMuxer
//import android.net.Uri
//import android.opengl.EGL14
//import android.os.Environment
//import android.util.Log
//import android.view.Surface
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import java.io.File
//import java.io.FileInputStream
//
//class VideoProcessor {
//    suspend fun processVideo(context: Context, inputUri: Uri): Uri {
//        return withContext(Dispatchers.IO) {
//            val mediaRetriever = MediaMetadataRetriever()
//            mediaRetriever.setDataSource(context, inputUri)
//
//            // Create output file
//            val outputFile = File(
//                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
//                "processed_${System.currentTimeMillis()}.mp4"
//            )
//
//            val mediaMuxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
//            val extractor = MediaExtractor()
//            extractor.setDataSource(context, inputUri, null)
//
//            // Set up encoder
//            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
//            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1080, 1350)
//            format.setInteger(MediaFormat.KEY_BIT_RATE, calculateBitRate(0.8f)) // 80% quality
//            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
//            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
//            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
//
//            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
//
//            // Process video frames
//            val surface = encoder.createInputSurface()
//            val eglContext = EGL14.eglGetCurrentContext()
//            val inputSurface = InputSurface(surface)
//            val outputSurface = OutputSurface()
//
//            encoder.start()
//
//            try {
//                processFrames(extractor, encoder, inputSurface, outputSurface, mediaMuxer)
//            } finally {
//                // Release resources
//                encoder.stop()
//                encoder.release()
//                inputSurface.release()
//                outputSurface.release()
//                extractor.release()
//                mediaMuxer.stop()
//                mediaMuxer.release()
//            }
//
//            return@withContext Uri.fromFile(outputFile)
//        }
//    }
//
//    private fun calculateBitRate(quality: Float): Int {
//        // Base bitrate for 1080x1350 resolution at high quality
//        val baseBitRate = 10_000_000 // 10 Mbps
//        return (baseBitRate * quality).toInt()
//    }
//
//    private fun processFrames(
//        extractor: MediaExtractor,
//        encoder: MediaCodec,
//        inputSurface: InputSurface,
//        outputSurface: OutputSurface,
//        mediaMuxer: MediaMuxer
//    ) {
//        // Frame processing implementation
//        // This would include reading frames from extractor,
//        // rendering to input surface, and writing encoded frames to muxer
//    }
//}
