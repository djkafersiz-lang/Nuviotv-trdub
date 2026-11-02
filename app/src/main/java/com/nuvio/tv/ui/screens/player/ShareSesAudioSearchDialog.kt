package com.nuvio.tv.ui.screens.player

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nuvio.tv.core.audio.ShareSesAudioMatch
import com.nuvio.tv.core.audio.ShareSesAudioFile
import com.nuvio.tv.core.audio.ShareSesAudioSearch
import kotlinx.coroutines.launch

@Composable
internal fun ShareSesAudioSearchDialog(
    visible: Boolean,
    mediaTitle: String,
    imdbId: String?,
    onDismiss: () -> Unit,
    onAttachAudio: (String, String) -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    var matches by remember(mediaTitle, imdbId) { mutableStateOf<List<ShareSesAudioMatch>>(emptyList()) }
    var loading by remember(mediaTitle, imdbId) { mutableStateOf(true) }
    var error by remember(mediaTitle, imdbId) { mutableStateOf<String?>(null) }
    var audioFiles by remember(mediaTitle, imdbId) { mutableStateOf<List<ShareSesAudioFile>>(emptyList()) }
    var selectedThread by remember(mediaTitle, imdbId) { mutableStateOf<String?>(null) }
    var loadingFiles by remember(mediaTitle, imdbId) { mutableStateOf(false) }
    var fileError by remember(mediaTitle, imdbId) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val firstResultFocus = remember { FocusRequester() }

    LaunchedEffect(mediaTitle, imdbId) {
        loading = true
        error = null
        matches = emptyList()
        try {
            matches = ShareSesAudioSearch.search(mediaTitle, imdbId)
            if (matches.isEmpty()) error = "Eşleşen herkese açık konu bulunamadı."
        } catch (exception: Exception) {
            error = exception.message ?: "ShareSes araması yapılamadı."
        } finally {
            loading = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .background(Color(0xFF171A22), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Türkçe ses ara",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Text(
                text = mediaTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFB8C4D8)
            )
            imdbId?.let {
                Text("IMDb: $it", color = Color(0xFF79B8FF), style = MaterialTheme.typography.labelMedium)
            }

            when {
                loading -> Text("IMDb ID ve film adıyla ses kaynaklarında aranıyor…", color = Color.White)
                matches.isEmpty() -> Text(error ?: "Sonuç bulunamadı.", color = Color(0xFFFFCC80))
                else -> {
                    Text("${matches.size} konu bulundu", color = Color(0xFF9ED0FF))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(matches, key = { it.threadUrl }) { match ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF252B36), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedThread = match.threadUrl
                                        audioFiles = emptyList()
                                        fileError = null
                                        loadingFiles = true
                                        coroutineScope.launch {
                                            try {
                                                audioFiles = ShareSesAudioSearch.findAudioFiles(match.threadUrl)
                                                if (audioFiles.isEmpty()) fileError = "Bu konuda doğrudan erişilebilir ses dosyası bulunamadı."
                                            } catch (exception: Exception) {
                                                fileError = exception.message ?: "Ses dosyaları okunamadı."
                                            } finally {
                                                loadingFiles = false
                                            }
                                        }
                                    }
                                    .focusable()
                                    .padding(14.dp)
                                    .then(if (match == matches.firstOrNull()) Modifier.focusRequester(firstResultFocus) else Modifier)
                            ) {
                                Text(match.sourceName, color = Color(0xFF79B8FF), style = MaterialTheme.typography.labelSmall)
                                Text(match.title, color = Color.White, style = MaterialTheme.typography.titleSmall)
                                match.excerpt?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = Color(0xFFB8C4D8), style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Konuyu aç  ›", color = Color(0xFF79B8FF), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (selectedThread != null) {
                Text("Konu içindeki Türkçe ses dosyaları", color = Color(0xFF9ED0FF), style = MaterialTheme.typography.titleMedium)
                when {
                    loadingFiles -> Text("Ses dosyaları kontrol ediliyor…", color = Color.White)
                    audioFiles.isNotEmpty() -> audioFiles.forEach { file ->
                        Text(
                            text = "▶  ${file.title}",
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onAttachAudio(file.url, file.title)
                                onDismiss()
                            }.padding(10.dp)
                        )
                    }
                    fileError != null -> Text(fileError!!, color = Color(0xFFFFCC80))
                }
                Text(
                    text = "Konuyu tarayıcıda aç",
                    color = Color(0xFF79B8FF),
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selectedThread)))
                    }.padding(8.dp)
                )
            }

            Text(
                text = "Not: Üyelik veya indirme izni isteyen konular ShareSes'in kendi kurallarına tabidir. Bu ekran herkese açık konu sonuçlarını listeler.",
                color = Color(0xFF9AA6B8),
                style = MaterialTheme.typography.bodySmall
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.tv.material3.Button(onClick = onDismiss) {
                    Text("Kapat")
                }
            }
        }
    }

    LaunchedEffect(matches) {
        if (matches.isNotEmpty()) runCatching { firstResultFocus.requestFocus() }
    }
}
