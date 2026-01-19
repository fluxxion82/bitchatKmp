//
//  Koin.swift
//  iosApp
//
//  Created by Sterling Albury on 12/18/25.
//

import Foundation
import BitchatApp

func startKoin() {
    let isMock = false

    _ = KoinIosKt.doInitKoinIos(
        initializers: KotlinMutableSet(set: [TempAppInitializer()]), mock: isMock
    )
}

private var _koin: Koin_coreKoin?
var koin: Koin_coreKoin {
    return _koin!
}
