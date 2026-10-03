package com.bitchat.crypto

import java.security.Provider
import java.security.SecureRandom
import java.security.SecureRandomSpi
import java.security.Security

/**
 * Makes a test's own generator the platform CSPRNG for the duration of [block], through the JCA
 * provider list (the mechanism the platform itself uses to choose one), and records every draw.
 *
 * Nothing is injected into production code. Whatever asks the platform for a `SecureRandom` in
 * the ordinary way gets this one; whatever draws randomness some other way is simply not seen.
 * That is the point: a key that is not among [InstalledCsprng.draws] did not come from the
 * platform CSPRNG.
 *
 * @param fill produces the bytes. By default it is the real platform CSPRNG, so keys stay real.
 */
internal fun <T> withInstalledCsprng(
    fill: ((ByteArray) -> Unit)? = null,
    block: (InstalledCsprng) -> T,
): T {
    val platform = SecureRandom() // resolved before installing: the real platform default
    val installed = InstalledCsprng(fill ?: platform::nextBytes)
    check(Security.insertProviderAt(installed, 1) == 1) {
        "could not install the test CSPRNG ahead of the platform's"
    }
    try {
        // Without this every assertion below could pass vacuously, against a generator that
        // production never consults.
        check(SecureRandom().provider === installed) {
            "JCA did not resolve the default SecureRandom to the installed test CSPRNG"
        }
        return block(installed)
    } finally {
        Security.removeProvider(installed.name)
    }
}

// The numeric-version constructor is deprecated on the JDK, but it is the only one android.jar
// declares, and the Android host tests compile against that.
@Suppress("DEPRECATION")
internal class InstalledCsprng(private val fill: (ByteArray) -> Unit) :
    Provider("BitchatTestCsprng", 1.0, "Test stand-in for the platform CSPRNG; records every draw") {

    private val recorded = mutableListOf<ByteArray>()

    /** Every block of bytes handed out, in order. */
    val draws: List<ByteArray>
        get() = synchronized(recorded) { recorded.map { it.copyOf() } }

    init {
        putService(
            object : Provider.Service(
                this, "SecureRandom", "BitchatTest", Spi::class.java.name, null, mapOf("ThreadSafe" to "true"),
            ) {
                override fun newInstance(constructorParameter: Any?): Any = Spi()
            },
        )
    }

    private inner class Spi : SecureRandomSpi() {
        override fun engineSetSeed(seed: ByteArray) = Unit

        override fun engineNextBytes(bytes: ByteArray) {
            fill(bytes)
            synchronized(recorded) { recorded += bytes.copyOf() }
        }

        override fun engineGenerateSeed(numBytes: Int): ByteArray = ByteArray(numBytes).also(::engineNextBytes)
    }

    companion object {
        /**
         * The bytes a [size]-byte draw yields under [fillWithPattern]: 0x01, 0x02, ... The Apple
         * stand-in hands out the same, so a test that fixes the generator this way can assert
         * the same known answer on both platforms.
         */
        fun pattern(size: Int): ByteArray = ByteArray(size) { (it + 1).toByte() }

        /** A `fill` that makes every draw [pattern]. */
        val fillWithPattern: (ByteArray) -> Unit = { bytes -> pattern(bytes.size).copyInto(bytes) }

        /** A `fill` that makes the one draw exactly [draw], for replaying a published test vector. */
        fun fillWith(draw: ByteArray): (ByteArray) -> Unit = { bytes ->
            require(bytes.size == draw.size) {
                "the test generator holds ${draw.size} bytes but ${bytes.size} were drawn"
            }
            draw.copyInto(bytes)
        }
    }
}
