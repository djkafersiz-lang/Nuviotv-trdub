package com.nuvio.tv.core.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A public index entry for a Turkish audio-track discussion.
 *
 * Search results are forum topics only. The app does not bypass account, membership,
 * download, or copyright restrictions imposed by either community.
 */
data class ShareSesAudioMatch(
    val title: String,
    val threadUrl: String,
    val excerpt: String?,
    val sourceName: String
)

object ShareSesAudioSearch {
    private data class ForumSource(
        val name: String,
        val baseUrl: String,
        val forumPaths: List<String>
    ) {
        val host: String get() = baseUrl.toHttpUrl().host
    }

    private val sources = listOf(
        ForumSource(
            name = "ShareSes",
            baseUrl = "https://shareses.com",
            forumPaths = listOf(
                "/forums/film-ses-dosyalari.4/",
                "/forums/dizi-ses-dosyalari.5/"
            )
        ),
        ForumSource(
            name = "Turkish Audio Center",
            baseUrl = "https://www.turkishaudiocenter.com",
            forumPaths = listOf("/forums/film-tuerkce-ses-dosyalari.2/")
        )
    )

    private const val MAX_RESULTS_PER_SOURCE = 20
    private const val MAX_RESULTS = 30

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Searches public XenForo search results first, then the visible public forum indexes.
     * Each source is isolated so an unavailable site does not hide results from the other.
     */
    suspend fun search(title: String, imdbId: String? = null): List<ShareSesAudioMatch> = withContext(Dispatchers.IO) {
        val titleQuery = title.trim().replace(Regex("\\s+"), " ").take(160)
        val validImdbId = imdbId?.trim()?.substringBefore(':')
            ?.takeIf { it.matches(Regex("^tt\\d{7,}$")) }
        val queries = listOfNotNull(validImdbId, titleQuery.takeIf { it.length >= 2 }).distinct()
        if (queries.isEmpty()) return@withContext emptyList()

        coroutineScope {
            val results = sources.map { source ->
                async { runCatching { searchSource(source, queries) } }
            }.awaitAll()

            val successful = results.filter { it.isSuccess }
            if (successful.isEmpty()) {
                throw IOException("Türkçe ses kaynaklarına şu anda ulaşılamıyor.")
            }

            successful.flatMap { it.getOrDefault(emptyList()) }
                .distinctBy { it.threadUrl }
                .sortedWith(
                    compareByDescending<ShareSesAudioMatch> { match -> queries.maxOfOrNull { titleMatchScore(it, match.title) } ?: 0 }
                        .thenBy { it.sourceName }
                )
                .take(MAX_RESULTS)
        }
    }

    private fun searchSource(source: ForumSource, queries: List<String>): List<ShareSesAudioMatch> {
        val collected = LinkedHashMap<String, ShareSesAudioMatch>()

        // Search the exact IMDb identifier first, then the canonical display title.
        // Some forum engines do not index IDs, so title/year matching remains the fallback.
        queries.forEach { query ->
            runCatching {
                val searchUrl = (source.baseUrl + "/search/").toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .build()
                val html = fetchHtml(searchUrl.toString())
                parseThreads(html, source, query).forEach { collected[it.threadUrl] = it }
            }
        }

        // XenForo may require a session for search. Inspect only visible public forum indexes.
        source.forumPaths.forEach { path ->
            if (collected.size >= MAX_RESULTS_PER_SOURCE) return@forEach
            runCatching {
                val html = fetchHtml(source.baseUrl + path)
                queries.forEach { query ->
                    parseThreads(html, source, query).forEach { collected.putIfAbsent(it.threadUrl, it) }
                }
            }
        }

        return collected.values
            .sortedByDescending { match ->
                queries.maxOfOrNull { titleMatchScore(it, match.title) } ?: 0
            }
            .take(MAX_RESULTS_PER_SOURCE)
    }

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.7")
            .build()

        val response = client.newCall(request).execute()
        response.use { result ->
            if (!result.isSuccessful) throw IOException("Forum HTTP ${result.code}")
            return result.body?.string().orEmpty()
        }
    }

    private fun parseThreads(
        html: String,
        source: ForumSource,
        query: String
    ): List<ShareSesAudioMatch> {
        if (html.isBlank()) return emptyList()
        val document = Jsoup.parse(html, source.baseUrl)
        val seen = HashSet<String>()

        return document.select(
            ".contentRow-title a[href], .structItem-title a[href], .block-body a[href]"
        ).mapNotNull { anchor ->
            val href = anchor.absUrl("href")
            val uri = runCatching { href.toHttpUrl() }.getOrNull()
                ?: return@mapNotNull null
            if (uri.host != source.host) return@mapNotNull null
            if (!uri.encodedPath.contains("/threads/")) return@mapNotNull null

            val canonical = uri.newBuilder().query(null).fragment(null).build().toString()
            if (!seen.add(canonical)) return@mapNotNull null

            val titleText = anchor.text().replace(Regex("\\s+"), " ").trim()
            if (titleText.isBlank() || titleMatchScore(query, titleText) == 0) {
                return@mapNotNull null
            }

            val parent = anchor.closest(".structItem, .contentRow, .block-row")
            val excerpt = parent
                ?.selectFirst(".structItem-snippet, .contentRow-snippet, .structItem-minor")
                ?.text()?.replace(Regex("\\s+"), " ")?.trim()?.take(280)

            ShareSesAudioMatch(
                title = titleText,
                threadUrl = canonical,
                excerpt = excerpt,
                sourceName = source.name
            )
        }
    }

    private fun titleMatchScore(query: String, candidate: String): Int {
        val queryWords = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 }
            .toSet()
        val candidateText = candidate.lowercase()
        if (queryWords.isEmpty()) return 0
        val matched = queryWords.count(candidateText::contains)
        return matched * 10 + if (candidateText.contains(query.lowercase())) 20 else 0
    }
}
