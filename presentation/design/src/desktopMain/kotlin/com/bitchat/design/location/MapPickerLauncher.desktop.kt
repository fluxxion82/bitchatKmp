package com.bitchat.design.location

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.multiplatform.webview.jsbridge.IJsMessageHandler
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.jsbridge.rememberWebViewJsBridge
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.WebViewNavigator
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewStateWithHTMLData
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed class KcefState {
    data object NotStarted : KcefState()
    data class Initializing(val progress: Float) : KcefState()
    data object Initialized : KcefState()
    data class Error(val message: String) : KcefState()
    data object RestartRequired : KcefState()
}

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
    var kcefState by remember { mutableStateOf<KcefState>(KcefState.NotStarted) }

    LaunchedEffect(Unit) {
        if (kcefState != KcefState.NotStarted) return@LaunchedEffect

        kcefState = KcefState.Initializing(0f)
        withContext(Dispatchers.IO) {
            try {
                KCEF.init(
                    builder = {
                        installDir(File("kcef-bundle"))
                        progress {
                            onDownloading {
                                kcefState = KcefState.Initializing(it)
                            }
                            onInitialized {
                                kcefState = KcefState.Initialized
                            }
                        }
                        settings {
                            cachePath = File("kcef-cache").absolutePath
                        }
                    },
                    onError = {
                        kcefState = KcefState.Error(it?.message ?: "Unknown error")
                    },
                    onRestartRequired = {
                        kcefState = KcefState.RestartRequired
                    }
                )
            } catch (e: Exception) {
                kcefState = KcefState.Error(e.message ?: "Failed to initialize")
            }
        }
    }

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
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(Modifier.fillMaxSize()) {
                when (val state = kcefState) {
                    is KcefState.NotStarted,
                    is KcefState.Initializing -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            val progress = (state as? KcefState.Initializing)?.progress ?: 0f
                            if (progress > 0f) {
                                Text("Downloading browser engine: ${(progress).toInt()}%")
                            } else {
                                Text("Initializing...")
                            }
                        }
                    }

                    is KcefState.Error -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Failed to initialize browser",
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    is KcefState.RestartRequired -> {
                        Text(
                            text = "Please restart the app to complete browser installation",
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    is KcefState.Initialized -> {
                        Column(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                IconButton(onClick = { requestState.value = null }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close map picker"
                                    )
                                }
                            }

                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                MapPickerWebView(
                                    initialGeohash = request.initialGeohash,
                                    onGeohashChanged = { currentGeohash = it }
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = {
                                        val gh = currentGeohash.trim()
                                        if (gh.isNotEmpty()) {
                                            request.onResult(gh)
                                            requestState.value = null
                                        }
                                    },
                                    enabled = currentGeohash.isNotEmpty()
                                ) {
                                    Text(text = if (currentGeohash.isEmpty()) "Select location" else "Use #$currentGeohash")
                                }
                            }
                        }
                    }
                }

                if (kcefState !is KcefState.Initialized) {
                    IconButton(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        onClick = { requestState.value = null }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close map picker"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapPickerWebView(
    initialGeohash: String?,
    onGeohashChanged: (String) -> Unit
) {
    var htmlContent by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            htmlContent = try {
                val resourceStream = object {}::class.java.getResourceAsStream(
                    "/composeResources/bitchatkmp.presentation.design.generated.resources/files/geohash_picker.html"
                )
                resourceStream?.bufferedReader()?.readText()
            } catch (_: Exception) {
                null
            }
        }
    }

    val html = htmlContent
    if (html == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val webViewState = rememberWebViewStateWithHTMLData(html)
    val navigator = rememberWebViewNavigator()
    val jsBridge = rememberWebViewJsBridge()

    LaunchedEffect(jsBridge) {
        jsBridge.register(object : IJsMessageHandler {
            override fun methodName(): String = "onGeohashChanged"

            override fun handle(
                message: JsMessage,
                navigator: WebViewNavigator?,
                callback: (String) -> Unit
            ) {
                val geohash = message.params
                if (geohash.isNotEmpty()) {
                    onGeohashChanged(geohash)
                }
                callback("")
            }
        })
    }

    LaunchedEffect(webViewState.isLoading, initialGeohash) {
        if (!webViewState.isLoading && initialGeohash != null) {
            navigator.evaluateJavaScript(
                "window.focusGeohash && window.focusGeohash('$initialGeohash')"
            )
        }
    }

    LaunchedEffect(webViewState.isLoading) {
        if (!webViewState.isLoading) {
            navigator.evaluateJavaScript(
                """
                window.Android = {
                    onGeohashChanged: function(geohash) {
                        window.kmpJsBridge.callNative('onGeohashChanged', geohash, function(result) {});
                    }
                };
            """.trimIndent()
            )
        }
    }

    WebView(
        state = webViewState,
        modifier = Modifier.fillMaxSize(),
        navigator = navigator,
        webViewJsBridge = jsBridge
    )
}
