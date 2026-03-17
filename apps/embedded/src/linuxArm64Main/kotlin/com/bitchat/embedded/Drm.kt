@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bitchat.embedded

import drm.DRM_MODE_CONNECTED
import drm.DRM_MODE_TYPE_PREFERRED
import drm.drmModeConnector
import drm.drmModeCrtc
import drm.drmModeFreeConnector
import drm.drmModeFreeCrtc
import drm.drmModeFreeEncoder
import drm.drmModeFreeResources
import drm.drmModeGetConnector
import drm.drmModeGetCrtc
import drm.drmModeGetEncoder
import drm.drmModeGetResources
import drm.drmModeModeInfo
import drm.drmModeRes
import drm.drmModeSetCrtc
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.toKString
import platform.posix.O_CLOEXEC
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.errno
import platform.posix.open

/**
 * DRM/KMS display management.
 * Opens a DRM device, finds the first connected connector, and selects
 * the preferred mode (targeting 800x480 for the Elecrow 5" display).
 */
class Drm private constructor(
    val fd: Int,
    val connectorId: UInt,
    val crtcId: UInt,
    val mode: DrmMode,
    val originalCrtc: CPointer<drmModeCrtc>?,
) {
    data class DrmMode(
        val width: Int,
        val height: Int,
        val hdisplay: UShort,
        val vdisplay: UShort,
        val vrefresh: UInt,
        val name: String,
        val modeInfo: CValue<drmModeModeInfo>,
    )

    fun cleanup() {
        // Restore the original CRTC if we saved one
        originalCrtc?.let { crtc ->
            drmModeSetCrtc(
                fd,
                crtc.pointed.crtc_id,
                crtc.pointed.buffer_id,
                crtc.pointed.x,
                crtc.pointed.y,
                cValuesOf(connectorId),
                1,
                crtc.pointed.mode.ptr,
            )
            drmModeFreeCrtc(crtc)
        }
        close(fd)
    }

    companion object {
        private const val DRM_DEVICE = "/dev/dri/card0"

        fun initialize(): Drm {
            // Open DRM device
            val fd = open(DRM_DEVICE, O_RDWR or O_CLOEXEC)
            if (fd < 0) {
                error("Failed to open DRM device $DRM_DEVICE: errno=$errno")
            }

            println("drm Getting resources...")
            // Get DRM resources
            val resources = drmModeGetResources(fd)
                ?: error("Failed to get DRM resources")
            println("drm Resources obtained")

            try {
                println("drm Finding connected connector...")
                // Find the first connected connector
                val connector = findConnectedConnector(fd, resources)
                    ?: error("No connected display found")
                println("drm Connector found: ${connector.pointed.connector_id}")

                try {
                    // Find the preferred or best mode (prefer 800x480)
                    val mode = findBestMode(connector)
                        ?: error("No valid display mode found")
                    println("drm Mode found: ${mode.name}")

                    // Find a CRTC for this connector
                    val encoder = if (connector.pointed.encoder_id != 0u) {
                        drmModeGetEncoder(fd, connector.pointed.encoder_id)
                    } else {
                        null
                    }

                    val crtcId = if (encoder != null && encoder.pointed.crtc_id != 0u) {
                        val id = encoder.pointed.crtc_id
                        drmModeFreeEncoder(encoder)
                        id
                    } else {
                        encoder?.let { drmModeFreeEncoder(it) }
                        findCrtcForConnector(fd, resources, connector)
                            ?: error("No suitable CRTC found")
                    }

                    // Save original CRTC for cleanup
                    val originalCrtc = drmModeGetCrtc(fd, crtcId)

                    println("[DRM] Initialized: ${mode.name} (${mode.width}x${mode.height} @ ${mode.vrefresh}Hz)")
                    println("[DRM] Connector ID: ${connector.pointed.connector_id}, CRTC ID: $crtcId")

                    return Drm(
                        fd = fd,
                        connectorId = connector.pointed.connector_id,
                        crtcId = crtcId,
                        mode = mode,
                        originalCrtc = originalCrtc,
                    )
                } finally {
                    drmModeFreeConnector(connector)
                }
            } finally {
                drmModeFreeResources(resources)
            }
        }

        private fun findConnectedConnector(
            fd: Int,
            resources: CPointer<drmModeRes>,
        ): CPointer<drmModeConnector>? {
            val res = resources.pointed
            for (i in 0 until res.count_connectors) {
                val connectorId = res.connectors!![i]
                val connector = drmModeGetConnector(fd, connectorId) ?: continue
                if (connector.pointed.connection == DRM_MODE_CONNECTED) {
                    return connector
                }
                drmModeFreeConnector(connector)
            }
            return null
        }

        private fun findBestMode(
            connector: CPointer<drmModeConnector>,
        ): DrmMode? {
            val conn = connector.pointed
            if (conn.count_modes <= 0) return null

            // First pass: look for 800x480 (Elecrow 5" native resolution)
            for (i in 0 until conn.count_modes) {
                val mode = conn.modes!![i]
                if (mode.hdisplay.toInt() == 800 && mode.vdisplay.toInt() == 480) {
                    return mode.toDrmMode()
                }
            }

            // Second pass: look for preferred mode
            for (i in 0 until conn.count_modes) {
                val mode = conn.modes!![i]
                if (mode.type and DRM_MODE_TYPE_PREFERRED.toUInt() != 0u) {
                    return mode.toDrmMode()
                }
            }

            // Fallback: first available mode
            return conn.modes!![0].toDrmMode()
        }

        private fun drmModeModeInfo.toDrmMode(): DrmMode {
            return DrmMode(
                width = hdisplay.toInt(),
                height = vdisplay.toInt(),
                hdisplay = hdisplay,
                vdisplay = vdisplay,
                vrefresh = vrefresh,
                name = name.toKString(),
                modeInfo = this@toDrmMode.readValue(),
            )
        }

        private fun findCrtcForConnector(
            fd: Int,
            resources: CPointer<drmModeRes>,
            connector: CPointer<drmModeConnector>,
        ): UInt? {
            val conn = connector.pointed
            val res = resources.pointed

            for (i in 0 until conn.count_encoders) {
                val encoderId = conn.encoders!![i]
                val encoder = drmModeGetEncoder(fd, encoderId) ?: continue
                val possibleCrtcs = encoder.pointed.possible_crtcs
                drmModeFreeEncoder(encoder)

                for (j in 0 until res.count_crtcs) {
                    if (possibleCrtcs and (1u shl j) != 0u) {
                        return res.crtcs!![j]
                    }
                }
            }
            return null
        }
    }
}
