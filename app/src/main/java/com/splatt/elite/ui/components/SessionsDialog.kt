package com.splatt.elite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsDialog(
    onDismiss: () -> Unit,
    onOpen: (File) -> Unit
) {
    val context = LocalContext.current
    val sessionsDir = File(context.filesDir, "sessions")
    if (!sessionsDir.exists()) {
        sessionsDir.mkdirs()
    }

    var filesList by remember { mutableStateOf(sessionsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()) }

    fun refreshFiles() {
        filesList = sessionsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E),
            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Historial de Sesiones",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (filesList.isEmpty()) {
                    Text(
                        text = "No hay sesiones guardadas.",
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filesList) { file ->
                            val fileSizeKb = file.length() / 1024
                            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2D2D2D), RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = file.name.replace(".csv", ""), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(text = "$dateStr - ${fileSizeKb}KB", color = Color.Gray, fontSize = 12.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onOpen(file) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Abrir", fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = {
                                            file.delete()
                                            refreshFiles()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("🗑️", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("Cerrar")
                }
            }
        }
    }
}
