package com.bitchat.domain.initialization

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.initialization.models.AppInformation

/** What this build says about itself: its version and, where the build has one, its identity. */
class GetAppInformation(
    private val appInformation: AppInformation,
) : Usecase<Unit, AppInformation> {
    override suspend fun invoke(param: Unit): AppInformation = appInformation
}
