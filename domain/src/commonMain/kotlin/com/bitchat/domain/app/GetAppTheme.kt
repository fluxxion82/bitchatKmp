package com.bitchat.domain.app

import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase

class GetAppTheme(
    private val repository: AppRepository,
) : Usecase<Unit, AppTheme> {

    override suspend fun invoke(param: Unit): AppTheme {
        return repository.getAppTheme()
    }
}
