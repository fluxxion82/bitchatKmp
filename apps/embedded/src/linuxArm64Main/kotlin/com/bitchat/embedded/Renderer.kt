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
 *
 * Following Jake Wharton's composeui-lightswitch rendering pattern.
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
         * Initialize the Skia renderer. Must be called after EGL context is made current.
         * Uses EGL-enabled Skiko (Jake Wharton's fork) which loads GL functions via
         * eglGetProcAddress instead of glXGetProcAddress (X11).
         */
        fun initialize(width: Int, height: Int): Renderer {
            val context = DirectContext.makeEGL()
            println("[Renderer] Skia DirectContext created (EGL backend)")
            return Renderer(context, width, height)
        }
    }
}
