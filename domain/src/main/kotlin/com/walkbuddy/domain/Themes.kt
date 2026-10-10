package com.walkbuddy.domain

/**
 * Track themes (alpha 2.0): how the Track picture around the walkers looks. Every theme is original art drawn with Canvas; none uses a
 * licensed character, logo or place. [Seasonal] changes with the month. Themes that look busy become still pictures with Reduce motion or
 * Battery saver (the UI already stops looping animation then).
 */
enum class TrackTheme(val id: String) {
    Metro("metro"), Train("train"), Trail("trail"), NightSky("night"), Seasonal("seasonal");

    companion object {
        fun fromId(id: String?): TrackTheme = values().firstOrNull { it.id == id } ?: Metro
    }
}

/** What is actually drawn: a fixed theme, or the season [Seasonal] picks for the month. */
enum class TrackScene { Metro, Train, Trail, NightSky, Spring, Summer, Autumn, Winter }

object Seasons {
    /** March to May spring, June to August summer, September to November autumn, December to February winter. */
    fun sceneForMonth(month: Int): TrackScene = when (((month % 12) + 12) % 12) {
        3, 4, 5 -> TrackScene.Spring
        6, 7, 8 -> TrackScene.Summer
        9, 10, 11 -> TrackScene.Autumn
        else -> TrackScene.Winter
    }

    fun scene(theme: TrackTheme, month: Int): TrackScene = when (theme) {
        TrackTheme.Metro -> TrackScene.Metro
        TrackTheme.Train -> TrackScene.Train
        TrackTheme.Trail -> TrackScene.Trail
        TrackTheme.NightSky -> TrackScene.NightSky
        TrackTheme.Seasonal -> sceneForMonth(month)
    }
}
