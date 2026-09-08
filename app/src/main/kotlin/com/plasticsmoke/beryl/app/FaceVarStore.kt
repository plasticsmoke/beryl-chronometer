package com.plasticsmoke.beryl.app

import android.content.Context

/**
 * Persisted per-face env variables — the Android side of iOS's per-watch NSUserDefaults slots
 * (saveBody / storePersistentValue). Values overlay the manifest `vars` when a face loads, so
 * Miami's selected planet survives a relaunch.
 */
class FaceVarStore(context: Context) {
    private val prefs = context.getSharedPreferences("face_vars", Context.MODE_PRIVATE)

    fun put(face: String, name: String, value: Double) {
        prefs.edit().putLong(key(face, name), value.toRawBits()).apply()
    }

    /** All persisted variables for [face]. */
    fun load(face: String): Map<String, Double> {
        val prefix = "$face."
        return prefs.all.keys.filter { it.startsWith(prefix) }.associate { k ->
            k.removePrefix(prefix) to Double.fromBits(prefs.getLong(k, 0L))
        }
    }

    private fun key(face: String, name: String) = "$face.$name"
}
