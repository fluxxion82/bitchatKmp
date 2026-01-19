package com.bitchat.screens.location

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberLocationSettingsLauncher(onReturn: () -> Unit): () -> Unit {
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        onReturn()
    }

    return remember {
        {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            settingsLauncher.launch(intent)
        }
    }
}