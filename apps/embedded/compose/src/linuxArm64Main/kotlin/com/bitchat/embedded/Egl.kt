@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bitchat.embedded

import egl.EGLConfig
import egl.EGLConfigVar
import egl.EGLContext
import egl.EGLDisplay
import egl.EGLSurface
import egl.EGL_BLUE_SIZE
import egl.EGL_CLIENT_APIS
import egl.EGL_GREEN_SIZE
import egl.EGL_NATIVE_VISUAL_ID
import egl.EGL_NONE
import egl.EGL_NO_CONTEXT
import egl.EGL_NO_DISPLAY
import egl.EGL_NO_SURFACE
import egl.EGL_OPENGL_ES_API
import egl.EGL_RED_SIZE
import egl.EGL_RENDERABLE_TYPE
import egl.EGL_SURFACE_TYPE
import egl.EGL_VENDOR
import egl.EGL_VERSION
import egl.EGL_WINDOW_BIT
import egl.eglBindAPI
import egl.eglChooseConfig
import egl.eglCreateContext
import egl.eglCreatePlatformWindowSurface
import egl.eglCreateWindowSurface
import egl.eglDestroyContext
import egl.eglDestroySurface
import egl.eglGetConfigAttrib
import egl.eglGetError
import egl.eglGetPlatformDisplay
import egl.eglGetProcAddress
import egl.eglInitialize
import egl.eglMakeCurrent
import egl.eglQueryString
import egl.eglSwapBuffers
import egl.eglTerminate
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * EGL context and surface initialization on a GBM device.
 * Sets up an OpenGL ES 2.0 rendering context for Compose/Skia.
 */
