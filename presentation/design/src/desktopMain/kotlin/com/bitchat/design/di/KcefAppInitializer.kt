package com.bitchat.design.di

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.initialization.AppInitializer
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.launch
import java.io.File

class KcefAppInitializer(
    private val coroutineScopeFacade: CoroutineScopeFacade,
): AppInitializer {
    override suspend fun initialize() {
        coroutineScopeFacade.applicationScope.launch {
            try {
                KCEF.init(
                    builder = {
                        installDir(File("kcef-bundle"))
                        progress {}
                        settings {
                            cachePath = File("kcef-cache").absolutePath
                        }
                    },
                    onError = {
                        println("KCEF initialization failed!")
                    },
                    onRestartRequired = {
                        println("KCEF restartRequired!")
                    }
                )
            } catch (e: Exception) {
                println("Failed to initialize")
                e.printStackTrace()
            }
        }
    }
}