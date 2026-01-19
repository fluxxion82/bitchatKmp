package com.bitchat.local.service

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.bitchat.domain.initialization.AppInitializer

class ActivityProvider(
    private val application: Application,
) : AppInitializer {

    private var activityOnTop: Activity? = null

    override suspend fun initialize() {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) {
                activityOnTop = null
            }

            override fun onActivityResumed(activity: Activity) {
                activityOnTop = activity
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        })
    }

    fun get(): Activity? = activityOnTop
}
