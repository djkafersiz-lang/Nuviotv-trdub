package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

/**
 * Builds the active video source and, when selected, merges a public external audio stream.
 * Audio requests intentionally use no video-site authorization headers.
 */
internal fun PlayerRuntimeController.createCurrentMediaSource(
    subtitleConfigurations: List<androidx.media3.common.MediaItem.SubtitleConfiguration> = emptyList(),
    subtitleRoutes: Map<String, SubtitleRoute> = emptyMap()
): MediaSource {
    val externalUrl = currentExternalAudioUrl
    return if (externalUrl.isNullOrBlank()) {
        mediaSourceFactory.createMediaSource(
            context = context,
            url = currentStreamUrl,
            headers = currentHeaders,
            subtitleConfigurations = subtitleConfigurations,
            subtitleRoutes = subtitleRoutes,
            filename = currentFilename,
            responseHeaders = currentStreamResponseHeaders,
            mimeTypeOverride = currentStreamMimeType,
            audioDelayUsProvider = audioDelayUs::get,
            cacheKey = currentStreamCacheKey
        )
    } else {
        mediaSourceFactory.createMediaSourceWithExternalAudio(
            context = context,
            videoUrl = currentStreamUrl,
            videoHeaders = currentHeaders,
            audioUrl = externalUrl,
            audioHeaders = emptyMap(),
            subtitleConfigurations = subtitleConfigurations,
            subtitleRoutes = subtitleRoutes,
            videoFilename = currentFilename,
            audioFilename = currentExternalAudioLabel,
            videoResponseHeaders = currentStreamResponseHeaders,
            videoMimeTypeOverride = currentStreamMimeType,
            audioDelayUsProvider = audioDelayUs::get,
            cacheKey = currentStreamCacheKey
        )
    }
}

/** Attaches the selected public audio file while preserving the current playback position. */
internal fun PlayerRuntimeController.attachExternalTurkishAudio(audioUrl: String, label: String) {
    if (isUsingMpvEngine()) {
        _uiState.update { it.copy(error = "Harici Türkçe ses şu anda ExoPlayer motorunda destekleniyor.") }
        return
    }
    val player = _exoPlayer ?: return
    scope.launch {
        val previousUrl = currentExternalAudioUrl
        val previousLabel = currentExternalAudioLabel
        try {
            val position = player.currentPosition.coerceAtLeast(0L)
            val wasPlaying = player.playWhenReady
            val subtitles = _uiState.value.addonSubtitles
            currentExternalAudioUrl = audioUrl
            currentExternalAudioLabel = label
            val source = createCurrentMediaSource(
                subtitleConfigurations = subtitles.map(::toSubtitleConfiguration),
                subtitleRoutes = subtitleRoutes(subtitles)
            )
            player.setMediaSource(source, position)
            player.prepare()
            player.playWhenReady = wasPlaying
            _uiState.update { it.copy(error = null) }
        } catch (error: Exception) {
            currentExternalAudioUrl = previousUrl
            currentExternalAudioLabel = previousLabel
            _uiState.update {
                it.copy(error = "Türkçe ses eklenemedi: ${error.message ?: "ses kaynağı açılamadı"}")
            }
        }
    }
}
