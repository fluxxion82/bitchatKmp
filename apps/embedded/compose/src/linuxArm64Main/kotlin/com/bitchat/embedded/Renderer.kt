@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bitchat.embedded

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/**
 * Skia GPU renderer that bridges Compose scene rendering to the EGL/DRM pipeline.
 *
 * Creates a Skia DirectContext on the current GL context and provides per-frame
 * rendering via BackendRenderTarget wrapping the default GL framebuffer.
 */
class Renderer private constructor(
    private val directContext: DirectContext,
    private val width: Int,
    private val height: Int,
) {
    /**
     * Render a single frame. Creates a Skia surface from the current GL framebuffer,
     * invokes the draw callback, then flushes to GL.
     *
     * @param draw callback that draws to the Skia canvas
     */
    fun renderFrame(draw: (Canvas) -> Unit) {
        // Create a render target wrapping GL FBO 0 (the default framebuffer)
        val renderTarget = BackendRenderTarget.makeGL(
            width = width,
            height = height,
            sampleCnt = 0,
            stencilBits = 0,
            fbId = 0,
            fbFormat = GL_RGBA8,
        )

        val surface = Surface.makeFromBackendRenderTarget(
            directContext,
            renderTarget,
            SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888,
            ColorSpace.sRGB,
        ) ?: run {
            renderTarget.close()
            println("[Renderer] Failed to create Skia surface from GL backend")
            return
        }

        try {
            draw(surface.canvas)
            directContext.flush()
        } finally {
            surface.close()
            renderTarget.close()
        }
    }

    fun cleanup() {
        directContext.close()
    }

    companion object {
        // GL_RGBA8
        private const val GL_RGBA8 = 0x8058

        /**
         * Initialize the Skia renderer. Must be called after the EGL context is made current.
         *
         * `makeGL()` is the EGL path on this target. It calls Skia's
         * `GrDirectContexts::MakeGL()`, which resolves through `GrGLMakeNativeInterface()`,
         * and the Skia bundled in `skiko-linuxarm64` >= 0.9.47 is built with `skia_use_egl=true`:
         * the only `GrGLMakeNativeInterface_*` object in the artifact is the EGL one, so GL
         * functions are loaded via `eglGetProcAddress`, never `glXGetProcAddress` (X11).
         * Earlier Skiko releases were GLX-only, which is why this used to call the
         * `makeEGL()` added by a local fork; see docs/FORKED_LIBRARIES.md.
         */
        fun initialize(width: Int, height: Int): Renderer {
            val context = DirectContext.makeGL()
            println("[Renderer] Skia DirectContext created (EGL backend)")
            return Renderer(context, width, height)
        }
    }
}
