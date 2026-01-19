package com.bitchat.mediautils

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.bitchat.mediautils.model.ResizeOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual suspend fun resizeVideo(inputVideoPath: String): ByteArray = withContext(Dispatchers.IO) {
    val tempOutputPath = File.createTempFile("processed_video", ".mp4").absolutePath

    val resizeOption = ResizeOptions()
    val command = arrayOf(
        "-y",
        "-i", inputVideoPath,
        "-vf", "scale=${resizeOption.width}:${resizeOption.height}",
        tempOutputPath
    )

    val session = FFmpegKit.execute(command.joinToString(" "))
    if (ReturnCode.isSuccess(session.getReturnCode())) {
        val temp = File(tempOutputPath)
        temp.readBytes().also { temp.delete() }
    } else if (ReturnCode.isCancel(session.getReturnCode())) {
        byteArrayOf()
    } else {
        byteArrayOf()
    }
}
