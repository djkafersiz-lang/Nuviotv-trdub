package com.nuvio.tv.core.audio

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.flow.collect

/**
 * Discovers alternative releases through addons already installed in Nuvio.
 *
 * Only ordinary HTTP(S) media URLs are returned. Magnet/torrent-only, browser,
 * DRM and unresolved debrid entries are intentionally excluded; this provider
 * never attempts to resolve restricted sources.
 */
class NuvioAddonDubbedReleaseProvider(
    private val streamRepository: StreamRepository
) : DubbedReleaseProvider {

    override val id: String = "nuvio-installed-addons"

    override suspend fun search(identity: PlayingTitleIdentity): List<DubbedReleaseCandidate> {
        val imdb = identity.imdbId?.substringBefore(':')?.takeIf { it.startsWith("tt") }
            ?: return emptyList()
        val type = if (identity.season != null || identity.episode != null) "series" else "movie"
        val videoId = if (type == "series") {
            "$imdb:${identity.season ?: return emptyList()}:${identity.episode ?: return emptyList()}"
        } else imdb

        var found = emptyList<com.nuvio.tv.domain.model.AddonStreams>()
        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = videoId,
            season = identity.season,
            episode = identity.episode,
            forceRefresh = true
        ).collect { result ->
            if (result is NetworkResult.Success) found = result.data
        }

        return found.flatMap { addon ->
            addon.streams.mapNotNull { stream -> stream.toDubbedCandidate(identity, addon.addonName) }
        }
    }

    private fun Stream.toDubbedCandidate(
        identity: PlayingTitleIdentity,
        fallbackSource: String
    ): DubbedReleaseCandidate? {
        val mediaUrl = getStreamUrl()?.trim()?.takeIf {
            (it.startsWith("https://", true) || it.startsWith("http://", true))
        } ?: return null

        val parsed = clientResolve?.stream?.raw?.parsed
        val releaseTitle = listOfNotNull(
            parsed?.parsedTitle,
            parsed?.rawTitle,
            clientResolve?.title,
            behaviorHints?.filename,
            clientResolve?.filename,
            title,
            name
        ).firstOrNull { it.isNotBlank() } ?: identity.title

        val releaseYear = parsed?.year ?: Regex("\\b(19\\d{2}|20\\d{2})\\b")
            .find(releaseTitle)?.value?.toIntOrNull()

        val languageHints = buildSet {
            parsed?.languages.orEmpty().forEach(::add)
            parsed?.audio.orEmpty().forEach(::add)
            listOfNotNull(name, title, description, behaviorHints?.filename, clientResolve?.filename)
                .forEach { text ->
                    if (Regex("(?i)(\\btr\\b|turkish|türkçe|tur\\b|dublaj)").containsMatchIn(text)) {
                        add("Turkish")
                    }
                }
        }

        val urlHost = runCatching { android.net.Uri.parse(mediaUrl).host }.getOrNull()
        val isDrm = listOfNotNull(name, title, description)
            .any { Regex("(?i)(widevine|playready|fairplay|drm)").containsMatchIn(it) }

        return DubbedReleaseCandidate(
            title = releaseTitle,
            year = releaseYear,
            imdbId = clientResolve?.mediaId?.takeIf { it.startsWith("tt") }
                ?: identity.imdbId,
            season = clientResolve?.season ?: identity.season,
            episode = clientResolve?.episode ?: identity.episode,
            languages = languageHints,
            mediaUrl = mediaUrl,
            sourceName = addonName.ifBlank { fallbackSource },
            isDirectMedia = !urlHost.isNullOrBlank() && !isDrm,
            isDrmProtected = isDrm
        )
    }
}
