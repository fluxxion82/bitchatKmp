package com.bitchat.screens

import com.bitchat.domain.app.model.BatteryOptimizationStatus
import com.bitchat.viewvo.permissions.PermissionType
import kotlinx.serialization.Serializable

@Serializable
object Routes {
    @Serializable
    data object Welcome

    @Serializable
    data object Onboard

    @Serializable
    data object Permissions

    @Serializable
    data class PermissionsError(val deniedPermissionOrdinals: String) {
        val deniedPermissions: List<PermissionType>
            get() = deniedPermissionOrdinals
                .split(",")
                .filter { it.isNotBlank() }
                .map { PermissionType.entries[it.toInt()] }

        companion object {
            fun create(deniedPermissions: List<PermissionType>): PermissionsError {
                return PermissionsError(deniedPermissions.joinToString(",") { it.ordinal.toString() })
            }
        }
    }

    @Serializable
    data object BluetoothDisabled

    @Serializable
    data object LocationServicesDisabled

    @Serializable
    data class BatteryOptimization(val statusOrdinal: Int) {
        val status: BatteryOptimizationStatus
            get() = BatteryOptimizationStatus.entries[statusOrdinal]

        companion object {
            fun create(status: BatteryOptimizationStatus): BatteryOptimization {
                return BatteryOptimization(status.ordinal)
            }
        }
    }

    @Serializable
    data object Home

    @Serializable
    data class Chat(val channel: String)

    @Serializable
    data object Locations

    @Serializable
    data object Settings

    @Serializable
    data object DebugSettings

    @Serializable
    data object LocationNotes
}
