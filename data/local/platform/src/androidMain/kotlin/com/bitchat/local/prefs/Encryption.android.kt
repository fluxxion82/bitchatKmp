package com.bitchat.local.prefs

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SettingsListener

class AndroidEncryptionSettingsFactory(val context: Context) : EncryptionSettingsFactory {
    override fun createEncrypted(name: String): Settings {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        return AndroidEncryptedSettings(prefs)
    }
}

class AndroidEncryptedSettings(
    private val delegate: SharedPreferences,
    private val commit: Boolean = false
) : ObservableSettings {
    public override val keys: Set<String> get() = delegate.all.keys
    public override val size: Int get() = delegate.all.size

    @Suppress("NOTHING_TO_INLINE")
    private inline fun SharedPreferences.Editor.applyOrCommit() {
        if (commit) {
            commit()
        } else {
            apply()
        }
    }

    @SuppressLint("CommitPrefEdits")
    public override fun clear() {
        // Note: we call remove() on all keys instead of calling clear() in order to match listener behavior to iOS
        // See issue #9
        delegate.edit().apply {
            for (key in delegate.all.keys) {
                remove(key)
            }
        }.applyOrCommit()
    }

    @SuppressLint("CommitPrefEdits")
    public override fun remove(key: String): Unit = delegate.edit().remove(key).applyOrCommit()

    public override fun hasKey(key: String): Boolean = delegate.contains(key)

    @SuppressLint("CommitPrefEdits")
    public override fun putInt(key: String, value: Int): Unit = delegate.edit().putInt(key, value).applyOrCommit()

    public override fun getInt(key: String, defaultValue: Int): Int = delegate.getInt(key, defaultValue)

    public override fun getIntOrNull(key: String): Int? =
        if (delegate.contains(key)) delegate.getInt(key, 0) else null

    @SuppressLint("CommitPrefEdits")
    public override fun putLong(key: String, value: Long): Unit = delegate.edit().putLong(key, value).applyOrCommit()

    public override fun getLong(key: String, defaultValue: Long): Long = delegate.getLong(key, defaultValue)

    public override fun getLongOrNull(key: String): Long? =
        if (delegate.contains(key)) delegate.getLong(key, 0L) else null

    @SuppressLint("CommitPrefEdits")
    public override fun putString(key: String, value: String): Unit =
        delegate.edit().putString(key, value).applyOrCommit()

    public override fun getString(key: String, defaultValue: String): String =
        delegate.getString(key, defaultValue) ?: defaultValue

    public override fun getStringOrNull(key: String): String? =
        if (delegate.contains(key)) delegate.getString(key, "") else null

    @SuppressLint("CommitPrefEdits")
    public override fun putFloat(key: String, value: Float): Unit = delegate.edit().putFloat(key, value).applyOrCommit()

    public override fun getFloat(key: String, defaultValue: Float): Float = delegate.getFloat(key, defaultValue)

    public override fun getFloatOrNull(key: String): Float? =
        if (delegate.contains(key)) delegate.getFloat(key, 0f) else null

    @SuppressLint("CommitPrefEdits")
    public override fun putDouble(key: String, value: Double): Unit =
        delegate.edit().putLong(key, value.toRawBits()).applyOrCommit()

    public override fun getDouble(key: String, defaultValue: Double): Double =
        Double.fromBits(delegate.getLong(key, defaultValue.toRawBits()))

    public override fun getDoubleOrNull(key: String): Double? =
        if (delegate.contains(key)) Double.fromBits(delegate.getLong(key, 0.0.toRawBits())) else null

    @SuppressLint("CommitPrefEdits")
    public override fun putBoolean(key: String, value: Boolean): Unit =
        delegate.edit().putBoolean(key, value).applyOrCommit()

    public override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        delegate.getBoolean(key, defaultValue)

    public override fun getBooleanOrNull(key: String): Boolean? =
        if (delegate.contains(key)) delegate.getBoolean(key, false) else null

    public override fun addIntListener(
        key: String,
        defaultValue: Int,
        callback: (Int) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getInt(key, defaultValue)) }

    public override fun addLongListener(
        key: String,
        defaultValue: Long,
        callback: (Long) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getLong(key, defaultValue)) }

    public override fun addStringListener(
        key: String,
        defaultValue: String,
        callback: (String) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getString(key, defaultValue)) }

    public override fun addFloatListener(
        key: String,
        defaultValue: Float,
        callback: (Float) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getFloat(key, defaultValue)) }

    public override fun addDoubleListener(
        key: String,
        defaultValue: Double,
        callback: (Double) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getDouble(key, defaultValue)) }

    public override fun addBooleanListener(
        key: String,
        defaultValue: Boolean,
        callback: (Boolean) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getBoolean(key, defaultValue)) }

    public override fun addIntOrNullListener(
        key: String,
        callback: (Int?) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getIntOrNull(key)) }

    public override fun addLongOrNullListener(
        key: String,
        callback: (Long?) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getLongOrNull(key)) }

    public override fun addStringOrNullListener(
        key: String,
        callback: (String?) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getStringOrNull(key)) }

    public override fun addFloatOrNullListener(
        key: String,
        callback: (Float?) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getFloatOrNull(key)) }

    public override fun addDoubleOrNullListener(
        key: String,
        callback: (Double?) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getDoubleOrNull(key)) }

    public override fun addBooleanOrNullListener(
        key: String,
        callback: (Boolean?) -> Unit
    ): SettingsListener =
        addListener(key) { callback(getBooleanOrNull(key)) }

    private fun addListener(key: String, callback: () -> Unit): SettingsListener {
        var prev = delegate.all[key]

        val prefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _: SharedPreferences, updatedKey: String? ->
                if (updatedKey != key) return@OnSharedPreferenceChangeListener

                /*
                 According to the OnSharedPreferenceChangeListener contract, we might get called for an update even
                 if the value didn't change. We hold a cache to ensure that the user-supplied callback only updates on
                 changes, in order to ensure that we match iOS behavior
                 */
                val current = delegate.all[key]
                if (prev != current) {
                    callback()
                    prev = current
                }
            }
        delegate.registerOnSharedPreferenceChangeListener(prefsListener)
        return Listener(delegate, prefsListener)
    }

    /**
     * A handle to a listener instance returned by one of the addListener methods of [ObservableSettings], so it can be
     * deactivated as needed.
     *
     * On the Android platform, this is a wrapper around [SharedPreferences.OnSharedPreferenceChangeListener].
     */
    public class Listener internal constructor(
        private val preferences: SharedPreferences,
        private val listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) : SettingsListener {
        public override fun deactivate() {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
