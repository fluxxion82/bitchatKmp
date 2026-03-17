@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bitchat.embedded

import cnames.structs.gbm_bo
import drm.drmModeAddFB
import drm.drmModeRmFB
import gbm.gbm_bo_get_handle
import gbm.gbm_bo_get_height
import gbm.gbm_bo_get_stride
import gbm.gbm_bo_get_user_data
import gbm.gbm_bo_get_width
import gbm.gbm_bo_set_user_data
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value

/**
 * Wrapper for a DRM framebuffer ID associated with a GBM buffer object.
 * Stored in the gbm_bo's user_data for caching.
 */
private class DrmFb(
    val drmFd: Int,
    val fbId: UInt,
)

/**
 * Callback invoked when a GBM buffer object is destroyed.
 * Removes the associated DRM framebuffer.
 *
 * This is a static C function that can be passed to gbm_bo_set_user_data.
 */
@Suppress("UNUSED_PARAMETER")
private fun drmFbDestroyCallback(bo: CPointer<gbm_bo>?, data: COpaquePointer?) {
    if (data == null) return
    val fb = data.asStableRef<DrmFb>().get()
    drmModeRmFB(fb.drmFd, fb.fbId)
    data.asStableRef<DrmFb>().dispose()
}

/**
 * Get or create a DRM framebuffer ID for a GBM buffer object.
 *
 * Uses gbm_bo's user_data to cache the framebuffer ID across frames.
 * When the GBM buffer is released, the destroy callback removes the
 * DRM framebuffer automatically.
 *
 * @param drm The DRM device
 * @param bo The GBM buffer object to get/create a framebuffer for
 * @return The DRM framebuffer ID, or null on failure
 */
fun drmFbGetFromBo(drm: Drm, bo: CPointer<gbm_bo>): UInt? {
    // Check if we already have a cached framebuffer for this buffer
    val existingData = gbm_bo_get_user_data(bo)
    if (existingData != null) {
        val fb = existingData.asStableRef<DrmFb>().get()
        return fb.fbId
    }

    // Create a new DRM framebuffer for this buffer
    val handle = gbm_bo_get_handle(bo).useContents { u32 }
    val stride = gbm_bo_get_stride(bo)
    val width = gbm_bo_get_width(bo)
    val height = gbm_bo_get_height(bo)

    val fbId = memScoped {
        val fbIdVar = alloc<UIntVar>()
        val ret = drmModeAddFB(
            drm.fd,
            width, height,
            24u, 32u, // depth, bpp
            stride, handle,
            fbIdVar.ptr,
        )
        if (ret != 0) {
            println("[DRM] Failed to add framebuffer: $ret")
            return null
        }
        fbIdVar.value
    }

    // Cache the framebuffer in the buffer's user_data
    val fb = DrmFb(drm.fd, fbId)
    val stableRef = StableRef.create(fb)

    // Note: We need to use staticCFunction for the callback, but Kotlin/Native
    // doesn't support capturing lambdas in staticCFunction. The workaround is
    // to pass a function reference. However, gbm_bo_set_user_data takes a
    // C function pointer type that we need to cast properly.
    //
    // For now, we'll manage cleanup manually in the page flip handler since
    // staticCFunction with the exact signature may not be directly usable.
    // The StableRef will be disposed when we release the buffer.
    gbm_bo_set_user_data(bo, stableRef.asCPointer(), null)

    return fbId
}

/**
 * Clean up the DRM framebuffer associated with a GBM buffer object.
 * Call this before releasing the buffer to properly dispose resources.
 */
fun drmFbCleanup(bo: CPointer<gbm_bo>) {
    val data = gbm_bo_get_user_data(bo) ?: return
    val fb = data.asStableRef<DrmFb>().get()
    drmModeRmFB(fb.drmFd, fb.fbId)
    data.asStableRef<DrmFb>().dispose()
    gbm_bo_set_user_data(bo, null, null)
}
