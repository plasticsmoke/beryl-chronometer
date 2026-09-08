package com.plasticsmoke.beryl.render

import com.plasticsmoke.beryl.astro.registerWatchFunctions
import com.plasticsmoke.beryl.expr.createDefaultEnvironment
import com.plasticsmoke.beryl.watch.ModeFilter
import com.plasticsmoke.beryl.watch.parseWatchXml
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders Firenze (canonical iOS) — an ORRERY: a heliocentric top-down view of the solar system with
 * each planet image placed on its orbital ring at the body's heliocentric longitude
 * (HLongitudeOfPlanet), plus Earth+Moon, the date windows, and time hands. Exercises the new
 * heliocentric-longitude leaf built on the ported WB planet theory.
 */
class FirenzeRenderTest {

    private fun resourceText(name: String) =
        FirenzeRenderTest::class.java.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceImage(name: String) =
        FirenzeRenderTest::class.java.getResourceAsStream(name)!!.use { ImageIO.read(it) }

    private fun img(name: String) = LoadedImage(AwtImage(resourceImage(name)), 0.25)

    private fun render(mode: ModeFilter, suffix: String, images: Map<String, LoadedImage>) {
        val xml = resourceText("/Firenze.xml")
        val watch = parseWatchXml(xml, mode)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()
        }
        applyInits(watch, env)
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "firenze$suffix")
        assertTrue(watch.parts.isNotEmpty(), "Firenze$suffix parts should be present")
    }

    // Image maps are LITERAL "key" to img(...) entries — tools/gen_face_manifest.py extracts them
    // into the app's faces.json, and only understands the literal idiom (Firenze's front map was
    // originally assembled in a loop, which silently produced a face-less, planet-less app render).
    @Test fun rendersFirenzeFront() = render(
        ModeFilter.FRONT, "",
        mapOf(
            "face.png" to img("/firenze-face-4x.png"),
            "../partsBin/planets/mercuryTransparent.png" to img("/firenze-mercury-4x.png"),
            "../partsBin/planets/venusTransparent.png" to img("/firenze-venus-4x.png"),
            "../partsBin/planets/earthTransparent.png" to img("/firenze-earth-4x.png"),
            "../partsBin/planets/marsTransparent.png" to img("/firenze-mars-4x.png"),
            "../partsBin/planets/jupiterTransparent.png" to img("/firenze-jupiter-4x.png"),
            "../partsBin/planets/saturnTransparent.png" to img("/firenze-saturn-4x.png"),
            "../partsBin/planets/moonTransparent.png" to img("/firenze-moon-4x.png"),
        ),
    )

    @Test fun rendersFirenzeBack() {
        val xml = resourceText("/Firenze.xml")
        val watch = parseWatchXml(xml, ModeFilter.BACK)
        val env = createDefaultEnvironment()
        val d = Math.PI / 180
        registerWatchFunctions(env, -7 * 3600.0, 37.7749 * d, -122.4194 * d) {
            Instant.parse("2026-06-12T17:08:36Z").toEpochMilli()
        }
        applyInits(watch, env)
        val images = mapOf(
            "../partsBin/planets/saturn.png" to img("/firenze-b-saturn-4x.png"),
            "../partsBin/planets/jupiter.png" to img("/firenze-b-jupiter-4x.png"),
            "../partsBin/planets/mars.png" to img("/firenze-b-mars-4x.png"),
            "../partsBin/planets/venus.png" to img("/firenze-b-venus-4x.png"),
            "../partsBin/planets/mercury.png" to img("/firenze-b-mercury-4x.png"),
            "../partsBin/planets/bighalfMoon.png" to img("/firenze-b-bighalfmoon-4x.png"),
            "horizonMask.png" to img("/firenze-horizonmask-4x.png"),
            "../partsBin/logos/white.png" to img("/firenze-logo-white-4x.png"),
        )
        PhoneFrame.render(watch, env, images + PhoneFrame.loadChrome(xml), "firenze-back")
        assertTrue(watch.parts.isNotEmpty(), "Firenze back parts should be present")
    }

    // Night uses an N2 glyph + an L lume-label per planet (Moon is just moonN).
    @Test fun rendersFirenzeNight() = render(
        ModeFilter.NIGHT, "-night",
        mapOf(
            "faceNight.png" to img("/firenze-facenight-4x.png"),
            "../partsBin/planets/mercuryN2.png" to img("/firenze-n-mercuryn2-4x.png"),
            "../partsBin/planets/mercuryL.png" to img("/firenze-n-mercuryl-4x.png"),
            "../partsBin/planets/venusN2.png" to img("/firenze-n-venusn2-4x.png"),
            "../partsBin/planets/venusL.png" to img("/firenze-n-venusl-4x.png"),
            "../partsBin/planets/marsN2.png" to img("/firenze-n-marsn2-4x.png"),
            "../partsBin/planets/marsL.png" to img("/firenze-n-marsl-4x.png"),
            "../partsBin/planets/jupiterN2.png" to img("/firenze-n-jupitern2-4x.png"),
            "../partsBin/planets/JupiterL.png" to img("/firenze-n-jupiterl-4x.png"),
            "../partsBin/planets/saturnN2.png" to img("/firenze-n-saturnn2-4x.png"),
            "../partsBin/planets/SaturnL.png" to img("/firenze-n-saturnl-4x.png"),
            "../partsBin/planets/earthN2.png" to img("/firenze-n-earthn2-4x.png"),
            "../partsBin/planets/earthL.png" to img("/firenze-n-earthl-4x.png"),
            "../partsBin/planets/moonN.png" to img("/firenze-n-moonn-4x.png"),
        ),
    )
}
