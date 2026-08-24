package com.sapphire.data.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * One persisted settings value: SharedPreferences read/write + a hot [Flow], behind a
 * tiny surface. This is the single owner of the store house pattern (read once at
 * construction → [MutableStateFlow]; every setter edits prefs on IO then updates the
 * flow) — the per-concern stores below are key definitions over this primitive.
 *
 * Not a seam: internal to core-data, no port. Values are whatever the enclosing store's
 * port declares; [enum] parses stored names defensively (unknown name → default) so a
 * renamed/removed enum constant degrades instead of crashing.
 */
internal class PrefsEntry<T : Any> private constructor(
    private val prefs: SharedPreferences,
    private val key: String,
    read: () -> T,
    private val canonicalize: (T) -> T,
    private val write: SharedPreferences.Editor.(key: String, value: T) -> Unit,
) {

    private val _flow = MutableStateFlow(read())
    /** Current value without collecting — hot-path sync snapshot for callers like BrowserConfig.baseUrl. */
    val current: T get() = _flow.value

    val flow: Flow<T> = _flow.asStateFlow()

    suspend fun set(value: T) {
        val v = canonicalize(value)
        withContext(Dispatchers.IO) {
            prefs.edit { write(key, v) }
        }
        _flow.value = v
    }

    companion object {
        fun bool(prefs: SharedPreferences, key: String, default: Boolean) =
            PrefsEntry(prefs, key, { prefs.getBoolean(key, default) }, { it }, { k, v -> putBoolean(k, v) })

        fun int(prefs: SharedPreferences, key: String, default: Int) =
            PrefsEntry(prefs, key, { prefs.getInt(key, default) }, { it }, { k, v -> putInt(k, v) })

        inline fun <reified T : Enum<T>> enum(prefs: SharedPreferences, key: String, default: T) =
            PrefsEntry(
                prefs,
                key,
                read = { prefs.getString(key, null)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default },
                canonicalize = { it },
                write = { k, v -> putString(k, v.name) },
            )

        /** [canonicalize] applies to both stored and emitted values (e.g. URL trimming). */
        fun string(
            prefs: SharedPreferences,
            key: String,
            default: String,
            canonicalize: (String) -> String = { it },
        ) = PrefsEntry(
            prefs,
            key,
            read = { prefs.getString(key, null)?.let(canonicalize) ?: default },
            canonicalize = canonicalize,
            write = { k, v -> putString(k, v) },
        )
    }
}
