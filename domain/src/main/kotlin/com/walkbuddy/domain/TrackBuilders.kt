package com.walkbuddy.domain

/**
 * Turns the live walk state into the walkers the Track draws. Pure, so the screens stay thin and this is unit tested.
 * Me: my own odometer. Others: where they are relative to me along the direction of travel. When a buddy goes quiet the last known
 * place is used and they are marked stale, so the lane fades instead of vanishing.
 */
object TrackBuilders {
    /** After this long without a message a buddy is drawn faded with a "last seen" note. */
    const val STALE_SEC = 30

    private fun placeOf(me: Double, along: Double?, lastAlong: Double?): Pair<Double?, Boolean> = when {
        along != null -> (me + along) to false
        lastAlong != null -> (me + lastAlong) to true
        else -> null to false
    }

    fun forPartner(w: WalkState, myId: String, myAvatar: Avatar): List<TrackWalker> {
        val me = TrackWalker(myId, "You", myAvatar, w.myDistanceM, w.myVerifiedSteps.toInt(), isMe = true)
        val others = w.buddies.map { b ->
            val (x, lastKnown) = placeOf(w.myDistanceM, b.alongM, b.lastAlongM)
            val quiet = (b.lastHeardAgoSec ?: 0) >= STALE_SEC
            TrackWalker(
                b.id, b.name, AvatarCode.of(b.avatar, b.id), x, b.steps,
                stale = lastKnown || quiet, lastSeenSec = if (lastKnown || quiet) b.lastHeardAgoSec else null,
            )
        }
        return listOf(me) + others
    }

    /** Straight-line distance for the gap text of a pair; null unless exactly one buddy is placed. */
    fun partnerGap(w: WalkState): Double? {
        val b = w.buddies.singleOrNull() ?: return null
        return b.distanceM ?: b.lastDistanceM
    }

    fun forGroup(g: GroupState, myName: String, myAvatar: Avatar): List<TrackWalker> {
        val me = TrackWalker(g.selfId, "You", myAvatar, g.myDistanceM, g.myVerifiedSteps.toInt(), isMe = true, straggler = g.iAmStraggler, role = g.myRole)
        val others = g.members.map { m ->
            val (x, lastKnown) = placeOf(g.myDistanceM, m.alongM, m.lastAlongM)
            val quiet = (m.lastHeardAgoSec ?: 0) >= STALE_SEC
            TrackWalker(
                m.id, m.name, AvatarCode.of(m.avatar, m.id), x, m.steps,
                stale = lastKnown || quiet, lastSeenSec = if (lastKnown || quiet) m.lastHeardAgoSec else null,
                straggler = m.straggler, role = m.role,
            )
        }
        return listOf(me) + others
    }

    fun recLanes(walkers: List<TrackWalker>, avatarOf: (TrackWalker) -> Int?, distanceOf: (TrackWalker) -> Double?): List<RecLane> =
        walkers.map { RecLane(it.id, it.name, avatarOf(it), it.xM, it.steps, distanceOf(it), it.isMe) }
}
