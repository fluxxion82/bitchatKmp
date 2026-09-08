package com.bitchat.local.prefs

/**
 * How a preference store presented itself when it was opened.
 *
 * The point of this type is to keep "nothing is stored here yet" apart from "something is
 * stored here but I could not read all of it". Both look identical to a caller that asks for a
 * key and gets null back, and mistaking the second for the first means minting a replacement
 * identity on top of a live one.
 */
enum class PreferenceStoreState {
    /** Nothing is stored. A genuine first run (or a deliberate wipe): creating keys is correct. */
    FIRST_RUN,

    /** The store loaded cleanly and holds at least one key. A missing key is a real gap. */
    POPULATED,

    /** The store did not load cleanly. A missing key may simply have been lost. */
    UNREADABLE;

    companion object {
        /**
         * The whole first-run-versus-load-failure decision, as a pure function.
         *
         * @param damage what the backing store reported wrong with itself, empty when intact.
         * @param isEmpty whether the store holds no keys at all.
         */
        fun of(damage: List<String>, isEmpty: Boolean): PreferenceStoreState = when {
            damage.isNotEmpty() -> UNREADABLE
            isEmpty -> FIRST_RUN
            else -> POPULATED
        }
    }
}

/**
 * Implemented by [com.russhwolf.settings.Settings] backends that can tell whether their backing
 * store loaded cleanly.
 *
 * Only the file-backed embedded store implements this. Keychain- and
 * EncryptedSharedPreferences-backed stores surface a read failure as an exception instead, so
 * they never hand a caller a half-loaded store to begin with.
 */
interface HealthReportingSettings {
    /**
     * Descriptions of anything wrong with the backing store, empty when it is intact.
     *
     * @see FlatFileContent.damage
     */
    val storeDamage: List<String>
}
