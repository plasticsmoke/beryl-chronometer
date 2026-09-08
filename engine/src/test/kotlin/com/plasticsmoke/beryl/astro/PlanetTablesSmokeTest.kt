package com.plasticsmoke.beryl.astro
import kotlin.test.Test
import kotlin.test.assertEquals
class PlanetTablesSmokeTest {
    @Test fun loadsAndMatchesKnownValues() {
        assertEquals(0.390917, PlanetTables.jupiterData[0][0], 1e-9)   // first jupiter aLong[0]
        assertEquals(-0.033997, PlanetTables.jupiterData[0][20], 1e-9) // last aRad[6]
        assertEquals(260057.5, PlanetTables.jupiterJD[0][0], 1e-9)     // first JD start
        assertEquals(510728.0, PlanetTables.mercuryLong[0][0], 1e-9)   // mercury vi[0]
        assertEquals(403406.0, PlanetTables.sun[0][0], 1e-9)           // sun li[0]
        assertEquals(1360, PlanetTables.saturnData.size)
        println("PlanetTables loaded OK")
    }
}
