package com.bitchat.domain.app

import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase

class SkipBatteryOptimization(
    private val repository: AppRepository,
) : Usecase<Unit, Unit> {
    override suspend fun invoke(param: Unit) {
        repository.setBatteryOptimizationSkipped(true)
    }
}
