package app.seniorremote.connection

import app.seniorremote.domain.SavedTv
import app.seniorremote.domain.TvCandidate
import app.seniorremote.domain.TvDiscovery
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/** Finds addresses where the saved TV might now be. Candidates are hints, never proof of identity. */
fun interface TvRediscovery {
    suspend fun candidatesFor(tv: SavedTv, windowMs: Long): List<TvCandidate>
}

/**
 * Browses the network briefly. If the saved TV has a discovery name or device id, only matching
 * candidates are returned, so other TVs in the home are not contacted. A TV that was added by
 * typing its address has neither, so every candidate is returned. Either way the connection only
 * succeeds if the device presents the pinned key from pairing, so a similarly named TV can never
 * be substituted.
 */
class DiscoveryRediscovery(
    private val discovery: TvDiscovery,
    private val maxCandidates: Int = 4,
) : TvRediscovery {
    override suspend fun candidatesFor(tv: SavedTv, windowMs: Long): List<TvCandidate> {
        var latest: List<TvCandidate> = emptyList()
        withTimeoutOrNull(windowMs) {
            // Stops early once the TV shows up elsewhere; a discovery that ends or fails keeps
            // whatever was seen so far.
            discovery.discover()
                .catch { }
                .firstOrNull { list ->
                    latest = list
                    list.any { it.host != tv.lastKnownHost && isLikelySame(it, tv) }
                }
        }
        val hasHints = tv.deviceId != null || tv.serviceName != null
        return latest
            .filter { it.host != tv.lastKnownHost }
            .filter { !hasHints || isLikelySame(it, tv) }
            .take(maxCandidates)
    }

    private fun isLikelySame(candidate: TvCandidate, tv: SavedTv): Boolean =
        (tv.deviceId != null && candidate.deviceId == tv.deviceId) ||
            (tv.serviceName != null && candidate.serviceName == tv.serviceName)
}
