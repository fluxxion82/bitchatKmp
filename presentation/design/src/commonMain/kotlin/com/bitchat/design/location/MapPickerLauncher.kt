package com.bitchat.design.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitchat.design.globe.GlobePicker

interface MapPickerLauncher {
    fun open(initialGeohash: String?, onResult: (String) -> Unit)
}

/**
 * Opens the geohash picker.
 *
 * This used to be an `expect` with a WebView actual per platform, all loading the same bundled
 * `geohash_picker.html`, which pulled Leaflet from unpkg.com and map tiles from
 * basemaps.cartocdn.com. Every open leaked the user's IP and the tiles they browsed to two third
 * parties, and WebView and WKWebView both ignore the JVM proxy so Tor never covered it. Upstream
 * bitchat-android has no such leak -- it renders a native globe from bundled data -- so this was a
 * regression against upstream, not an inherited limitation.
 *
 * There is nothing platform-specific left: [GlobePicker] is pure Compose over bundled Natural Earth
 * geojson, so one common implementation serves Android, iOS, desktop and embedded alike.
 */
@Composable
fun rememberMapPickerLauncher(): MapPickerLauncher {
    var request by remember { mutableStateOf<Pair<String?, (String) -> Unit>?>(null) }

    val launcher = remember {
        object : MapPickerLauncher {
            override fun open(initialGeohash: String?, onResult: (String) -> Unit) {
                request = initialGeohash to onResult
            }
        }
    }

    request?.let { (initialGeohash, onResult) ->
        Dialog(
            onDismissRequest = { request = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            GlobePicker(
                initialGeohash = initialGeohash,
                onCancel = { request = null },
                onConfirm = { geohash ->
                    request = null
                    onResult(geohash)
                }
            )
        }
    }

    return launcher
}
