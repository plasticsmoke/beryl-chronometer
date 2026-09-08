package com.plasticsmoke.beryl.astro

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Willmann-Bell Moon series (Chapront), ported from chronometer-web `src/astronomy/wb-moon.ts`.
 * Cache-free. [t] is julianCenturies since J2000.0 (TDT); [p] is precision (WB_LOW/MID/FULL).
 */

private const val DEG = PI / 180

/** Lunar longitude in DEGREES. */
private fun lunarLongitudeForTDT(t: Double, p: Int): Double {
    val L = LunarTables
    val t2 = t * t; val t3 = t * t2; val t4 = t2 * t2
    var sv = 0.0
    for (i in 0 until L.Nv[p]) {
        val arg = L.sv_an0[i] + L.sv_an1[i] * t + L.sv_an2[i] * t2 * 1e-4 + L.sv_an3[i] * t3 * 1e-6 + L.sv_an4[i] * t4 * 1e-8
        sv += L.sv_vn[i] * sin(DEG * arg)
    }
    var sv1 = 0.0
    for (i in 0 until L.N1v[p]) sv1 += L.sv1_vn[i] * sin(DEG * (L.sv1_an0[i] + L.sv1_an1[i] * t))
    var sv2 = 0.0
    for (i in 0 until L.N2v[p]) sv2 += L.sv2_vn[i] * sin(DEG * (L.sv2_an0[i] + L.sv2_an1[i] * t))
    var sv3 = 0.0
    for (i in 0 until L.N3v[p]) sv3 += L.sv3_vn[i] * sin(DEG * (L.sv3_an0[i] + L.sv3_an1[i] * t))

    val v = 218.31665436 + 481267.88134240 * t - 13.268e-4 * t2 + 1.856e-6 * t3 - 1.534e-8 * t4 +
        sv + 1e-3 * (sv1 + t * sv2 + t2 * 1e-4 * sv3)
    return esFmod(v, 360.0)
}

/** Lunar latitude in DEGREES. */
private fun lunarLatitudeForTDT(t: Double, p: Int): Double {
    val L = LunarTables
    val t2 = t * t; val t3 = t * t2; val t4 = t2 * t2
    var su = 0.0
    for (i in 0 until L.Nu[p]) {
        val arg = L.su_bn0[i] + L.su_bn1[i] * t + L.su_bn2[i] * t2 * 1e-4 + L.su_bn3[i] * t3 * 1e-6 + L.su_bn4[i] * t4 * 1e-8
        su += L.su_un[i] * sin(DEG * arg)
    }
    var su1 = 0.0
    for (i in 0 until L.N1u[p]) su1 += L.su1_un[i] * sin(DEG * (L.su1_bn0[i] + L.su1_bn1[i] * t))
    var su2 = 0.0
    for (i in 0 until L.N2u[p]) su2 += L.su2_un[i] * sin(DEG * (L.su2_bn0[i] + L.su2_bn1[i] * t))
    var su3 = 0.0
    for (i in 0 until L.N3u[p]) su3 += L.su3_un[i] * sin(DEG * (L.su3_bn0[i] + L.su3_bn1[i] * t))

    var u = su + 1e-3 * (su1 + t * su2 + t2 * 1e-4 * su3)
    u = esFmod(u, 360.0)
    if (u > 180) u -= 360
    return u
}

/** Lunar distance in km. */
private fun lunarDistanceForTDT(t: Double, p: Int): Double {
    val L = LunarTables
    val t2 = t * t; val t3 = t * t2; val t4 = t2 * t2
    var sr = 0.0
    for (i in 0 until L.Nr[p]) {
        val arg = L.sr_dn0[i] + L.sr_dn1[i] * t + L.sr_dn2[i] * t2 * 1e-4 + L.sr_dn3[i] * t3 * 1e-6 + L.sr_dn4[i] * t4 * 1e-8
        sr += L.sr_rn[i] * cos(DEG * arg)
    }
    var sr1 = 0.0
    for (i in 0 until L.N1r[p]) sr1 += L.sr1_rn[i] * cos(DEG * (L.sr1_dn0[i] + L.sr1_dn1[i] * t))
    var sr2 = 0.0
    for (i in 0 until L.N2r[p]) sr2 += L.sr2_rn[i] * cos(DEG * (L.sr2_dn0[i] + L.sr2_dn1[i] * t))
    var sr3 = 0.0
    for (i in 0 until L.N3r[p]) sr3 += L.sr3_rn[i] * cos(DEG * (L.sr3_dn0[i] + L.sr3_dn1[i] * t))
    return 385000.57 + sr + sr1 + t * sr2 + t2 * 1e-4 * sr3
}

