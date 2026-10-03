@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.crypto

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import libsodium.randombytes_implementation
import libsodium.randombytes_implementation_name
import libsodium.randombytes_internal_implementation
import libsodium.randombytes_set_implementation
import libsodium.randombytes_sysrandom_implementation
import libsodium.sodium_init
import platform.posix.size_t

/**
 * Makes a test's own generator libsodium's CSPRNG for the duration of [block], through
 * `randombytes_set_implementation` (libsodium's own hook for replacing it), and records every
 * `randombytes_buf` draw.
 *
 * Nothing is injected into production code. Whatever asks libsodium for random bytes in the
 * ordinary way gets [InstalledCsprng.pattern]; whatever draws randomness some other way is simply
 * not seen. That is the point: a key that is not the pattern did not come from libsodium.
 *
 * libsodium's original generator is put back afterwards, whether or not [block] throws, so the
 * rest of the test binary draws real randomness again.
 */
internal fun <T> withInstalledCsprng(block: (InstalledCsprng) -> T): T {
    // The first sodium_init draws libsodium's allocation canary through whatever generator is
    // installed. Run it now, so the recording holds only what the code under test asked for.
    check(sodium_init() >= 0) { "sodium_init failed" }

    // libsodium can name its current generator but not hand it back, so find it among the ones
    // it exports. Refuse to install over anything else: it could not be restored.
    val platformName = randombytes_implementation_name()?.toKString()
    val platform = listOf(randombytes_sysrandom_implementation, randombytes_internal_implementation)
        .firstOrNull { it.implementation_name?.invoke()?.toKString() == platformName }
        ?: error("libsodium's generator \"$platformName\" is not one this test could put back")

    return memScoped {
        installedName = InstalledCsprng.NAME.cstr.ptr
        InstalledCsprng.clear()
        val installed = alloc<randombytes_implementation> {
            implementation_name = staticCFunction(::installedImplementationName)
            random = staticCFunction(::installedRandom)
            stir = null
            uniform = null
            buf = staticCFunction(::installedBuf)
            close = null
        }
        check(randombytes_set_implementation(installed.ptr) == 0) { "randombytes_set_implementation failed" }
        val result = try {
            // A failed install would otherwise show up as every secret "not from libsodium",
            // blaming the code under test for what went wrong here.
            check(randombytes_implementation_name()?.toKString() == InstalledCsprng.NAME) {
                "libsodium did not switch to the installed test generator"
            }
            block(InstalledCsprng)
        } finally {
            randombytes_set_implementation(platform.ptr)
            installedName = null
        }
        check(randombytes_implementation_name()?.toKString() == platformName) {
            "libsodium's own generator \"$platformName\" was not put back"
        }
        result
    }
}

/**
 * The generator libsodium draws from inside [withInstalledCsprng].
 *
 * Every draw is [pattern] of the requested size. libsodium's `buf` cannot report a failure (its own
 * implementations abort), so unlike the JVM stand-in this one has no failing mode.
 */
internal object InstalledCsprng {
    const val NAME = "bitchat-test-csprng"

    private val recorded = mutableListOf<ByteArray>()

    /** Every block of bytes handed out through `randombytes_buf`, in order. */
    val draws: List<ByteArray>
        get() = recorded.map { it.copyOf() }

    /**
     * What a draw of [size] bytes yields: 0x01, 0x02, 0x03, ... Fixed, so a secret can be compared
     * with the exact bytes it must be. 32 of them, read big-endian, are a valid secp256k1 private
     * key, so generateKeyPair takes the first draw.
     */
    fun pattern(size: Int): ByteArray = ByteArray(size) { (it + 1).toByte() }

    internal fun clear() = recorded.clear()

    internal fun record(bytes: ByteArray) {
        recorded += bytes
    }
}

// The functions below are C function pointers and cannot capture, so they reach their state at
// top level: the name here, the draws in InstalledCsprng.
private var installedName: CPointer<ByteVar>? = null

private fun installedImplementationName(): CPointer<ByteVar>? = installedName

// Required by libsodium for randombytes_random and randombytes_uniform, which the code under test
// does not use. Fixed like everything else here: the first four pattern bytes.
private fun installedRandom(): UInt = 0x01020304u

private fun installedBuf(buf: COpaquePointer?, size: size_t) {
    val bytes = InstalledCsprng.pattern(size.toInt())
    val out = buf!!.reinterpret<ByteVar>()
    bytes.forEachIndexed { index, byte -> out[index] = byte }
    InstalledCsprng.record(bytes)
}
