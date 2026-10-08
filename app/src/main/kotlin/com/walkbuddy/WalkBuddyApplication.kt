package com.walkbuddy

import android.app.Application
import android.content.Context
import com.walkbuddy.data.AppDatabase
import com.walkbuddy.data.AppRepository
import com.walkbuddy.data.RouteStore
import com.walkbuddy.data.SettingsStore
import com.walkbuddy.data.StepRecorder
import com.walkbuddy.health.HealthBridges
import com.walkbuddy.notify.Notifications
import com.walkbuddy.sensors.LocationSource
import com.walkbuddy.sensors.StepSource
import com.walkbuddy.session.GroupSession
import com.walkbuddy.session.WalkSession
import com.walkbuddy.steps.StepTracking

/** Hand-rolled dependency container (same approach as the template project): small enough that DI would only add build risk. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = SettingsStore(context)
    private val database = AppDatabase.create(context)
    val repository = AppRepository(database, settings)
    val stepSource = StepSource(context)
    val steps = StepRecorder(stepSource, repository, settings)
    val health = HealthBridges.create(context)
    val locationSource = LocationSource(context)
    val routes = RouteStore(context.applicationContext)
    val session = WalkSession(context.applicationContext, repository, settings, steps, locationSource, health, routes)
    val groupSession = GroupSession(context.applicationContext, repository, settings, steps, locationSource, health, routes)
}

class WalkBuddyApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        // Always-on step counting starts with the process: safety nets first, then the service if Android allows it from here.
        StepTracking.ensureRunning(this)
    }
}
