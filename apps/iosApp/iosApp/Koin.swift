//
//  Koin.swift
//  iosApp
//
//  Created by Sterling Albury on 12/18/25.
//

import Foundation
import BitchatApp

/// Starts the Kotlin side. Nil once it is started; otherwise why the Keychain did not answer: see `AppLaunch`.
func startApplication() -> String? {
    let isMock = false

    return KoinIosKt.startApplication(
        initializers: KotlinMutableSet(set: [TempAppInitializer()]), mock: isMock
    )
}

private var _koin: Koin_coreKoin?
var koin: Koin_coreKoin {
    return _koin!
}
