package com.downloader.allinone.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.ui.theme.CyanAccent
import com.downloader.allinone.ui.theme.DarkBorder
import com.downloader.allinone.ui.theme.DarkSurface
import com.downloader.allinone.ui.theme.DarkSurfaceVariant
import com.downloader.allinone.ui.theme.GreenSuccess
import com.downloader.allinone.ui.theme.IndigoPrimary
import com.downloader.allinone.ui.theme.PinkAccent
import com.downloader.allinone.ui.theme.RedError
import com.downloader.allinone.ui.theme.TextMuted
import com.downloader.allinone.ui.theme.TextPrimary
import com.downloader.allinone.ui.theme.TextSecondary
import com.downloader.allinone.util.MediaMuxerHelper
import com.downloader.allinone.util.MediaStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MediaMergerModal(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoName by remember { mutableStateOf("") }

    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var selectedAudioName by remember { mutableStateOf("") }

    var outputFileName by remember { mutableStateOf("Video_Dengan_Suara_${System.currentTimeMillis() % 10000}") }
    var isMerging by remember { mutableStateOf(false) }
    var mergeSuccessUri by remember { mutableStateOf<Uri?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedVideoUri = uri
            selectedVideoName = getFileNameFromUri(context, uri) ?: "Video Terpilih"
            errorMessage = null
            mergeSuccessUri = null
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedAudioUri = uri
            selectedAudioName = getFileNameFromUri(context, uri) ?: "Audio Terpilih"
            errorMessage = null
            mergeSuccessUri = null
        }
    }

    Dialog(onDismissRequest = { if (!isMerging) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DarkBorder, RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(listOf(IndigoPrimary, CyanAccent))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Transform, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text(
                                text = "Penggabung Media",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Satukan Video & Suara Jadi MP4",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, enabled = !isMerging) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TextMuted)
                    }
                }

                // 1. Pilih File Video
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !isMerging) {
                            videoPickerLauncher.launch("video/*")
                        },
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = if (selectedVideoUri != null) CyanAccent else TextMuted,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "File Video (MP4 / MKV)",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted
                            )
                            Text(
                                text = if (selectedVideoName.isNotBlank()) selectedVideoName else "Ketuk untuk pilih video dari HP",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selectedVideoUri != null) TextPrimary else CyanAccent
                            )
                        }
                    }
                }

                // 2. Pilih File Suara
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !isMerging) {
                            audioPickerLauncher.launch("audio/*")
                        },
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Audiotrack,
                            contentDescription = null,
                            tint = if (selectedAudioUri != null) PinkAccent else TextMuted,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "File Audio (M4A / MP3 / AAC)",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted
                            )
                            Text(
                                text = if (selectedAudioName.isNotBlank()) selectedAudioName else "Ketuk untuk pilih audio dari HP",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selectedAudioUri != null) TextPrimary else PinkAccent
                            )
                        }
                    }
                }

                // 3. Nama Hasil File
                OutlinedTextField(
                    value = outputFileName,
                    onValueChange = { outputFileName = it },
                    label = { Text("Nama File Video Baru") },
                    singleLine = true,
                    enabled = !isMerging,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = CyanAccent,
                        unfocusedLabelColor = TextMuted
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                // Error Banner jika ada
                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = RedError,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Success Card jika berhasil
                if (mergeSuccessUri != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = GreenSuccess.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenSuccess)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Berhasil Digabungkan!",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = GreenSuccess
                                )
                                Text(
                                    text = "Video tersimpan di Galeri / Movies",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Button(
                                onClick = {
                                    MediaStorageHelper.openMediaFile(context, mergeSuccessUri.toString(), MediaType.VIDEO)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GreenSuccess),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Tonton")
                            }
                        }
                    }
                }

                // Tombol Eksekusi Gabung
                Button(
                    onClick = {
                        val vUri = selectedVideoUri
                        val aUri = selectedAudioUri
                        if (vUri == null) {
                            errorMessage = "Harap pilih file video terlebih dahulu."
                            return@Button
                        }
                        if (aUri == null) {
                            errorMessage = "Harap pilih file audio terlebih dahulu."
                            return@Button
                        }

                        isMerging = true
                        errorMessage = null
                        mergeSuccessUri = null

                        coroutineScope.launch(Dispatchers.IO) {
                            val result = MediaMuxerHelper.muxUris(
                                context = context,
                                videoUri = vUri,
                                audioUri = aUri,
                                outputTitle = outputFileName
                            )

                            withContext(Dispatchers.Main) {
                                isMerging = false
                                if (result.isSuccess) {
                                    mergeSuccessUri = result.getOrNull()
                                    Toast.makeText(context, "Video dan suara berhasil disatukan!", Toast.LENGTH_LONG).show()
                                } else {
                                    errorMessage = result.exceptionOrNull()?.message ?: "Gagal menggabungkan file."
                                }
                            }
                        }
                    },
                    enabled = !isMerging && selectedVideoUri != null && selectedAudioUri != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = IndigoPrimary
                    )
                ) {
                    if (isMerging) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Sedang Menyatukan Video & Suara...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Transform, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Gabungkan Sekarang (MP4)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path?.let { p ->
            val cut = p.lastIndexOf('/')
            if (cut != -1) p.substring(cut + 1) else p
        }
    }
    return result
}
