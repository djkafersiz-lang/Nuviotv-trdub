package com.nuvio.tv.core.audio

import java.text.Normalizer
import java.util.Locale

/**
 * Metadata used to identify a dubbed copy of the currently playing title.
 * A candidate must expose a directly accessible, non-DRM media URL before
 * a later extraction stage can inspect its audio streams.
 */
data class DubbedReleaseCandidate(
    val title: String,
    val year: Int?,
    val imdbId: String?,
    val season: Int? = null,
    val episode: Int? = null,
    val languages: Set<String> = emptySet(),
    val mediaUrl: String,
    val sourceName: String,
    val isDirectMedia: Boolean = false,
    val isDrmProtected: Boolean = false
)

data class PlayingTitleIdentity(
    val title: String,
    val year: Int?,
    val imdbId: String?,
    val season: Int? = null,
    val episode: Int? = null
)

data class DubbedReleaseMatch(
    val candidate: DubbedReleaseCandidate,
    val confidence: Int,
    val reasons: List<String>
)

/**
 * Scores releases from any registered discovery provider. It deliberately
 * rejects protected/non-direct sources and episode/year mismatches.
 */
object DubbedReleaseMatcher {
    fun rank(
        identity: PlayingTitleIdentity,
        candidates: List<DubbedReleaseCandidate>
    ): List<DubbedReleaseMatch> {
        return candidates.mapNotNull { candidate ->
            score(identity, candidate)
        }.sortedWith(
            compareByDescending<DubbedReleaseMatch> { it.confidence }
                .thenBy { it.candidate.sourceName }
        )
    }

    private fun score(
        identity: PlayingTitleIdentity,
        candidate: DubbedReleaseCandidate
    ): DubbedReleaseMatch? {
        if (candidate.mediaUrl.isBlank() || candidate.isDrmProtected || !candidate.isDirectMedia) return null

        val reasons = mutableListOf<String>()
        var score = 0
        val expectedImdb = identity.imdbId?.substringBefore(':')?.trim()?.lowercase(Locale.ROOT)
        val foundImdb = candidate.imdbId?.substringBefore(':')?.trim()?.lowercase(Locale.ROOT)

        if (!expectedImdb.isNullOrBlank() && !foundImdb.isNullOrBlank()) {
            if (expectedImdb != foundImdb) return null
            score += 100
            reasons += "IMDb kimliği eşleşti"
        }

        val expectedTitle = normalize(identity.title)
        val foundTitle = normalize(candidate.title)
        if (expectedTitle.isBlank() || foundTitle.isBlank()) return null
        if (expectedTitle == foundTitle) {
            score += 45
            reasons += "Film adı tam eşleşti"
        } else if (expectedTitle.contains(foundTitle) || foundTitle.contains(expectedTitle)) {
            score += 25
            reasons += "Film adı kısmi eşleşti"
        } else {
            return null
        }

        if (identity.year != null && candidate.year != null) {
            if (identity.year != candidate.year) return null
            score += 20
            reasons += "Yapım yılı eşleşti"
        }

        if (identity.season != null || identity.episode != null) {
            if (identity.season != candidate.season || identity.episode != candidate.episode) return null
            score += 25
            reasons += "Sezon ve bölüm eşleşti"
        }

        if (candidate.languages.any {
                it.equals("tr", true) || it.equals("tur", true) ||
                    it.contains("turkish", true) || it.contains("türkçe", true)
            }) {
            score += 30
            reasons += "Türkçe ses bilgisi bulundu"
        }

        return DubbedReleaseMatch(candidate, score.coerceAtMost(100), reasons)
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
