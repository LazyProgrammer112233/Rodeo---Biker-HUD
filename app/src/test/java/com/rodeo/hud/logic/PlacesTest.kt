package com.rodeo.hud.logic

import com.rodeo.hud.nav.Place
import com.rodeo.hud.nav.Places
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    private val blrLat = 12.9716
    private val blrLng = 77.5946

    // Photon's real answer for "nandi hills" near Bengaluru: three roads, no hill.
    private val photonNandi = """
        {"type":"FeatureCollection","features":[
         {"type":"Feature","properties":{"osm_key":"highway","osm_value":"primary","type":"street","name":"Nandi Hills Road","city":"Devanahalli","state":"Karnataka"},"geometry":{"type":"Point","coordinates":[77.7194972,13.2676857]}},
         {"type":"Feature","properties":{"osm_key":"highway","osm_value":"primary","type":"street","name":"Nandi Hills Road","city":"Maligenahalli","state":"Karnataka"},"geometry":{"type":"Point","coordinates":[77.7024108,13.2953158]}},
         {"type":"Feature","properties":{"osm_key":"highway","osm_value":"primary","type":"street","name":"Nandi Hills Road","city":"Doddaballapura","state":"Karnataka"},"geometry":{"type":"Point","coordinates":[77.5471571,13.3140746]}}]}
    """.trimIndent()

    @Test fun theHillBeatsTheRoadNamedAfterIt() {
        val roads = Places.parsePhoton(photonNandi)
        assertTrue(roads.all { it.road })
        val nominatim = Places.parseNominatim(
            """[{"lat":"13.3702","lon":"77.6835","category":"natural","type":"peak","name":"Nandi Hills","display_name":"Nandi Hills, Chikkaballapura, Karnataka, India"}]""",
        )
        val ranked = Places.rank("Nandi Hills", Places.merge(roads, nominatim), blrLat, blrLng)
        assertEquals("Nandi Hills", ranked.first().title)
        assertTrue(!ranked.first().road)
    }

    @Test fun roadsComeFirstWhenAskedFor() {
        val ranked = Places.rank(
            "nandi hills road",
            listOf(Place("Nandi Hills", "", 13.3702, 77.6835)) + Places.parsePhoton(photonNandi),
            blrLat, blrLng,
        )
        assertEquals("Nandi Hills Road", ranked.first().title)
        // Among equal names, the closest comes first.
        assertEquals("Devanahalli, Karnataka", ranked.first().subtitle)
    }

    @Test fun nearestOfAChainFirst() {
        val far = Place("HP Petrol Pump", "Hosur Road", 12.85, 77.66)
        val near = Place("HP Petrol Pump", "MG Road", 12.975, 77.60)
        val other = Place("Indian Oil", "MG Road", 12.972, 77.595)
        val ranked = Places.rank("hp petrol pump", listOf(far, other, near), blrLat, blrLng)
        assertEquals(listOf(near, far, other), ranked)
    }

    @Test fun aMatchInTownBeatsAnExactOneInAnotherState() {
        // Rider in Gurugram searching "ambience mall": the Jaipur one must not come first.
        val ggnLat = 28.4595; val ggnLng = 77.0266
        val jaipur = Place("Ambience Mall", "Jaipur, Rajasthan", 26.85, 75.80)
        val gurugram = Place("Ambience Mall Gurgaon", "NH 48, Gurugram", 28.5045, 77.0970)
        val unrelated = Place("Cyber Hub", "Gurugram", 28.4950, 77.0890)
        val ranked = Places.rank("ambience mall", listOf(jaipur, unrelated, gurugram), ggnLat, ggnLng)
        assertEquals(listOf(gurugram, jaipur, unrelated), ranked)
    }

    @Test fun startOfNameAndPartialWords() {
        val garden = Place("Lalbagh Botanical Garden", "", 12.9507, 77.5848)
        val gate = Place("Lalbagh West Gate", "", 12.9496, 77.5826)
        val road = Place("Lalbagh Road", "", 12.9580, 77.5900, road = true)
        val elsewhere = Place("Garden City", "", 12.95, 77.58)
        val ranked = Places.rank("lalbag", listOf(elsewhere, road, garden, gate), blrLat, blrLng)
        assertEquals(setOf(garden, gate), ranked.take(2).toSet())
        assertEquals(road, ranked[2])
        assertEquals(elsewhere, ranked[3])
    }

    @Test fun samePlaceFromTwoSourcesShowsOnce() {
        val a = Place("Cubbon Park", "Bengaluru", 12.9763, 77.5929)
        val b = Place("cubbon park", "Kasturba Road", 12.9765, 77.5931)
        assertEquals(1, Places.merge(listOf(a), listOf(b)).size)
    }
}
