package com.bitchat.mediautils.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_chlayout
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_int
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_sample_fmt
import org.bytedeco.ffmpeg.global.swresample.swr_alloc
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_get_out_samples
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent

@Composable
actual fun rememberAudioPlayer(): AudioPlayer {
    val player = remember { DesktopAudioPlayer() }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    return player
}

/**
 * Desktop AudioPlayer implementation using Java Sound API.
 * Supports WAV files directly and M4A/AAC files via FFmpeg decoding.
 */
class DesktopAudioPlayer : AudioPlayer {

    private var clip: Clip? = null
    private var currentPath: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var progressJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    companion object {
        private val M4A_EXTENSIONS = setOf("m4a", "aac", "mp4", "m4b", "m4p")
        private const val OUTPUT_SAMPLE_RATE = 44100
        private const val OUTPUT_CHANNELS = 2
    }

    override fun prepare(path: String) {
        // If same file already prepared, do nothing
        if (path == currentPath && clip != null) {
            return
        }

        // Stop any existing playback
        stop()

        scope.launch {
            try {
                currentPath = path
                val file = File(path)

                if (!file.exists()) {
                    println("Audio file not found: $path")
                    return@launch
                }

                val extension = file.extension.lowercase()
                val audioInputStream = if (extension in M4A_EXTENSIONS) {
                    // Use FFmpeg to decode M4A/AAC files
                    decodeWithFFmpeg(path)
                } else {
                    // Use Java Sound API for WAV and other supported formats
                    try {
                        val stream = AudioSystem.getAudioInputStream(file)
                        convertToPcmIfNeeded(stream)
                    } catch (e: Exception) {
                        // Fallback to FFmpeg for any unsupported format
                        println("Java Sound API failed, trying FFmpeg: ${e.message}")
                        decodeWithFFmpeg(path)
                    }
                }

                if (audioInputStream == null) {
                    println("Failed to decode audio file: $path")
                    return@launch
                }

                val info = DataLine.Info(Clip::class.java, audioInputStream.format)
                val newClip = AudioSystem.getLine(info) as Clip

                newClip.addLineListener { event ->
                    when (event.type) {
                        LineEvent.Type.STOP -> {
                            // Check if playback finished naturally
                            if (newClip.framePosition >= newClip.frameLength) {
                                withContextBlocking {
                                    _isPlaying.value = false
                                    _currentPositionMs.value = 0L
                                    newClip.framePosition = 0
                                }
                                stopProgressUpdates()
                            }
                        }
                        LineEvent.Type.START -> {
                            _isPlaying.value = true
                        }
                        else -> {}
                    }
                }

                newClip.open(audioInputStream)
                clip = newClip

                _durationMs.value = (newClip.microsecondLength / 1000)
            } catch (e: Exception) {
                e.printStackTrace()
                println("Error preparing audio: ${e.message}")
            }
        }
    }