private fun lunarAberrationV(t: Double): Double =
    DEG * (-0.00019524 - 0.00001059 * sin((225 + 477198.9 * t) * DEG))

private fun lunarAberrationU(t: Double): Double =
    DEG * (-0.00001754 * sin((183.3 + 483202.0 * t) * DEG))

private fun lunarAberrationR(t: Double): Double =
    DEG * (0.0708 * cos((225 + 477198.9 * t) * DEG))

private fun meanObliquityFromTDT(t: Double): Double {
    val t2 = t * t; val t3 = t * t2; val t4 = t2 * t2
    return DEG * (23.43928 - 0.013 * t + 0.555e-6 * t3 - 0.014e-8 * t4)
}

private fun nutations(t: Double): Pair<Double, Double> {
    val L = LunarTables
    var longNut = 0.0; var obliqueNut = 0.0
    val t2 = t * t
    for (i in 0 until L.NNut[WB_FULL]) {
        val arg = (L.nut_mu0n[i] + L.nut_mu1n[i] * t + L.nut_mu2n[i] * t2) * DEG
        longNut += (L.nut_psin[i] + L.nut_psi1n[i] * t) * sin(arg)
        if (i < 4 || (i > 5 && i < 9)) obliqueNut += (L.nut_obn[i] + L.nut_ob1n[i] * t) * cos(arg)
    }
    return Pair(longNut * DEG, obliqueNut * DEG)
}

private fun moonRaDeclForTDT(vRad: Double, uRad: Double, t: Double): RaDecl {
    val meanOb = meanObliquityFromTDT(t)
    val (longitudeNutation, obliquityNutation) = nutations(t)
    val vn = vRad + longitudeNutation
    val trueOb = meanOb + obliquityNutation

    val cosV = cos(vn); val sinV = sin(vn); val sinU = sin(uRad); val cosU = cos(uRad)
    val cosTrueOb = cos(trueOb); val sinTrueOb = sin(trueOb)

    val ra: Double = if (cosV == 0.0) {
        vn
    } else {
        var r = atan2(cosTrueOb * sinV * cosU - sinTrueOb * sinU, cosV * cosU)
        if (r < 0) r += PI * 2
        r
    }
    val decl = asin(sinTrueOb * sinV * cosU + cosTrueOb * sinU)
    return RaDecl(ra, decl)
}

internal data class MoonRADeclResult(
    val rightAscension: Double, val declination: Double, val longitude: Double, val latitude: Double,
)

internal fun wbMoonRAAndDecl(t: Double, p: Int = WB_FULL): MoonRADeclResult {
    val v = lunarLongitudeForTDT(t, p)
    val u = lunarLatitudeForTDT(t, p)
    val longitude = v * DEG + lunarAberrationV(t)
    val latitude = u * DEG + lunarAberrationU(t)
    val rd = moonRaDeclForTDT(longitude, latitude, t)
    return MoonRADeclResult(rd.rightAscension, rd.declination, longitude, latitude)
}

/** Moon distance in km (with aberration). */
internal fun wbMoonDistance(t: Double, p: Int = WB_FULL): Double =
    lunarDistanceForTDT(t, p) + lunarAberrationR(t)

/**
 * Longitude of the Moon's ascending node (radians, [0,2π)), ported from
 * WB_MoonAscendingNodeLongitude. [t] is julianCenturies since J2000 (TDT).
 */
