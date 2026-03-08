@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package com.bitchat.embedded

import androidx.compose.ui.scene.ComposeScene
import cnames.structs.gbm_bo
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlin.concurrent.AtomicInt

/**
 * State for the event-driven render loop.
 * Tracks all resources needed for page-flip based rendering:
 * - DRM, GBM, EGL subsystems
 * - Skia renderer
 * - Compose scene
 * - Double-buffering state (current and previous buffer objects)
 *
 * Following Jake Wharton's composeui-lightswitch State pattern.
 */
class State(
    val drm: Drm,
    val gbm: Gbm,
    val egl: Egl,
    val renderer: Renderer,
    val scene: ComposeScene,
    val touchInput: TouchInput?,
    val keyboardInput: KeyboardInput?,
    val mainDispatcher: FlushCoroutineDispatcher,
) {
    /**
     * Flag indicating the Compose scene needs to be re-rendered.
     * Set by Compose's invalidate callback, cleared after rendering.
     * Uses AtomicInt (0=false, 1=true) for thread-safe access.
     */
    private val _needsRender = AtomicInt(1) // Start with true to render first frame

    /** Check if rendering is needed */
    val needsRender: Boolean get() = _needsRender.value != 0

    /** Mark that a render is needed (called by Compose invalidate) */
    fun requestRender() {
        _needsRender.value = 1
    }

    /** Clear the render request (called after rendering) */
    fun clearRenderRequest() {
        _needsRender.value = 0
    }
    /** The buffer object currently being rendered to / just submitted */
    var thisBo: CPointer<gbm_bo>? = null

    /** The buffer object currently being displayed (from previous flip) */
    var lastBo: CPointer<gbm_bo>? = null

    /** StableRef for passing State to C callbacks */
    private var _stableRef: StableRef<State>? = null

    /**
     * Get or create a StableRef for this State that can be passed to C callbacks.
     * The same StableRef is reused across all page flips.
     */
    val stableRef: StableRef<State>
        get() = _stableRef ?: StableRef.create(this).also { _stableRef = it }

    /**
     * Dispose the StableRef if one was created.
     */
    fun disposeStableRef() {
        _stableRef?.dispose()
        _stableRef = null
    }

    fun cleanup() {
        disposeStableRef()
        scene.close()
        renderer.cleanup()
        egl.cleanup()
        gbm.cleanup()
        drm.cleanup()
        touchInput?.close()
        keyboardInput?.close()
    }
}
