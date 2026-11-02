package com.nuvio.tv.core.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Searches the public ShareSes XenForo forum for Turkish audio-track discussions.
 *
 * This intentionally returns public thread pages, not scraped attachment URLs. ShareSes
 * has member-only areas and download limits; playback must use links the signed-in user
 * is allowed to access, without bypassing those controls.
 */
data class ShareSesAudioMatch(
    val title: String,
    val threadUrl: String,
    val excerpt: String?
)

object ShareSesAudioSearch {
    private const val BASE_URL = "https://shareses.com"
    private const val SEARCH_PATH = "/search/"
    private const val MAX_RESULTS = 20

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Search by the currently playing film/episode title. Only public search results
     * hosted on shareses.com are returned.
     */
    suspend fun search(title: String): List<ShareSesAudioMatch> = withContext(Dispatchers.IO) {
        val query = title.trim().replace(Regex("\\s+"), " ").take(160)
        if (query.length < 2) return@withContext emptyList()

        val url = (BASE_URL + SEARCH_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.7")
            .build()

        val response = client.newCall(request).execute()
        response.use { result ->
            if (!result.isSuccessful) throw IOException("ShareSes search failed: HTTP ${result.code}")
            val html = result.body?.string().orEmpty()
            if (html.isBlank()) return@withContext emptyList()

            val document = Jsoup.parse(html, BASE_URL)
            val candidates = document.select(
                ".contentRow-title a[href], .structItem-title a[href], .block-body a[href]"
            )
            val seen = HashSet<String>()
            candidates.mapNotNull { anchor ->
                val href = anchor.absUrl("href")
                val uri = runCatching { href.toHttpUrl() }.getOrNull() ?: return@mapNotNull null
                if (uri.host != "shareses.com" && !uri.host.endsWith(".shareses.com")) return@mapNotNull null
                if (!uri.encodedPath.contains("/threads/")) return@mapNotNull null

                val canonical = uri.newBuilder().query(null).fragment(null).build().toString()
                if (!seen.add(canonical)) return@mapNotNull null

                val titleText = anchor.text().replace(Regex("\\s+"), " ").trim()
                if (titleText.isBlank()) return@mapNotNull null
                val parent = anchor.closest(".structItem, .contentRow, .block-row")
                val excerpt = parent?.selectFirst(".structItem-snippet, .contentRow-snippet, .structItem-minor")
                    ?.text()?.replace(Regex("\\s+"), " ")?.trim()?.take(280)

                ShareSesAudioMatch(
                    title = titleText,
                    threadUrl = canonical,
                    excerpt = excerpt
                )
            }
                .sortedByDescending { match -> titleMatchScore(query, match.title) }
                .take(MAX_RESULTS)
        }
    }

    private fun titleMatchScore(query: String, candidate: String): Int {
        val queryWords = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
        val candidateText = candidate.lowercase()
        if (queryWords.isEmpty()) return 0
        val matched = queryWords.count(candidateText::contains)
        return matched * 10 + if (candidateText.contains(query.lowercase())) 20 else 0
    }
}