internal fun wbMoonAscendingNodeLongitude(t: Double, p: Int = WB_FULL): Double {
    val L = LunarTables
    val t2 = t * t; val t3 = t * t2

    // Vdot (longitude rate)
    var sVdot = 0.0
    for (i in 0 until L.Nv[p]) {
        val derivArg = L.sv_an1[i] + 2 * L.sv_an2[i] * t * 1e-4 + 3 * L.sv_an3[i] * t2 * 1e-6 + 4 * L.sv_an4[i] * t3 * 1e-8
        val cosArg = L.sv_an0[i] + L.sv_an1[i] * t + L.sv_an2[i] * t2 * 1e-4 + L.sv_an3[i] * t3 * 1e-6 + L.sv_an4[i] * (t2 * t2) * 1e-8
        sVdot += L.sv_vn[i] * derivArg * cos(DEG * cosArg)
    }
    sVdot *= DEG
    var sV1dot = 0.0
    for (i in 0 until L.N1v[p]) sV1dot += L.sv1_vn[i] * L.sv1_an1[i] * cos(DEG * (L.sv1_an0[i] + L.sv1_an1[i] * t))
    sV1dot *= DEG
    var sV2dot = 0.0
    for (i in 0 until L.N2v[p]) sV2dot += L.sv2_vn[i] * L.sv2_an1[i] * cos(DEG * (L.sv2_an0[i] + L.sv2_an1[i] * t))
    sV2dot *= DEG
    var sV3dot = 0.0
    for (i in 0 until L.N3v[p]) sV3dot += L.sv3_vn[i] * L.sv3_an1[i] * cos(DEG * (L.sv3_an0[i] + L.sv3_an1[i] * t))
    sV3dot *= DEG
    val vDot = 481267.881 - 0.0026536 * t + 0.05568e-4 * t2 - 0.06136e-6 * t3 +
        sVdot + 1e-3 * (sV1dot + t * sV2dot + t2 * sV3dot * 1e-4)

    // Udot (latitude rate)
    var sUdot = 0.0
    for (i in 0 until L.Nu[p]) {
        val derivArg = L.su_bn1[i] + 2 * L.su_bn2[i] * t * 1e-4 + 3 * L.su_bn3[i] * t2 * 1e-6 + 4 * L.su_bn4[i] * t3 * 1e-8
        val cosArg = L.su_bn0[i] + L.su_bn1[i] * t + L.su_bn2[i] * t2 * 1e-4 + L.su_bn3[i] * t3 * 1e-6 + L.su_bn4[i] * (t2 * t2) * 1e-8
        sUdot += L.su_un[i] * derivArg * cos(DEG * cosArg)
    }
    sUdot *= DEG
    var sU1dot = 0.0
    for (i in 0 until L.N1u[p]) sU1dot += L.su1_un[i] * L.su1_bn1[i] * cos(DEG * (L.su1_bn0[i] + L.su1_bn1[i] * t))
    sU1dot *= DEG
    var sU2dot = 0.0
    for (i in 0 until L.N2u[p]) sU2dot += L.su2_un[i] * L.su2_bn1[i] * cos(DEG * (L.su2_bn0[i] + L.su2_bn1[i] * t))
    sU2dot *= DEG
    var sU3dot = 0.0
    for (i in 0 until L.N3u[p]) sU3dot += L.su3_un[i] * L.su3_bn1[i] * cos(DEG * (L.su3_bn0[i] + L.su3_bn1[i] * t))
    sU3dot *= DEG
    val uDot = sUdot + 1e-3 * (sU1dot + t * sU2dot + 1e-4 * t2 * sU3dot)

    val v = lunarLongitudeForTDT(t, p)
    val u = lunarLatitudeForTDT(t, p)
    val cosU = cos(DEG * u); val sinU = sin(DEG * u)
    val cosV = cos(DEG * v); val sinV = sin(DEG * v)
    val y = uDot * sinV - vDot * sinU * cosU * cosV
    val z = uDot * cosV + vDot * sinU * cosU * sinV
    var omega = esFmod(atan2(y, z), 2 * PI)
    if (omega < 0) omega += 2 * PI
    return omega
}
