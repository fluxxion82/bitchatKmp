package com.bitchat.local.service

import com.bitchat.domain.app.AppForegroundState
import com.bitchat.domain.initialization.AppInitializer
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.NSObjectProtocol

class IosAppLifecycleObserver(
    private val foregroundState: AppForegroundState,
) : AppInitializer {
    private val observers = mutableListOf<NSObjectProtocol>()

    override suspend fun initialize() {
        check(NSThread.isMainThread) { "UIApplication lifecycle state must be sampled on the main thread" }
        foregroundState.publish(
            UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground
        )
        val notificationCenter = NSNotificationCenter.defaultCenter

        observers.add(
            notificationCenter.addObserverForName(
                UIApplicationDidEnterBackgroundNotification,
                null,
                null
            ) {
                foregroundState.publish(false)
            }
        )

        observers.add(
            notificationCenter.addObserverForName(
                UIApplicationWillEnterForegroundNotification,
                null,
                null
            ) {
                foregroundState.publish(true)
            }
        )
    }
}
