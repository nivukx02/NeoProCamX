package com.neoproc.neoproccamx.utils

/**
 * PreferencesTypedFastPathFlag — reversible O(1) fast-path (DEFAULT OFF) for [PreferencesManager]'s
 * compat getters.
 *
 * WHY: every getBooleanCompat / getStringCompat / getIntCompat / getLongCompat / getFloatCompat call
 * reads `prefs.all[key]`, and Android's SharedPreferencesImpl.getAll() COPIES the entire preferences map
 * (under the class lock) on every single lookup. With dozens of settings read per request rebuild / mode
 * toggle / startup migration that is O(prefsSize) work for one property read. When ON, each compat getter
 * first tries the typed accessor (prefs.getInt/getString/getLong/getFloat/getBoolean) — an O(1) map get —
 * and only falls back to the map-copy coercion path when the stored value is a different type
 * (ClassCastException), which is exactly the defensive case the compat coercion exists for.
 *
 * NO-DOWNGRADE: the fast path returns the same value as the map path for every same-type read and every
 * absent key, and on a type mismatch it runs the IDENTICAL existing coercion branch. So the returned
 * value is byte-identical whether the flag is ON or OFF; only the number of full-map copies changes.
 * Nothing about resolution/fps/bitrate/bit-depth/feature/look is touched.
 *
 * Default OFF (low-priority polish); the byte-identical map path stays the shipped behavior. Read live
 * per compat-getter call, so a flip takes effect on the next preference read:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.prefs_typed_fast_path true
 */
object PreferencesTypedFastPathFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
