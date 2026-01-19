package com.bitchat.design.location

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import bitchatkmp.presentation.design.generated.resources.Res
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.WebKit.WKContentWorld
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

private data class MapPickerRequest(
    val initialGeohash: String?,
    val onResult: (String) -> Unit
)

@Composable
actual fun rememberMapPickerLauncher(): MapPickerLauncher {
    var showDialog by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<MapPickerRequest?>(null) }

    if (showDialog && request != null) {
        MapPickerDialog(
            initialGeohash = request?.initialGeohash,
            onResult = { geohash ->
                request?.onResult?.invoke(geohash)
                showDialog = false
                request = null
            },
            onDismiss = {
                showDialog = false
                request = null
            }
        )
    }

    return remember {
        object : MapPickerLauncher {
            override fun open(initialGeohash: String?, onResult: (String) -> Unit) {
                request = MapPickerRequest(initialGeohash?.trim()?.lowercase(), onResult)
                showDialog = true
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
private fun MapPickerDialog(
    initialGeohash: String?,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentGeohash by remember { mutableStateOf(initialGeohash.orEmpty()) }
    var isPageLoading by remember { mutableStateOf(true) }
    var htmlContent by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WKWebView?>(null) }

    LaunchedEffect(Unit) {
        try {
            val bytes = Res.readBytes("files/geohash_picker.html")
            htmlContent = bytes.decodeToString()
        } catch (e: Exception) {
            // Fallback HTML if resource loading fails
            htmlContent = """
                <html><body style="display:flex;align-items:center;justify-content:center;height:100%;">
                    <h2>Map not available</h2>
                    <p>Could not load map picker resources.</p>
                </body></html>
            """.trimIndent()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
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
                if (htmlContent != null) {
                    val messageHandler = remember {
                        object : NSObject(), WKScriptMessageHandlerProtocol {
                            override fun userContentController(
                                userContentController: WKUserContentController,
                                didReceiveScriptMessage: WKScriptMessage
                            ) {
                                val geohash = didReceiveScriptMessage.body as? String
                                if (!geohash.isNullOrEmpty()) {
                                    currentGeohash = geohash
                                }
                            }
                        }
                    }

                    UIKitView(
                        factory = {
                            val bridgeScript = """
                                window.Android = {
                                    onGeohashChanged: function(geohash) {
                                        window.webkit.messageHandlers.geohashHandler.postMessage(geohash);
                                    }
                                };
                            """.trimIndent()

                            val userScript = WKUserScript(
                                source = bridgeScript,
                                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                                forMainFrameOnly = true,
                                inContentWorld = WKContentWorld.pageWorld
                            )

                            val userContentController = WKUserContentController().apply {
                                addUserScript(userScript)
                                addScriptMessageHandler(
                                    scriptMessageHandler = messageHandler,
                                    contentWorld = WKContentWorld.pageWorld,
                                    name = "geohashHandler"
                                )
                            }

                            val config = WKWebViewConfiguration().apply {
                                setUserContentController(userContentController)
                            }

                            WKWebView(frame = CGRectZero.readValue(), configuration = config).apply {
                                scrollView.scrollEnabled = true
                                scrollView.bounces = true
                                userInteractionEnabled = true

                                loadHTMLString(htmlContent!!, baseURL = null)

                                webViewRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            view.scrollView.scrollEnabled = true
                            view.scrollView.bounces = true
                            view.userInteractionEnabled = true
                        },
                        properties = UIKitInteropProperties(
                            interactionMode = UIKitInteropInteractionMode.NonCooperative,
                            isNativeAccessibilityEnabled = false
                        ),
                        onRelease = {
                            webViewRef = null
                        },
                        onReset = {
                            webViewRef = null
                        }
                    )

                    LaunchedEffect(webViewRef, initialGeohash) {
                        if (webViewRef != null && !initialGeohash.isNullOrEmpty()) {
                            // Small delay to ensure page is loaded
                            kotlinx.coroutines.delay(500)
                            webViewRef?.evaluateJavaScript(
                                "window.focusGeohash && window.focusGeohash('$initialGeohash')",
                                completionHandler = { _, error ->
                                    if (error == null) {
                                        isPageLoading = false
                                    }
                                }
                            )
                        } else if (webViewRef != null) {
                            kotlinx.coroutines.delay(500)
                            isPageLoading = false
                        }
                    }
                }

                if (isPageLoading || htmlContent == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    onClick = onDismiss
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
                    onClick = {
                        val gh = currentGeohash.trim()
                        if (gh.isNotEmpty()) {
                            onResult(gh)
                        }
                    },
                    enabled = currentGeohash.length >= 2
                ) {
                    Text(text = if (currentGeohash.isEmpty()) "Select location" else "Use #$currentGeohash")
                }

                DisposableEffect(Unit) {
                    onDispose {
                        webViewRef?.evaluateJavaScript(
                            "window.cleanup && window.cleanup()",
                            completionHandler = null
                        )
                        webViewRef?.stopLoading()
                        webViewRef = null
                    }
                }
            }
        }
    }
}
