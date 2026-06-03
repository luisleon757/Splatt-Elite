package com.splatt.elite.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.splatt.elite.ui.theme.AccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun FocusDialog(
    baseUrl: String,
    onDismiss: () -> Unit
) {
    var cameraBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var statusText by remember { mutableStateOf("Conectando con cámara...") }
    val coroutineScope = rememberCoroutineScope()

    // Periodically fetch frame
    LaunchedEffect(key1 = baseUrl) {
        coroutineScope.launch {
            while (true) {
                val result = fetchCameraFrame("$baseUrl/capture")
                if (result != null) {
                    cameraBitmap = result
                    statusText = "Transmitiendo en vivo"
                } else {
                    statusText = "Error de conexión con la cámara"
                }
                delay(250) // 4 FPS is enough for lens focusing and lightweight for ESP32S3
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Enfoque de Cámara",
                style = MaterialTheme.typography.titleLarge,
                color = AccentColor
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Gira la lente M12 físicamente hasta que la imagen se vea nítida.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = cameraBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Stream",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        CircularProgressIndicator(color = AccentColor)
                    }
                }

                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (cameraBitmap != null) Color(0xFF2ECC71) else Color(0xFFE74C3C)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Terminar", color = AccentColor)
            }
        }
    )
}

suspend fun fetchCameraFrame(urlString: String): Bitmap? {
    return withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                inputStream = connection.inputStream
                BitmapFactory.decodeStream(inputStream)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            inputStream?.close()
            connection?.disconnect()
        }
    }
}
