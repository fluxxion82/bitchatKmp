package com.bitchat.local.internal

import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

private inline fun <T> SharedPreferences.delegate(
    key: String,
    crossinline getter: SharedPreferences.(String) -> T?,
    crossinline setter: SharedPreferences.Editor.(String, T) -> SharedPreferences.Editor
): ReadWriteProperty<Any, T?> {
    return object : ReadWriteProperty<Any, T?> {
        override fun getValue(thisRef: Any, property: KProperty<*>) =
            takeIf { contains(key) }?.let { getter(key) }

        override fun setValue(thisRef: Any, property: KProperty<*>, value: T?) =
            edit().apply {
                if (value == null) {
                    remove(key)
                } else {
                    setter(key, value)
                }
            }.apply()
    }
}

fun SharedPreferences.bool(key: String) =
    delegate(key, { getBoolean(key, false) }, SharedPreferences.Editor::putBoolean)

fun SharedPreferences.int(key: String) =
    delegate(key, { getInt(key, Int.MIN_VALUE) }, SharedPreferences.Editor::putInt)

fun SharedPreferences.long(key: String) =
    delegate(key, { getLong(key, Long.MIN_VALUE) }, SharedPreferences.Editor::putLong)

fun SharedPreferences.string(key: String) =
    delegate(key, { getString(key, null) }, SharedPreferences.Editor::putString)
