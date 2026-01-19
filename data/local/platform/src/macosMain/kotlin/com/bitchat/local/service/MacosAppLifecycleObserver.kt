package com.bitchat.local.service

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.initialization.AppInitializer
import kotlinx.coroutines.launch
import platform.AppKit.NSApplicationDidBecomeActiveNotification
import platform.AppKit.NSApplicationDidFinishLaunchingNotification
import platform.AppKit.NSApplicationWillResignActiveNotification
import platform.AppKit.NSApplicationWillTerminateNotification
import platform.Foundation.NSNotificationCenter
import platform.darwin.NSObjectProtocol

class MacosAppLifecycleObserver(
    private val coroutineScopeFacade: CoroutineScopeFacade,
) : AppInitializer {
    private var observers = mutableListOf<NSObjectProtocol>()

    override suspend fun initialize() {
        val notificationCenter = NSNotificationCenter.defaultCenter

        observers.add(
            notificationCenter.addObserverForName(
                NSApplicationDidFinishLaunchingNotification,
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
                NSApplicationDidBecomeActiveNotification,
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
                NSApplicationWillResignActiveNotification,
                null,
                null
            ) {
                coroutineScopeFacade.applicationScope.launch {
                    //  _lifecycleEvents.emit(AppLifecycleState.INACTIVE)
                }
            }
        )

        // Note: macOS doesn't have a "background" state like iOS
        // Apps are either active, inactive, or terminated

        observers.add(
            notificationCenter.addObserverForName(
                NSApplicationWillTerminateNotification,
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
