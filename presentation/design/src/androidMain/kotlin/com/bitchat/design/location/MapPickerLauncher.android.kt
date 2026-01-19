package com.bitchat.design.location

import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class MapPickerRequest(
    val initialGeohash: String?,
    val onResult: (String) -> Unit
)

@Composable
actual fun rememberMapPickerLauncher(): MapPickerLauncher {
    val requestState = remember { mutableStateOf<MapPickerRequest?>(null) }

    if (requestState.value != null) {
        MapPickerDialog(requestState)
    }

    return remember {
        object : MapPickerLauncher {
            override fun open(initialGeohash: String?, onResult: (String) -> Unit) {
                requestState.value = MapPickerRequest(initialGeohash?.trim()?.lowercase(), onResult)
            }
        }
    }
}

@Composable
private fun MapPickerDialog(requestState: MutableState<MapPickerRequest?>) {
    val request = requestState.value ?: return
    var currentGeohash by remember { mutableStateOf(request.initialGeohash.orEmpty()) }

    Dialog(
        onDismissRequest = { requestState.value = null },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            MapPickerSheetContent(
                initialGeohash = request.initialGeohash,
                currentGeohash = currentGeohash,
                onGeohashChanged = { currentGeohash = it },
                onReady = { },
                onConfirm = {
                    val gh = currentGeohash.trim()
                    if (gh.isNotEmpty()) {
                        request.onResult(gh)
                        requestState.value = null
                    }
                },
                onClose = { requestState.value = null }
            )
        }
    }
}

@Composable
private fun MapPickerSheetContent(
    initialGeohash: String?,
    currentGeohash: String,
    onGeohashChanged: (String) -> Unit,
    onReady: () -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            initialGeohash?.let { gh ->
                                evaluateJavascript("window.focusGeohash('${gh}')", null)
                            }
                            onReady()
                        }
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onGeohashChanged(geohash: String) {
                            onGeohashChanged(geohash)
                        }
                    }, "Android")

                    loadUrl("file:///android_asset/geohash_picker.html")
                }
            },
            update = { webView ->
                // Keep layout bounds in sync
                webView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            },
            onRelease = { webView ->
                try {
                    webView.evaluateJavascript("window.cleanup && window.cleanup()", null)
                } catch (_: Throwable) {
                }
                try {
                    webView.stopLoading()
                } catch (_: Throwable) {
                }
                try {
                    webView.clearHistory()
                } catch (_: Throwable) {
                }
                try {
                    webView.clearCache(true)
                } catch (_: Throwable) {
                }
                try {
                    webView.loadUrl("about:blank")
                } catch (_: Throwable) {
                }
                try {
                    webView.removeAllViews()
                } catch (_: Throwable) {
                }
                try {
                    webView.destroy()
                } catch (_: Throwable) {
                }
            }
        )

        IconButton(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            onClick = onClose
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close map picker"
            )
        }

        Button(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            onClick = onConfirm,
            enabled = currentGeohash.length >= 2
        ) {
            Text(text = if (currentGeohash.isEmpty()) "Select location" else "Use #$currentGeohash")
        }
    }
}