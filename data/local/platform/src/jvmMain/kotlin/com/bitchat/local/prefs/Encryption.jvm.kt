package com.bitchat.local.prefs

import com.microsoft.credentialstorage.SecretStore
import com.microsoft.credentialstorage.StorageProvider
import com.microsoft.credentialstorage.model.StoredCredential
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.io.OutputStream
import java.security.Key
import java.security.SecureRandom
import java.util.prefs.NodeChangeListener
import java.util.prefs.PreferenceChangeEvent
import java.util.prefs.PreferenceChangeListener
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.asKotlinRandom

const val MASTER_KEY_NAME = "bitchatMasterKey"

/**
 * Raised when the JVM desktop build cannot reach an OS-provided secure credential store.
 *
 * **This build** deliberately has no unencrypted fallback: the master key protects Nostr identity
 * keys, the block list and user preferences, so degrading to plaintext silently would be worse
 * than refusing to start. Anyone who genuinely wants unprotected storage has to say so in code,
 * not have it happen to them.
 *
 * The promise is this source set's alone, not the app's: the `linuxArm64` embedded actual
 * (`Encryption.linux.kt`) writes the very same preferences as plain-text files under
 * `~/.bitchat/prefs`, guarded by nothing but 0700 directory permissions. Android and Apple have
 * their own keystores. Say "the desktop build" wherever this guarantee is described.
 */
class SecureStorageUnavailableException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class DesktopEncryptionSettingsFactory(
    private val credentialStorageProvider: () -> SecretStore<StoredCredential>? = {
        // Returns null (not an exception) when no backend qualifies. On Linux that needs
        // libsecret AND an unlocked secret-service collection on a live session bus.
        StorageProvider.getCredentialStorage(true, StorageProvider.SecureOption.REQUIRED)
    }
) : EncryptionSettingsFactory {

    /**
     * Probed once. Three preference classes ([LocalUserPreferences], [LocalSecureIdentityPreferences],
     * [LocalBlockListPreferences]) each build their settings in a property initialiser, so without
     * memoising this a broken host produced three identical failures nested inside three Koin
     * instance-creation stack traces. Now the reason is printed once and the same exception is
     * re-thrown to every caller.
     */
    private val credentialStorage: Result<SecretStore<StoredCredential>> by lazy {
        resolveCredentialStorage()
    }

    override fun createEncrypted(name: String): Settings {
        val store = credentialStorage.getOrThrow()

        // If master key is not found, create one
        val storedMasterKey = store.get(MASTER_KEY_NAME)?.password
        val masterKey = if (storedMasterKey == null) {
            val keyGenerator = KeyGenerator.getInstance("AES")
            val secretKey = keyGenerator.generateKey()
            val keyChars = Base64.encode(secretKey.encoded)
            println("Not found master key for secure storage, creating one")
            store.add(MASTER_KEY_NAME, StoredCredential("bitchat", keyChars.toCharArray()))
            secretKey
        } else {
            println("Found master key for secure storage")
            val decodedKey = Base64.decode(storedMasterKey.concatToString())
            SecretKeySpec(decodedKey, 0, decodedKey.size, "AES")
        }

        // Encrypt the preferences
        return PreferencesSettings(EncryptedPreferences(masterKey))
    }

    private fun resolveCredentialStorage(): Result<SecretStore<StoredCredential>> {
        val failure = try {
            val store = credentialStorageProvider()
            if (store != null) return Result.success(store)
            SecureStorageUnavailableException(secureStorageUnavailableMessage())
        } catch (e: LinkageError) {
            // The backend probes load native code through JNA, so a broken install can raise
            // UnsatisfiedLinkError - a LinkageError, not an Exception. VirtualMachineError
            // (OutOfMemoryError, StackOverflowError) is deliberately not caught here: reporting a
            // dying JVM as "no keyring" would send the user chasing the wrong problem.
            SecureStorageUnavailableException(secureStorageUnavailableMessage(e), e)
        } catch (e: Exception) {
            SecureStorageUnavailableException(secureStorageUnavailableMessage(e), e)
        }
        System.err.println(failure.message)
        return Result.failure(failure)
    }

    internal companion object {
        /**
         * One self-contained, actionable sentence-per-line explanation. It has to read correctly
         * even when Koin wraps it several layers deep in an InstanceCreationException.
         */
        internal fun secureStorageUnavailableMessage(
            cause: Throwable? = null,
            osName: String = System.getProperty("os.name") ?: "unknown OS"
        ): String {
            val reason = if (cause == null) {
                "no OS credential store qualified (StorageProvider.getCredentialStorage returned null)"
            } else {
                "probing the OS credential store failed: ${cause::class.java.name}: ${cause.message}"
            }
            return buildString {
                appendLine("bitchat secure storage unavailable on $osName: $reason.")
                // Scoped to the desktop build on purpose: the embedded linuxArm64 build stores the
                // same preferences as plain-text files, so an app-wide claim here would be false.
                appendLine("The bitchat desktop app stores identity keys and preferences encrypted with a master key")
                appendLine("held in the OS keyring, and will not fall back to unencrypted storage. To fix this on")
                appendLine("Linux you need BOTH of:")
                appendLine("  1. libsecret installed")
                appendLine("     Fedora:        sudo dnf install libsecret gnome-keyring")
                appendLine("     Debian/Ubuntu: sudo apt install libsecret-1-0 gnome-keyring")
                appendLine("  2. a running D-Bus session whose default secret-service collection is UNLOCKED,")
                appendLine("     i.e. a real desktop session with gnome-keyring or KWallet unlocked. Over plain")
                appendLine("     SSH, headless or in a container there is no session bus, the default collection")
                appendLine("     stays locked, and no backend qualifies.")
                append("On macOS this means the login Keychain is unavailable; on Windows, Credential Manager.")
            }
        }
    }
}

