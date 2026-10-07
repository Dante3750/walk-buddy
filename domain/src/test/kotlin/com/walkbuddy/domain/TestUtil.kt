package com.walkbuddy.domain

/** Helpers to place points a given number of metres from another (flat-earth approximation, fine for < 1 km). */
object T {
    const val M_PER_DEG = 111_195.0
    val origin = LatLon(12.9716, 77.5946)

    fun north(p: LatLon, m: Double) = LatLon(p.lat + m / M_PER_DEG, p.lon)
    fun south(p: LatLon, m: Double) = north(p, -m)
    fun east(p: LatLon, m: Double) = LatLon(p.lat, p.lon + m / (M_PER_DEG * Math.cos(Math.toRadians(p.lat))))
}
