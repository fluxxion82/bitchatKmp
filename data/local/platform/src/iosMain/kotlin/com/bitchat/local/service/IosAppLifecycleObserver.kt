package com.bitchat.local.service

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.initialization.AppInitializer
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidFinishLaunchingNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.darwin.NSObjectProtocol

class IosAppLifecycleObserver(
    private val coroutineScopeFacade: CoroutineScopeFacade,
) : AppInitializer {
    private var observers = mutableListOf<NSObjectProtocol>()

    override suspend fun initialize() {
        val notificationCenter = NSNotificationCenter.defaultCenter

        observers.add(
            notificationCenter.addObserverForName(
                UIApplicationDidFinishLaunchingNotification,
                null,
                null
            ) {
                coroutineScopeFacade.applicationScope.launch {
                    //_lifecycleEvents.emit(AppLifecycleState.CREATED)
                }
            }
        )

        observers.add(
            notificationCenter.addObserverForName(
                UIApplicationDidBecomeActiveNotification,
                null,
                null
            ) {
                coroutineScopeFacade.applicationScope.launch {
                    // _lifecycleEvents.emit(AppLifecycleState.ACTIVE)
                }
            }
        )

        observers.add(
            notificationCenter.addObserverForName(
                UIApplicationWillResignActiveNotification,
                null,
                null
            ) {
                coroutineScopeFacade.applicationScope.launch {
                    //  _lifecycleEvents.emit(AppLifecycleState.INACTIVE)
                }
            }
        )

        observers.add(
            notificationCenter.addObserverForName(
                UIApplicationDidEnterBackgroundNotification,
                null,
                null
            ) {
                coroutineScopeFacade.applicationScope.launch {
                    // _lifecycleEvents.emit(AppLifecycleState.BACKGROUND)
                }
            }
        )

        observers.add(
            notificationCenter.addObserverForName(
                UIApplicationWillTerminateNotification,
                null,
                null
            ) {
                coroutineScopeFacade.applicationScope.launch {
                    //  _lifecycleEvents.emit(AppLifecycleState.TERMINATED)
                }
            }
        )
    }
}
