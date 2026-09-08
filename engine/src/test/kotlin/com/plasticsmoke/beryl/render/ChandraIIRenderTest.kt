package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.registerWatchFunctions
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.watch.ModeFilter
import com.plasticsmoke.beryl.watch.parseWatchXml
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders ChandraII (canonical iOS) — the MOON WATCH v2. The front mirrors Chandra (realistic
 * terminator moon under a porthole, month/day/AM-PM windows, Terra quad hands, alt/az star markers).
 * The back is the new work: a Delta-Ecliptic-Longitude ring of overlapping QWedges
 * (moonDeltaEclipticLongitudeAtDeltaDay) with elongation/phase hands, ecliptic-latitude + Earth-Moon
 * distance subdials (ELatitudeOfPlanet/distanceFromEarthOfPlanet), the four closest-phase day wheels,
 * moonrise/set subdials, a clock subdial, and the lunar-node (rahu/ketu) hands.
 *
 * Full phone frame: real band/case/button chrome from partsBin (white wide case, black curved
 * band) over the granite wallpaper — no procedural bezel, canonical watch tag.
 */
class ChandraIIRenderTest {

    private fun resourceText(name: String) =
        ChandraIIRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        ChandraIIRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String, scale: Double = 0.25) = LoadedImage(AwtImage(resourceImage(name)), scale)

    private fun render(mode: ModeFilter, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/ChandraII.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()  // 10:08:36 PDT, Fri Jun 12 2026
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "chandraii$suffix")
        assertTrue(watch.parts.isNotEmpty(), "ChandraII$suffix parts should be present")
    }

    @Test fun rendersChandraIIFront() = render(
        ModeFilter.FRONT, "",
        mapOf(
            "../Chandra/face.png" to img("/chandra-face-4x.png"),
            "../partsBin/logos/white.png" to img("/logo-white-4x.png"),
            "../partsBin/moonES.png" to img("/moonES-4x.png"),
            "redStar.png" to img("/redStar-4x.png"),
            "blueStar.png" to img("/blueStar-4x.png"),
        ),
    )

    @Test fun rendersChandraIINight() = render(
        ModeFilter.NIGHT, "-night",
        mapOf(
            "../Chandra/stars.png" to img("/chandra-stars-4x.png"),
            "../partsBin/moonESnight.png" to img("/moonESnight-4x.png"),
            "redStar.png" to img("/redStar-4x.png"),
            "blueStar.png" to img("/blueStar-4x.png"),
        ),
    )

    @Test fun rendersChandraIIBack() = render(
        ModeFilter.BACK, "-back",
        mapOf(
            "face-white-trim.png" to img("/chandraii-faceback-4x.png"),
            "../partsBin/logos/black.png" to img("/chandraii-logo-black-4x.png"),
            "../partsBin/moonES72.png" to img("/chandraii-moon72-4x.png"),
            "phaseN.png" to img("/chandraii-phasen.png", 1.0),
            "phase1.png" to img("/chandraii-phase1.png", 1.0),
            "phase3.png" to img("/chandraii-phase3.png", 1.0),
            "phaseF.png" to img("/chandraii-phasef.png", 1.0),
        ),
    )
}