    private fun convertToPcmIfNeeded(audioInputStream: AudioInputStream): AudioInputStream {
        val format = audioInputStream.format
        return if (format.encoding != AudioFormat.Encoding.PCM_SIGNED) {
            val decodedFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                format.sampleRate,
                16,
                format.channels,
                format.channels * 2,
                format.sampleRate,
                false
            )
            AudioSystem.getAudioInputStream(decodedFormat, audioInputStream)
        } else {
            audioInputStream
        }
    }

    /**
     * Decode audio file using FFmpeg (supports M4A, AAC, MP3, etc.)
     */
    private fun decodeWithFFmpeg(path: String): AudioInputStream? {
        var formatContext: AVFormatContext? = null
        var codecContext: AVCodecContext? = null
        var swrContext: SwrContext? = null
        var packet: AVPacket? = null
        var frame: AVFrame? = null

        try {
            // Open input file
            formatContext = AVFormatContext(null)
            if (avformat_open_input(formatContext, path, null, null) < 0) {
                println("Could not open input file: $path")
                return null
            }

            // Find stream info
            if (avformat_find_stream_info(formatContext, null as PointerPointer<*>?) < 0) {
                println("Could not find stream info")
                return null
            }

            // Find audio stream
            var audioStreamIndex = -1
            for (i in 0 until formatContext.nb_streams()) {
                val stream = formatContext.streams(i)
                if (stream.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) {
                    audioStreamIndex = i
                    break
                }
            }

            if (audioStreamIndex < 0) {
                println("No audio stream found")
                return null
            }

            val audioStream = formatContext.streams(audioStreamIndex)
            val codecParams = audioStream.codecpar()

            // Find decoder
            val codec = avcodec_find_decoder(codecParams.codec_id())
            if (codec == null) {
                println("Codec not found for codec_id: ${codecParams.codec_id()}")
                return null
            }

            // Allocate codec context
            codecContext = avcodec_alloc_context3(codec)
            if (codecContext == null) {
                println("Could not allocate codec context")
                return null
            }

            // Copy codec parameters to codec context
            if (avcodec_parameters_to_context(codecContext, codecParams) < 0) {
                println("Could not copy codec parameters")
                return null
            }

            // Open codec
            if (avcodec_open2(codecContext, codec, null as PointerPointer<*>?) < 0) {
                println("Could not open codec")
                return null
            }

            // Get input audio parameters
            val inSampleRate = codecContext.sample_rate()
            val inChannelLayout = codecContext.ch_layout()
            val inSampleFormat = codecContext.sample_fmt()

            // Setup resampler to convert to standard PCM format
            swrContext = swr_alloc()
            if (swrContext == null) {
                println("Could not allocate resampler")
                return null
            }

            // Set resampler options
            av_opt_set_int(swrContext, "in_sample_rate", inSampleRate.toLong(), 0)
            av_opt_set_int(swrContext, "out_sample_rate", OUTPUT_SAMPLE_RATE.toLong(), 0)
            av_opt_set_sample_fmt(swrContext, "in_sample_fmt", inSampleFormat, 0)
            av_opt_set_sample_fmt(swrContext, "out_sample_fmt", AV_SAMPLE_FMT_S16, 0)
            av_opt_set_chlayout(swrContext, "in_chlayout", inChannelLayout, 0)

            // Create output channel layout (stereo)
            val outChannelLayout = org.bytedeco.ffmpeg.avutil.AVChannelLayout()
            av_channel_layout_default(outChannelLayout, OUTPUT_CHANNELS)
            av_opt_set_chlayout(swrContext, "out_chlayout", outChannelLayout, 0)

            if (swr_init(swrContext) < 0) {
                println("Could not initialize resampler")
                av_channel_layout_uninit(outChannelLayout)
                return null
            }

            // Allocate packet and frame
            packet = av_packet_alloc()
            frame = av_frame_alloc()

            // Decode all audio frames
            val pcmData = mutableListOf<Byte>()

            while (av_read_frame(formatContext, packet) >= 0) {
                if (packet.stream_index() == audioStreamIndex) {
                    // Send packet to decoder
                    var ret = avcodec_send_packet(codecContext, packet)
                    if (ret < 0) {
                        av_packet_unref(packet)
                        continue
                    }

                    // Receive decoded frames
                    while (ret >= 0) {
                        ret = avcodec_receive_frame(codecContext, frame)
                        if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF) {
                            break
                        }
                        if (ret < 0) {
                            break
                        }

                        // Calculate output samples
                        val outSamples = swr_get_out_samples(swrContext, frame.nb_samples())
                        val outBufferSize = outSamples * OUTPUT_CHANNELS * 2 // 16-bit = 2 bytes

                        // Allocate output buffer
                        val outBuffer = BytePointer(av_malloc(outBufferSize.toLong()))
                        val outBufferPtr = PointerPointer<BytePointer>(outBuffer)

                        // Resample
                        val convertedSamples = swr_convert(
                            swrContext,
                            outBufferPtr,
                            outSamples,
                            frame.data(),
                            frame.nb_samples()
                        )

                        if (convertedSamples > 0) {
                            val actualSize = convertedSamples * OUTPUT_CHANNELS * 2
                            val bytes = ByteArray(actualSize)
                            outBuffer.get(bytes)
                            pcmData.addAll(bytes.toList())
                        }

                        av_free(outBuffer)
                        av_frame_unref(frame)
                    }
                }
                av_packet_unref(packet)
            }

            // Flush decoder
            avcodec_send_packet(codecContext, null)
            var ret = 0
            while (ret >= 0) {
                ret = avcodec_receive_frame(codecContext, frame)
                if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF) {
                    break
                }
                if (ret < 0) {
                    break
                }

                val outSamples = swr_get_out_samples(swrContext, frame.nb_samples())
                val outBufferSize = outSamples * OUTPUT_CHANNELS * 2

                val outBuffer = BytePointer(av_malloc(outBufferSize.toLong()))
                val outBufferPtr = PointerPointer<BytePointer>(outBuffer)

                val convertedSamples = swr_convert(
                    swrContext,
                    outBufferPtr,
                    outSamples,
                    frame.data(),
                    frame.nb_samples()
                )

                if (convertedSamples > 0) {
                    val actualSize = convertedSamples * OUTPUT_CHANNELS * 2
                    val bytes = ByteArray(actualSize)
                    outBuffer.get(bytes)
                    pcmData.addAll(bytes.toList())
                }

                av_free(outBuffer)
                av_frame_unref(frame)
            }

            av_channel_layout_uninit(outChannelLayout)

            if (pcmData.isEmpty()) {
                println("No audio data decoded")
                return null
            }

            // Create AudioInputStream from PCM data
            val pcmBytes = pcmData.toByteArray()
            val audioFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                OUTPUT_SAMPLE_RATE.toFloat(),
                16,
                OUTPUT_CHANNELS,
                OUTPUT_CHANNELS * 2,
                OUTPUT_SAMPLE_RATE.toFloat(),
                false
            )

            val frameCount = pcmBytes.size / audioFormat.frameSize
            return AudioInputStream(
                ByteArrayInputStream(pcmBytes),
                audioFormat,
                frameCount.toLong()
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("FFmpeg decode error: ${e.message}")
            return null
        } finally {
            // Cleanup
            frame?.let { av_frame_free(it) }
            packet?.let { av_packet_free(it) }
            swrContext?.let { swr_free(it) }
            codecContext?.let { avcodec_free_context(it) }
            formatContext?.let { avformat_close_input(it) }
        }
    }

    override fun play(path: String) {
        // If same file and clip is ready, just start/resume
        if (path == currentPath && clip != null) {
            clip?.start()
            _isPlaying.value = true
            startProgressUpdates()
            return
        }

        // Prepare and then play
        scope.launch {
            // Prepare first (this sets up the clip)
            prepare(path)
            // Wait a bit for prepare to complete
            delay(100)
            // Start playback
            withContext(Dispatchers.Main) {
                clip?.start()
                _isPlaying.value = true
                startProgressUpdates()
            }
        }
    }

    override fun pause() {
        clip?.stop()
        _isPlaying.value = false
        stopProgressUpdates()
    }

    override fun stop() {
        stopProgressUpdates()
        try {
            clip?.stop()
            clip?.close()
        } catch (e: Exception) {
            // Ignore
        }
        clip = null
        currentPath = null
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
    }

    override fun seekTo(positionMs: Long) {
        clip?.let { c ->
            val microsecondPosition = positionMs * 1000
            c.microsecondPosition = microsecondPosition
            _currentPositionMs.value = positionMs
        }
    }

    override fun release() {
        stop()
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                clip?.let { c ->
                    _currentPositionMs.value = c.microsecondPosition / 1000
                }
                delay(100) // Update every 100ms
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun withContextBlocking(block: () -> Unit) {
        // Simple synchronous execution for line listener callbacks
        block()
    }
}
