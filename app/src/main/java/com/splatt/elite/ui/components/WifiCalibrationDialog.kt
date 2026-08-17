package com.splatt.elite.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.splatt.elite.ui.theme.GlassBg
import com.splatt.elite.ui.theme.GreenActive
import com.splatt.elite.ui.theme.PanelBg

private fun visorUrl(host: String): String {
    val cleaned = host
        .trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .ifBlank { "10.224.224.121" }

    return "http://$cleaned:8000"
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiCalibrationDialog(
    initialHost: String,
    onHostSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var hostText by remember(initialHost) {
        mutableStateOf(initialHost)
    }
    var activeUrl by remember(initialHost) {
        mutableStateOf(visorUrl(initialHost))
    }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val currentWebView = webView
        if (currentWebView?.canGoBack() == true) {
            currentWebView.goBack()
        } else {
            onDismiss()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PanelBg)
                .padding(10.dp),
        ) {
            Text(
                text = "Panel de cámara",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = hostText,
                    onValueChange = { hostText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("IP de la Raspberry") },
                    singleLine = true,
                )

                Button(
                    onClick = {
                        val normalizedHost = hostText
                            .trim()
                            .removePrefix("http://")
                            .removePrefix("https://")
                            .substringBefore('/')

                        if (normalizedHost.isNotBlank()) {
                            hostText = normalizedHost
                            onHostSaved(normalizedHost)
                            activeUrl = visorUrl(normalizedHost)
                        }
                    },
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GreenActive,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Abrir")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { webView?.reload() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GlassBg,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Recargar")
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB23A48),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Cerrar")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp),
                factory = {
                    WebView(context).apply {
                        setBackgroundColor(AndroidColor.BLACK)
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        loadUrl(activeUrl)
                        webView = this
                    }
                },
                update = { view ->
                    if (view.url != activeUrl) {
                        view.loadUrl(activeUrl)
                    }
                },
            )
        }
    }
}

