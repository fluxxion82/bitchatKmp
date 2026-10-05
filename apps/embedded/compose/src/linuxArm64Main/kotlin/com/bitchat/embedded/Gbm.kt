@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bitchat.embedded

import cnames.structs.gbm_bo
import cnames.structs.gbm_device
import cnames.structs.gbm_surface
import gbm.gbm_create_device
import gbm.gbm_device_destroy
import gbm.gbm_surface_create
import gbm.gbm_surface_destroy
import gbm.gbm_surface_lock_front_buffer
import gbm.gbm_surface_release_buffer
import kotlinx.cinterop.CPointer

/**
 * GBM (Generic Buffer Management) surface for DRM rendering.
 * Creates a GBM device and surface for double-buffered page-flipping.
 */
class Gbm private constructor(
    val device: CPointer<gbm_device>,
    private var _surface: CPointer<gbm_surface>,
    val width: Int,
    val height: Int,
) {
    val surface: CPointer<gbm_surface> get() = _surface

    /** Lock the front buffer for display. Returns a gbm_bo to scan out. */
    fun lockFrontBuffer(): CPointer<gbm_bo>? {
        return gbm_surface_lock_front_buffer(_surface)
    }

    /** Release a previously locked buffer. */
    fun releaseBuffer(bo: CPointer<gbm_bo>) {
        gbm_surface_release_buffer(_surface, bo)
    }

    /**
     * Recreate the GBM surface with a different format.
     * Used when EGL config selection requires a format other than XRGB8888
     * (e.g., ARGB8888).
     */
    fun recreateSurface(format: UInt) {
        gbm_surface_destroy(_surface)
        _surface = gbm_surface_create(
            device,
            width.toUInt(),
            height.toUInt(),
            format,
            GBM_BO_USE_SCANOUT or GBM_BO_USE_RENDERING,
        ) ?: error("Failed to recreate GBM surface with format 0x${format.toString(16)}")
        println("[GBM] Recreated ${width}x${height} surface (format=0x${format.toString(16)})")
    }

    fun cleanup() {
        gbm_surface_destroy(_surface)
        gbm_device_destroy(device)
    }

    companion object {
        // GBM_FORMAT_XRGB8888
        private const val GBM_FORMAT_XRGB8888 = 0x34325258u

        // GBM_BO_USE_SCANOUT | GBM_BO_USE_RENDERING
        private const val GBM_BO_USE_SCANOUT = 1u      // 1 shl 0
        private const val GBM_BO_USE_RENDERING = 4u    // 1 shl 2

        fun initialize(drm: Drm): Gbm {
            val device = gbm_create_device(drm.fd)
                ?: error("Failed to create GBM device")

            val surface = gbm_surface_create(
                device,
                drm.mode.width.toUInt(),
                drm.mode.height.toUInt(),
                GBM_FORMAT_XRGB8888,
                GBM_BO_USE_SCANOUT or GBM_BO_USE_RENDERING,
            ) ?: run {
                gbm_device_destroy(device)
                error("Failed to create GBM surface (${drm.mode.width}x${drm.mode.height})")
            }

            println("[GBM] Created ${drm.mode.width}x${drm.mode.height} surface (XRGB8888)")

            return Gbm(
                device = device,
                _surface = surface,
                width = drm.mode.width,
                height = drm.mode.height,
            )
        }
    }
}
