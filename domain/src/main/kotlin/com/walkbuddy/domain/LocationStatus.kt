package com.walkbuddy.domain

/**
 * Why the map or the Track may be empty, in words. Before this existed a missing permission, a switched-off GPS and a slow first fix all
 * looked the same: a blank map with "Waiting for a location fix" that never changed.
 */
enum class LocationStatus { Ok, Weak, Searching, GpsOff, NoPermission }

enum class LocationAction { None, AllowLocation, OpenLocationSettings }

data class LocationNotice(val status: LocationStatus, val title: String, val body: String, val action: LocationAction, val actionLabel: String?)

object LocationStatusLogic {
    fun of(hasPermission: Boolean, anyProviderOn: Boolean, quality: LocationQuality): LocationStatus = when {
        !hasPermission -> LocationStatus.NoPermission
        !anyProviderOn -> LocationStatus.GpsOff
        quality == LocationQuality.None -> LocationStatus.Searching
        quality == LocationQuality.Weak -> LocationStatus.Weak
        else -> LocationStatus.Ok
    }

    /** [searchingForSec] is how long this walk has been waiting for the first position. Null for [LocationStatus.Ok]. */
    fun notice(status: LocationStatus, searchingForSec: Long = 0): LocationNotice? = when (status) {
        LocationStatus.Ok -> null
        LocationStatus.NoPermission -> LocationNotice(
            status, "Location is off for Walk Buddy",
            "Allow location so your buddy can see you and the walk can measure distance. Steps keep counting either way.",
            LocationAction.AllowLocation, "Allow location",
        )
        LocationStatus.GpsOff -> LocationNotice(
            status, "Location is switched off on this phone",
            "Turn on Location in the phone's quick settings or settings so the map and the Track can place you.",
            LocationAction.OpenLocationSettings, "Open location settings",
        )
        LocationStatus.Searching -> LocationNotice(
            status, "Finding your position",
            if (searchingForSec >= 60) "Still searching. A clear view of the sky helps; it can take a minute the first time, longer indoors."
            else "This can take a few seconds. Stepping outside helps.",
            LocationAction.None, null,
        )
        LocationStatus.Weak -> LocationNotice(
            status, "Weak GPS signal",
            "Showing an approximate position. It sharpens with a clear view of the sky.",
            LocationAction.None, null,
        )
    }
}
