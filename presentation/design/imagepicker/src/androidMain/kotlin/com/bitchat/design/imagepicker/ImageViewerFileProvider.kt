package com.bitchat.design.imagepicker

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.*

class ImageViewerFileProvider : FileProvider(R.xml.file_paths) {
    companion object {
        fun getImageUri(context: Context): Uri {
            val tempFile = File
                .createTempFile("picture_${System.currentTimeMillis()}", ".png", context.cacheDir)
                .apply { createNewFile() }
            val authority = context.applicationContext.packageName + ".provider"
            return getUriForFile(Objects.requireNonNull(context), authority, tempFile)
        }
    }
}
