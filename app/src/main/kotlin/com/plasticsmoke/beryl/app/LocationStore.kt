package com.plasticsmoke.beryl.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import kotlin.math.abs

/**
 * Observer position for the astronomy leaves, from plain android.location (no Play Services
 * dependency). Strategy suited to a watch app — astronomy only cares about km-scale position,
 * not tracking:
 *
 *  - the last good fix persists in SharedPreferences, so one fix ever is enough until you travel;
 *  - on refresh (launch/resume/permission grant), seed from every provider's last-known location
 *    and request a single fresh fix per enabled provider (no continuous updates, no battery cost);
 *  - until any fix exists, fall back to San Francisco (what the engine previews render for).
 *
 * [onChanged] fires on the main thread when the position moves ≥ [SIGNIFICANT_DEG] (~5 km) so
 * the caller can rebuild face environments (observer lat/lon is baked in at registration).
 */
class LocationStore(private val context: Context, private val onChanged: () -> Unit) {

    private val prefs = context.getSharedPreferences("location", Context.MODE_PRIVATE)

    @Volatile var latDeg: Double = Double.NaN
        private set
    @Volatile var lonDeg: Double = Double.NaN
        private set

    /** Meters above sea level from the last fix that carried altitude (persisted). */
    @Volatile var altitudeM: Double = 0.0
        private set

    /** Horizontal 68% accuracy radius (m) of the last LIVE fix this run; NaN before any. */
    @Volatile var accuracyM: Double = Double.NaN
        private set

    val latRad: Double get() = latDeg * Math.PI / 180
    val lonRad: Double get() = lonDeg * Math.PI / 180

    /** True when any location provider is enabled (iOS CLLocationManager locationServicesEnabled). */
    fun servicesEnabled(): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return lm.allProviders.any { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** True when a LIVE fix arrived this run (vs the persisted/fallback "manual" position). */
    val hasLiveFix: Boolean get() = !accuracyM.isNaN()

    /** iOS ECLocationManager accuracyIsGood: `locationOverridden || (0 < err < 101 m)`.
     *  Our persisted-last-fix / SF-fallback position plays iOS's overridden/cached role — with
     *  location off, the iPhone still reports GOOD accuracy (verified: its Uraniborg runs the
     *  fast sidereal-seconds hand). Only a LIVE fix worse than ECDefaultHorizontalError is bad. */
    val goodAccuracy: Boolean get() = accuracyM.isNaN() || (accuracyM > 0 && accuracyM < 101.0)

    init {
        latDeg = prefs.getFloat(KEY_LAT, DEFAULT_LAT.toFloat()).toDouble()
        lonDeg = prefs.getFloat(KEY_LON, DEFAULT_LON.toFloat()).toDouble()
        altitudeM = prefs.getFloat(KEY_ALT, 0f).toDouble()
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Seed from last-known fixes and request one fresh fix per enabled provider. Main thread. */
    fun refresh() {
        if (!hasPermission()) {
            Log.d(TAG, "location: no permission, using ${fmt()}")
            return
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        try {
            lm.allProviders
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { apply(it, "last-known ${it.provider}") }

            for (p in lm.allProviders) {
                if (!runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    lm.getCurrentLocation(p, null, context.mainExecutor) { loc ->
                        loc?.let { apply(it, "fresh $p") }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(p, { loc -> apply(loc, "fresh $p") }, null)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "location refresh denied", e)
        }
    }

    private fun apply(loc: Location, source: String) {
        // Altitude/accuracy update on EVERY fix (AtlantisIV's GPS dial reads them live); only a
        // significant lat/lon move forces the env rebuild.
        if (loc.hasAccuracy()) accuracyM = loc.accuracy.toDouble()
        if (loc.hasAltitude()) {
            altitudeM = loc.altitude
            prefs.edit().putFloat(KEY_ALT, loc.altitude.toFloat()).apply()
        }
        val moved = latDeg.isNaN() ||
            abs(loc.latitude - latDeg) > SIGNIFICANT_DEG || abs(loc.longitude - lonDeg) > SIGNIFICANT_DEG
        if (!moved) return
        latDeg = loc.latitude
        lonDeg = loc.longitude
        prefs.edit()
            .putFloat(KEY_LAT, loc.latitude.toFloat())
            .putFloat(KEY_LON, loc.longitude.toFloat())
            .apply()
        Log.i(TAG, "location: ${fmt()} ($source) — rebuilding faces")
        onChanged()
    }

    private fun fmt() = "%.2f, %.2f".format(latDeg, lonDeg)

    private companion object {
        const val TAG = "Beryl"
        const val KEY_LAT = "latDeg"
        const val KEY_LON = "lonDeg"
        const val KEY_ALT = "altM"
        // Fallback until any fix ever arrives: SF, the engine previews' observer.
        const val DEFAULT_LAT = 37.7749
        const val DEFAULT_LON = -122.4194
        const val SIGNIFICANT_DEG = 0.05   // ~5 km: below this, rise/set shifts are sub-minute
    }
}