class EncryptedPreferences(
    private val secretKey: Key,
    // New node needed to avoid interacting with other non-encrypted preferences
    private val delegate: Preferences = userRoot().node("illyan.butler"),
    javaRandom: SecureRandom = SecureRandom()
) : Preferences() {
    companion object {
        private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
        private const val IV_SIZE_BYTES = 12 // Size for GCM
        private const val TAG_LENGTH_BIT = 128 // Recommended tag length for GCM
    }

    private val random = javaRandom.asKotlinRandom()

    @OptIn(ExperimentalEncodingApi::class)
    private fun decryptWithIV(encryptedData: String?, ivSize: Int = IV_SIZE_BYTES): String? {
        return encryptedData?.let {
            val rawData = Base64.decode(it)
            val iv = rawData.copyOfRange(0, ivSize) // Extract IV from value
            val encryptedValue = rawData.copyOfRange(ivSize, rawData.size)

            val cipher = Cipher.getInstance(AES_GCM_NOPADDING).apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
            }

            cipher.doFinal(encryptedValue).decodeToString()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encryptWithIV(value: String?, iv: ByteArray = random.nextBytes(IV_SIZE_BYTES)): String? {
        return value?.let {
            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))

            val encryptedValue = cipher.doFinal(it.toByteArray())

            // Add IV to the value to retrieve it later
            Base64.encode(iv + encryptedValue)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encryptWithHashedIV(value: String?): String? {
        // IV derived from the encrypted data
        val iv = value?.let { Base64.encode(value.toByteArray()).toByteArray().copyOfRange(0, IV_SIZE_BYTES) }
        return iv?.let { encryptWithIV(value, it) }
    }

    private fun putEncrypted(key: String?, value: String?) {
        delegate.put(encryptWithHashedIV(key), encryptWithIV(value))
    }

    private fun getDecrypted(key: String?): String? {
        return decryptWithIV(delegate.get(encryptWithHashedIV(key), null))
    }

    override fun toString(): String {
        return "EncryptedPreferences"
    }

    override fun put(p0: String?, p1: String?) {
        putEncrypted(p0, p1)
    }

    override fun get(p0: String?, p1: String?): String? {
        // Might sometimes throw an error for the key being null.
        // Not found definitive reason for this exception.
        return getDecrypted(p0) ?: p1
    }

    override fun remove(p0: String?) {
        delegate.remove(encryptWithHashedIV(p0))
    }

    override fun clear() {
        delegate.clear()
    }

    override fun putInt(p0: String?, p1: Int) {
        put(p0, p1.toString())
    }

    override fun getInt(p0: String?, p1: Int): Int {
        return get(p0, null)?.toInt() ?: p1
    }

    override fun putLong(p0: String?, p1: Long) {
        put(p0, p1.toString())
    }

    override fun getLong(p0: String?, p1: Long): Long {
        return get(p0, null)?.toLong() ?: p1
    }

    override fun putBoolean(p0: String?, p1: Boolean) {
        put(p0, p1.toString())
    }

    override fun getBoolean(p0: String?, p1: Boolean): Boolean {
        return get(p0, p1.toString()).toBoolean()
    }

    override fun putFloat(p0: String?, p1: Float) {
        put(p0, p1.toString())
    }

    override fun getFloat(p0: String?, p1: Float): Float {
        return get(p0, null)?.toFloat() ?: p1
    }

    override fun putDouble(p0: String?, p1: Double) {
        put(p0, p1.toString())
    }

    override fun getDouble(p0: String?, p1: Double): Double {
        return get(p0, null)?.toDouble() ?: p1
    }

    override fun putByteArray(p0: String?, p1: ByteArray?) {
        put(p0, p1.toString())
    }

    override fun getByteArray(p0: String?, p1: ByteArray?): ByteArray? {
        return get(p0, null)?.toByteArray() ?: p1
    }

    override fun keys(): Array<String?> {
        return delegate.keys().map { decryptWithIV(it) }.toTypedArray()
    }

    override fun childrenNames(): Array<String> {
        return delegate.childrenNames()
    }

    override fun parent(): Preferences {
        return delegate.parent()
    }

    override fun node(p0: String?): Preferences {
        return delegate.node(p0)
    }

    override fun nodeExists(p0: String?): Boolean {
        return delegate.nodeExists(p0)
    }

    override fun removeNode() {
        delegate.removeNode()
    }

    override fun name(): String {
        return delegate.name()
    }

    override fun absolutePath(): String {
        return delegate.absolutePath()
    }

    override fun isUserNode(): Boolean {
        return delegate.isUserNode
    }

    override fun flush() {
        delegate.flush()
    }

    override fun sync() {
        delegate.sync()
    }

    private val preferenceChangeListeners = mutableMapOf<PreferenceChangeListener, PreferenceChangeListener>()
    override fun addPreferenceChangeListener(p0: PreferenceChangeListener?) {
        p0?.let {
            val listener = PreferenceChangeListener { event ->
                val decryptedKey = decryptWithIV(event.key)
                val decryptedNewValue = decryptWithIV(event.newValue)
                p0.preferenceChange(PreferenceChangeEvent(this, decryptedKey, decryptedNewValue))
            }
            preferenceChangeListeners[it] = listener
            delegate.addPreferenceChangeListener(listener)
        }
    }

    override fun removePreferenceChangeListener(p0: PreferenceChangeListener?) {
        p0?.let {
            val listener = preferenceChangeListeners.remove(p0)
            listener?.let { delegate.removePreferenceChangeListener(it) }
        }
    }

    override fun addNodeChangeListener(p0: NodeChangeListener?) {
        delegate.addNodeChangeListener(p0)
    }

    override fun removeNodeChangeListener(p0: NodeChangeListener?) {
        delegate.removeNodeChangeListener(p0)
    }

    override fun exportNode(p0: OutputStream?) {
        delegate.exportNode(p0)
    }

    override fun exportSubtree(p0: OutputStream?) {
        delegate.exportSubtree(p0)
    }
}
