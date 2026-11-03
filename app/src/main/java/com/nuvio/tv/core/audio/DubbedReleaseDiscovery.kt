package com.nuvio.tv.core.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Implementations can query Nuvio addon catalogs or independent public catalogs.
 * Providers must return only media URLs they are allowed to expose for playback.
 */
interface DubbedReleaseProvider {
    val id: String
    suspend fun search(identity: PlayingTitleIdentity): List<DubbedReleaseCandidate>
}

/**
 * Runs discovery providers independently so one unavailable catalog does not
 * prevent results from the others. Ranking and safety filtering are centralized.
 */
class DubbedReleaseDiscovery(
    private val providers: List<DubbedReleaseProvider>
) {
    suspend fun find(identity: PlayingTitleIdentity): List<DubbedReleaseMatch> =
        coroutineScope {
            val batches = providers.map { provider ->
                async {
                    runCatching { provider.search(identity) }.getOrDefault(emptyList())
                }
            }.awaitAll()

            DubbedReleaseMatcher.rank(
                identity = identity,
                candidates = batches.flatten().distinctBy { it.mediaUrl }
            )
        }
}
