/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import kotlin.math.abs

data class AppleCachedPlaybackItem(
    val identity: TrackIdentity,
    val item: Any
)

object AppleTrackBindPolicy {
    fun unnamedOrSame(authority: TrackIdentity?, candidate: TrackIdentity): Boolean {
        if (candidate.isBlank) return false
        if (authority == null || authority.isBlank) return true
        if (TrackIdentityPolicy.isSameTrack(authority, candidate)) return true

        val oneIdOnly = authority.id.isNullOrBlank() != candidate.id.isNullOrBlank()
        if (oneIdOnly &&
            authority.durationMs > 0L &&
            candidate.durationMs > 0L &&
            abs(authority.durationMs - candidate.durationMs) <= 750L &&
            sameNonBlankArtist(authority.artist, candidate.artist)
        ) {
            return true
        }
        return false
    }

    fun shouldFollowObservedPlaybackItem(
        authoritative: TrackIdentity?,
        observed: TrackIdentity
    ): Boolean = !observed.id.isNullOrBlank() && unnamedOrSame(authoritative, observed)

    fun hasRequestableIdentity(track: TrackIdentity): Boolean =
        !track.id.isNullOrBlank() || !track.title.isNullOrBlank()

    fun findCachedPlaybackItem(
        track: TrackIdentity,
        records: Map<String, AppleCachedPlaybackItem>
    ): AppleCachedPlaybackItem? {
        val id = track.id?.trim().orEmpty()
        if (id.isNotBlank()) {
            records[id]?.let { return it }
        }
        if (track.title.isNullOrBlank()) return null
        return records.values.firstOrNull { cached ->
            unnamedOrSame(cached.identity, track)
        }
    }

    private fun sameNonBlankArtist(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return left.trim().equals(right.trim(), ignoreCase = true)
    }
}
