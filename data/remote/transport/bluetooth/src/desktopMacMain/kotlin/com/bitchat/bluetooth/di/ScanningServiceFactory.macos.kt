package com.bitchat.bluetooth.di

import com.bitchat.bluetooth.MacosCentralScanningService
import com.bitchat.bluetooth.service.AppleScanningService
import com.bitchat.bluetooth.service.IosSharedCentralManager
import com.bitchat.domain.base.CoroutineScopeFacade

actual fun createPlatformScanningService(
    coroutineScopeFacade: CoroutineScopeFacade,
    sharedCentralManager: IosSharedCentralManager
): AppleScanningService = MacosCentralScanningService(
    sharedCentralManager = sharedCentralManager
)
