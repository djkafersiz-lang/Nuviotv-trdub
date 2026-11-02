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
import com.nuvio.tv.core.audio.ShareSesAudioSearch

@Composable
internal fun ShareSesAudioSearchDialog(
    visible: Boolean,
    mediaTitle: String,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    var matches by remember(mediaTitle) { mutableStateOf<List<ShareSesAudioMatch>>(emptyList()) }
    var loading by remember(mediaTitle) { mutableStateOf(true) }
    var error by remember(mediaTitle) { mutableStateOf<String?>(null) }
    val firstResultFocus = remember { FocusRequester() }

    LaunchedEffect(mediaTitle) {
        loading = true
        error = null
        matches = emptyList()
        try {
            matches = ShareSesAudioSearch.search(mediaTitle)
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

            when {
                loading -> Text("ShareSes'te aranıyor…", color = Color.White)
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
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(match.threadUrl))
                                        context.startActivity(intent)
                                    }
                                    .focusable()
                                    .padding(14.dp)
                                    .then(if (match == matches.firstOrNull()) Modifier.focusRequester(firstResultFocus) else Modifier)
                            ) {
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