class Egl private constructor(
    val display: EGLDisplay?,
    val context: EGLContext?,
    val surface: EGLSurface?,
    val config: EGLConfig?,
) {
    fun makeCurrent(): Boolean {
        return eglMakeCurrent(display, surface, surface, context) == EGL_TRUE
    }

    fun swapBuffers(): Boolean {
        return eglSwapBuffers(display, surface) == EGL_TRUE
    }

    fun cleanup() {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
        if (surface != EGL_NO_SURFACE) {
            eglDestroySurface(display, surface)
        }
        if (context != EGL_NO_CONTEXT) {
            eglDestroyContext(display, context)
        }
        eglTerminate(display)
    }

    companion object {
        private const val EGL_TRUE = 1u
        private const val EGL_PLATFORM_GBM_KHR = 0x31D7
        private const val GBM_FORMAT_XRGB8888 = 0x34325258
        private const val GBM_FORMAT_ARGB8888 = 0x34325241
        private const val MAX_CONFIGS = 64

        // EGL context attributes
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        private const val EGL_OPENGL_ES3_BIT = 0x00000040

        private fun eglErrorString(): String {
            val err = eglGetError()
            return "0x${err.toString(16)}"
        }

        /**
         * Query and print GL version info using eglGetProcAddress.
         * This verifies that GL functions are accessible.
         */
        private fun printGlInfo() {
            // Get glGetString function pointer via EGL
            val glGetStringPtr = eglGetProcAddress("glGetString")
            if (glGetStringPtr == null) {
                println("[GL] WARNING: eglGetProcAddress(glGetString) returned null")
                return
            }

            // Cast to function type and call
            @Suppress("UNCHECKED_CAST")
            val glGetString = glGetStringPtr.reinterpret<CFunction<(UInt) -> CPointer<ByteVar>?>>()

            val GL_VENDOR = 0x1F00u
            val GL_RENDERER = 0x1F01u
            val GL_VERSION = 0x1F02u

            val vendor = glGetString(GL_VENDOR)?.toKString() ?: "unknown"
            val renderer = glGetString(GL_RENDERER)?.toKString() ?: "unknown"
            val version = glGetString(GL_VERSION)?.toKString() ?: "unknown"

            println("[GL] Vendor: $vendor")
            println("[GL] Renderer: $renderer")
            println("[GL] Version: $version")
        }

        fun initialize(gbm: Gbm): Egl {
            // Get EGL display from GBM device
            val display = eglGetPlatformDisplay(
                EGL_PLATFORM_GBM_KHR.toUInt(),
                gbm.device,
                null,
            ).also {
                if (it == EGL_NO_DISPLAY) {
                    error("Failed to get EGL display from GBM device (${eglErrorString()})")
                }
            }

            // Initialize EGL
            memScoped {
                val major = alloc<IntVar>()
                val minor = alloc<IntVar>()
                if (eglInitialize(display, major.ptr, minor.ptr) != EGL_TRUE) {
                    error("Failed to initialize EGL (${eglErrorString()})")
                }
                println("[EGL] Initialized EGL ${major.value}.${minor.value}")
            }

            // Print driver info for diagnostics
            val vendor = eglQueryString(display, EGL_VENDOR)?.toKString() ?: "unknown"
            val version = eglQueryString(display, EGL_VERSION)?.toKString() ?: "unknown"
            val apis = eglQueryString(display, EGL_CLIENT_APIS)?.toKString() ?: "unknown"
            println("[EGL] Vendor: $vendor")
            println("[EGL] Version: $version")
            println("[EGL] Client APIs: $apis")

            // Bind OpenGL ES API - Mali-G31 GPU only supports GLES, not desktop GL
            if (eglBindAPI(EGL_OPENGL_ES_API.toUInt()) != EGL_TRUE) {
                error("Failed to bind OpenGL ES API (${eglErrorString()})")
            }

            // Choose EGL config matching our GBM surface format.
            // We must find a config whose EGL_NATIVE_VISUAL_ID matches the
            // GBM format, otherwise eglCreatePlatformWindowSurface fails
            // with EGL_BAD_MATCH.
            val config = memScoped {
                val configAttribs = intArrayOf(
                    EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                    EGL_RED_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,  // GLES 3.x for Skia
                    EGL_NONE,
                )

                val configs = allocArray<EGLConfigVar>(MAX_CONFIGS)
                val numConfigs = alloc<IntVar>()

                configAttribs.usePinned { pinned ->
                    if (eglChooseConfig(display, pinned.addressOf(0), configs, MAX_CONFIGS, numConfigs.ptr) != EGL_TRUE) {
                        error("Failed to choose EGL config (${eglErrorString()})")
                    }
                }
                val count = numConfigs.value
                if (count == 0) {
                    error("No matching EGL configs found")
                }
                println("[EGL] Found $count candidate config(s), scanning for GBM format match...")

                // Iterate configs to find one whose native visual ID matches
                // our GBM surface format (XRGB8888). Fall back to ARGB8888.
                val value = alloc<IntVar>()
                var matchedConfig: EGLConfig? = null
                var argbFallback: EGLConfig? = null

                for (i in 0 until count) {
                    val cfg = configs[i]
                    eglGetConfigAttrib(display, cfg, EGL_NATIVE_VISUAL_ID, value.ptr)
                    val visualId = value.value
                    if (i < 5) {
                        println("[EGL]   config[$i] native_visual_id=0x${visualId.toUInt().toString(16)}")
                    }
                    if (visualId == GBM_FORMAT_XRGB8888) {
                        matchedConfig = cfg
                        break
                    }
                    if (visualId == GBM_FORMAT_ARGB8888 && argbFallback == null) {
                        argbFallback = cfg
                    }
                }

                val chosen = matchedConfig ?: argbFallback
                if (chosen == null) {
                    // Print all visual IDs for debugging
                    for (i in 0 until count) {
                        eglGetConfigAttrib(display, configs[i], EGL_NATIVE_VISUAL_ID, value.ptr)
                        println("[EGL]   config[$i] native_visual_id=0x${value.value.toUInt().toString(16)}")
                    }
                    error("No EGL config matches GBM format XRGB8888 (0x${GBM_FORMAT_XRGB8888.toUInt().toString(16)}) or ARGB8888")
                }

                eglGetConfigAttrib(display, chosen, EGL_NATIVE_VISUAL_ID, value.ptr)
                val chosenVisualId = value.value.toUInt()
                println("[EGL] Selected config with native_visual_id=0x${chosenVisualId.toString(16)}")
                if (matchedConfig != null) {
                    println("[EGL] Exact XRGB8888 match")
                } else {
                    println("[EGL] Using ARGB8888 fallback — GBM surface will be recreated")
                }
                chosen
            }

            // If the selected config's native visual ID differs from the GBM
            // surface format (XRGB8888), recreate the GBM surface to match.
            memScoped {
                val visualId = alloc<IntVar>()
                eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, visualId.ptr)
                val configFormat = visualId.value.toUInt()
                if (configFormat.toInt() != GBM_FORMAT_XRGB8888) {
                    println("[EGL] Config format 0x${configFormat.toString(16)} differs from GBM — recreating GBM surface")
                    gbm.recreateSurface(configFormat)
                }
            }

            // Create EGL context for OpenGL ES 3.x
            val context = memScoped {
                // Request GLES 3.2 (Mali-G31 supports up to GLES 3.2)
                val contextAttribs = intArrayOf(
                    EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL_NONE,
                )
                var ctx = contextAttribs.usePinned { pinned ->
                    eglCreateContext(display, config, EGL_NO_CONTEXT, pinned.addressOf(0))
                }
                if (ctx == EGL_NO_CONTEXT) {
                    // Fall back to GLES 2.0 if 3.x fails
                    println("[EGL] GLES 3.x context failed (${eglErrorString()}), trying GLES 2.0...")
                    val es2Attribs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE)
                    ctx = es2Attribs.usePinned { es2 ->
                        eglCreateContext(display, config, EGL_NO_CONTEXT, es2.addressOf(0))
                    }
                    if (ctx == EGL_NO_CONTEXT) {
                        error("Failed to create EGL context (${eglErrorString()})")
                    }
                }
                ctx
            }
            println("[EGL] Context created")

            // Create EGL window surface from GBM surface.
            // Try eglCreatePlatformWindowSurface (EGL 1.5) first, then
            // fall back to eglCreateWindowSurface (legacy).
            var surface = eglCreatePlatformWindowSurface(
                display,
                config,
                gbm.surface,
                null,
            )
            if (surface == EGL_NO_SURFACE) {
                val err1 = eglErrorString()
                println("[EGL] eglCreatePlatformWindowSurface failed ($err1), trying legacy eglCreateWindowSurface...")

                surface = eglCreateWindowSurface(
                    display,
                    config,
                    gbm.surface.rawValue.toLong().toULong(),
                    null,
                )
                if (surface == EGL_NO_SURFACE) {
                    val err2 = eglErrorString()
                    error("Failed to create EGL window surface. " +
                        "eglCreatePlatformWindowSurface error=$err1, " +
                        "eglCreateWindowSurface error=$err2")
                }
                println("[EGL] Created surface via legacy eglCreateWindowSurface")
            } else {
                println("[EGL] Created surface via eglCreatePlatformWindowSurface")
            }

            // Make context current
            if (eglMakeCurrent(display, surface, surface, context) != EGL_TRUE) {
                error("Failed to make EGL context current (${eglErrorString()})")
            }

            println("[EGL] OpenGL ES context ready")

            // Query GL info to verify context is working
            printGlInfo()

            return Egl(
                display = display,
                context = context,
                surface = surface,
                config = config,
            )
        }
    }
}
