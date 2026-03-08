@file:OptIn(
    ExperimentalForeignApi::class,
    InternalComposeUiApi::class,
)

package com.bitchat.embedded

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import drm.drmModePageFlip
import drm.drmModeSetCrtc
import drm.drm_mode_page_flip_event
import egl.eglSwapBuffers
import gbm.gbm_surface_lock_front_buffer
import gbm.gbm_surface_release_buffer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import org.jetbrains.skia.Color

/**
 * Page flip handler callback invoked by drmHandleEvent when a page flip completes.
 *
 * This is called from the DRM event handling in the main loop after select()
 * indicates the DRM fd is ready. It triggers the next render cycle only if
 * the Compose scene has been invalidated.
 */
@Suppress("UNUSED_PARAMETER")
fun pageFlipHandler(
    fd: Int,
    sequence: UInt,
    tvSec: UInt,
    tvUsec: UInt,
    userData: COpaquePointer?,
) {
    if (userData == null) return
    val state = userData.asStableRef<State>().get()

    // Flush pending Compose tasks before render decision
    state.mainDispatcher.flush()

    if (state.needsRender) {
        state.clearRenderRequest()
        renderFrame(state)
    } else {
        requeuePageFlip(state)
    }
}

/**
 * Render a single frame and queue a page flip.
 *
 * This function:
 * 1. Releases the previous buffer (now done displaying)
 * 2. Renders the Compose scene to the Skia canvas
 * 3. Swaps EGL buffers (presents GL framebuffer to GBM)
 * 4. Locks the new front buffer from GBM
 * 5. Gets/creates a DRM framebuffer for it
 * 6. Queues a page flip (async, will trigger pageFlipHandler when complete)
 * 7. Updates buffer tracking state
 *
 * @param state The render state containing all subsystems
 */
fun renderFrame(state: State) {
    state.lastBo?.let { lastBo ->
        drmFbCleanup(lastBo)
        gbm_surface_release_buffer(state.gbm.surface, lastBo)
    }

    val frameTimeNanos = currentTimeNanos()
    state.renderer.renderFrame { skiaCanvas ->
        skiaCanvas.clear(Color.BLACK)
        state.scene.render(
            skiaCanvas.asComposeCanvas(),
            frameTimeNanos,
        )
    }

    eglSwapBuffers(state.egl.display, state.egl.surface)

    val nextBo = gbm_surface_lock_front_buffer(state.gbm.surface)
    if (nextBo == null) {
        println("[PageFlip] Failed to lock front buffer")
        return
    }

    val fbId = drmFbGetFromBo(state.drm, nextBo)
    if (fbId == null) {
        println("[PageFlip] Failed to get DRM framebuffer")
        gbm_surface_release_buffer(state.gbm.surface, nextBo)
        return
    }

    // DRM_MODE_PAGE_FLIP_EVENT = 0x01 - request event notification
    val ret = drmModePageFlip(
        state.drm.fd,
        state.drm.crtcId,
        fbId,
        drm_mode_page_flip_event().toUInt(),
        state.stableRef.asCPointer(),
    )

    if (ret != 0) {
        println("[PageFlip] drmModePageFlip failed: $ret")
        drmFbCleanup(nextBo)
        gbm_surface_release_buffer(state.gbm.surface, nextBo)
        return
    }

    // thisBo becomes lastBo (will be released on next frame)
    // nextBo becomes thisBo (currently being scanned out)
    state.lastBo = state.thisBo
    state.thisBo = nextBo
}

private fun requeuePageFlip(state: State) {
    val currentBo = state.thisBo ?: return
    val fbId = drmFbGetFromBo(state.drm, currentBo) ?: return

    val ret = drmModePageFlip(
        state.drm.fd,
        state.drm.crtcId,
        fbId,
        drm_mode_page_flip_event().toUInt(),
        state.stableRef.asCPointer(),
    )

    if (ret != 0) {
        println("[PageFlip] requeuePageFlip failed: $ret")
    }
}

/**
 * Perform the initial render and first page flip to start the render loop.
 *
 * Unlike subsequent frames which are triggered by page flip completion,
 * the first frame uses drmModeSetCrtc to display immediately, then
 * queues a page flip to start the event-driven cycle.
 *
 * @param state The render state
 */
fun initialRender(state: State) {
    // Flush pending Compose tasks before initial render
    state.mainDispatcher.flush()
    state.clearRenderRequest()

    val frameTimeNanos = currentTimeNanos()
    state.renderer.renderFrame { skiaCanvas ->
        skiaCanvas.clear(Color.BLACK)
        state.scene.render(
            skiaCanvas.asComposeCanvas(),
            frameTimeNanos,
        )
    }

    eglSwapBuffers(state.egl.display, state.egl.surface)

    val firstBo = gbm_surface_lock_front_buffer(state.gbm.surface)
        ?: error("Failed to lock first front buffer")

    val fbId = drmFbGetFromBo(state.drm, firstBo)
        ?: error("Failed to create first DRM framebuffer")

    memScoped {
        val connId = alloc<UIntVar>()
        connId.value = state.drm.connectorId
        state.drm.mode.modeInfo.useContents {
            val ret = drmModeSetCrtc(
                state.drm.fd,
                state.drm.crtcId,
                fbId,
                0u, 0u,
                connId.ptr, 1,
                this.ptr,
            )
            if (ret != 0) {
                error("drmModeSetCrtc failed: $ret")
            }
        }
    }

    println("[PageFlip] Initial frame displayed")
    state.thisBo = firstBo

    val ret = drmModePageFlip(
        state.drm.fd,
        state.drm.crtcId,
        fbId,
        drm_mode_page_flip_event().toUInt(),
        state.stableRef.asCPointer(),
    )

    if (ret != 0) {
        error("Initial drmModePageFlip failed: $ret")
    }

    println("[PageFlip] First page flip queued, entering event loop...")
}

private fun currentTimeNanos(): Long {
    return memScoped {
        val ts = alloc<platform.posix.timespec>()
        platform.posix.clock_gettime(platform.posix.CLOCK_MONOTONIC, ts.ptr)
        ts.tv_sec * 1_000_000_000L + ts.tv_nsec
    }
}
