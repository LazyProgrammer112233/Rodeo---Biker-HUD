package com.rodeo.hud.data

/** How the speed gauge and type are drawn for a brand. */
enum class GaugeStyle { RACE, CLASSIC, MINIMAL }

data class Paint(val name: String, val hex: Long)

data class Model(
    val name: String,
    val firstYear: Int,
    val paints: List<Paint>,
)

data class Brand(
    val id: String,
    val name: String,
    /** Primary accent from the brand's identity. */
    val accent: Long,
    /** Optional heritage / racing accent (e.g. Kawasaki lime). */
    val heritageAccent: Long? = null,
    val gauge: GaugeStyle,
    val tagline: String,
    val models: List<Model>,
)

/**
 * Bundled bike catalog for the Indian market. Model lineups follow each maker's Indian website
 * (Oct 2026). Accents follow published brand colours; paints are the common factory colours.
 */
object Catalog {
    private val black = Paint("Black", 0xFF15161A)
    private val white = Paint("White", 0xFFEDEEF0)
    private val grey = Paint("Grey", 0xFF6E7176)
    private val silver = Paint("Silver", 0xFFB9BCC1)
    private val red = Paint("Red", 0xFFC8102E)
    private val blue = Paint("Blue", 0xFF1F4E9C)

    private fun m(name: String, year: Int, vararg paints: Paint) = Model(name, year, paints.toList())

    val brands: List<Brand> = listOf(
        Brand(
            "ktm", "KTM", 0xFFFF6600, gauge = GaugeStyle.RACE, tagline = "Ready to Race",
            models = listOf(
                m("390 Duke", 2013, Paint("Electronic Orange", 0xFFFF6600), Paint("Atlantic Blue", 0xFF1D4E89), Paint("Ebony Black", 0xFF16171A)),
                m("250 Duke", 2017, Paint("Electronic Orange", 0xFFFF6600), Paint("Silver Metallic", 0xFFAEB2B7), Paint("Ebony Black", 0xFF16171A)),
                m("200 Duke", 2012, Paint("Electronic Orange", 0xFFFF6600), Paint("Atlantic Blue", 0xFF1D4E89), Paint("Dark Galvano", 0xFF3B3E43)),
                m("160 Duke", 2025, Paint("Electronic Orange", 0xFFFF6600), Paint("Silver Metallic", 0xFFAEB2B7)),
                m("RC 390", 2014, Paint("Orange", 0xFFFF6600), Paint("Blue", 0xFF1D4E89)),
                m("RC 200", 2014, Paint("Orange", 0xFFFF6600), Paint("Black", 0xFF16171A)),
                m("390 Adventure", 2020, Paint("Orange", 0xFFFF6600), Paint("White", 0xFFEDEEF0)),
                m("250 Adventure", 2020, Paint("Orange", 0xFFFF6600), Paint("Black", 0xFF16171A)),
                m("390 Enduro R", 2025, Paint("Orange", 0xFFFF6600)),
                m("890 Duke R", 2026, Paint("Orange", 0xFFFF6600), Paint("Black", 0xFF16171A)),
            ),
        ),
        Brand(
            "royal_enfield", "Royal Enfield", 0xFFA6192E, gauge = GaugeStyle.CLASSIC, tagline = "Pure Motorcycling",
            models = listOf(
                m("Classic 350", 2009, Paint("Stealth Black", 0xFF141414), Paint("Signals Marsh Grey", 0xFF7A7F72), Paint("Chrome Red", 0xFF9E1B22), Paint("Halcyon Green", 0xFF2F5A47)),
                m("Bullet 350", 1932, Paint("Black Gold", 0xFF1A1714), Paint("Military Red", 0xFF7B1E1E), Paint("Standard Maroon", 0xFF5A1A22)),
                m("Hunter 350", 2022, Paint("Rebel Blue", 0xFF2B5DA8), Paint("Dapper Grey", 0xFF8A8D91), Paint("Factory Black", 0xFF16171A)),
                m("Meteor 350", 2020, Paint("Fireball Orange", 0xFFD9601E), Paint("Stellar Black", 0xFF16171A), Paint("Supernova Brown", 0xFF5A3A28)),
                m("Goan Classic 350", 2024, Paint("Purple Haze", 0xFF5C3B7A), Paint("Shack Black", 0xFF16171A)),
                m("Himalayan 450", 2023, Paint("Kaza Brown", 0xFF6B4A33), Paint("Slate Himalayan Salt", 0xFFC9C3B6), Paint("Hanle Black", 0xFF16171A)),
                m("Guerrilla 450", 2024, Paint("Playa Black", 0xFF16171A), Paint("Yellow Ribbon", 0xFFE3B23C), Paint("Brava Blue", 0xFF2E6FB0)),
                m("Scram 440", 2025, Paint("Force Blue", 0xFF2C5B8F), Paint("Trail Green", 0xFF4E6B3C)),
                m("Interceptor 650", 2018, Paint("Canyon Red", 0xFFA42A2A), Paint("Black Ray", 0xFF16171A), Paint("Baja Orange", 0xFFE06A2B)),
                m("Continental GT 650", 2018, Paint("Rocker Red", 0xFFB01E23), Paint("British Racing Green", 0xFF1F4A33), Paint("Slipstream Blue", 0xFF3D6EA6)),
                m("Super Meteor 650", 2023, Paint("Astral Black", 0xFF16171A), Paint("Interstellar Green", 0xFF2E4B3E), Paint("Celestial Red", 0xFF8E1D24)),
                m("Shotgun 650", 2024, Paint("Sheetmetal Grey", 0xFF8A8C8E), Paint("Plasma Blue", 0xFF2F6FB7)),
                m("Bear 650", 2024, Paint("Wild Honey", 0xFFE0A030), Paint("Two Four Nine", 0xFFEDEEF0)),
                m("Classic 650", 2025, Paint("Vallam Red", 0xFF9E1B22), Paint("Black Chrome", 0xFF16171A)),
            ),
        ),
        Brand(
            "hero", "Hero MotoCorp", 0xFFE21E26, gauge = GaugeStyle.MINIMAL, tagline = "Hum Mein Hai Hero",
            models = listOf(
                m("Splendor+", 1994, black, red, Paint("Heavy Grey", 0xFF5D6066), blue),
                m("Super Splendor XTEC", 2022, black, Paint("Canvas Black", 0xFF222326), red),
                m("HF Deluxe", 2007, black, red, Paint("Nexus Blue", 0xFF2C59A0)),
                m("Passion+", 2023, Paint("Sports Red", 0xFFC8102E), black, grey),
                m("Glamour X", 2025, black, red, Paint("Metallic Silver", 0xFFB9BCC1)),
                m("Xtreme 125R", 2024, Paint("Firestorm Red", 0xFFD0202A), Paint("Cobalt Blue", 0xFF1F4E9C), Paint("Stallion Black", 0xFF16171A)),
                m("Xtreme 160R 4V", 2023, Paint("Matte Slate Black", 0xFF26282B), Paint("Blazing Red", 0xFFC8102E), Paint("Neon Shooting Star", 0xFFB8E01E)),
                m("Xtreme 250R", 2025, Paint("Matte Black", 0xFF1E1F22), Paint("Shining White", 0xFFEDEEF0)),
                m("XPulse 210", 2024, Paint("Matte Nexus Blue", 0xFF2C59A0), Paint("Black", 0xFF16171A), white),
                m("Karizma XMR", 2023, Paint("Iconic Yellow", 0xFFF2C21B), Paint("Turbo Red", 0xFFC8102E), Paint("Matte Phantom Black", 0xFF1E1F22)),
                m("Mavrick 440", 2024, Paint("Arctic White", 0xFFEDEEF0), Paint("Fearless Red", 0xFFC8102E), Paint("Phantom Black", 0xFF16171A)),
                m("Xoom 160", 2025, white, black, Paint("Matte Ragging Red", 0xFFB01E23)),
                m("Destini 125", 2020, black, Paint("Noble Red", 0xFF9E1B22), Paint("Chestnut Brown", 0xFF6A4A35)),
                m("Pleasure+ Xtec", 2022, Paint("Pole Star Blue", 0xFF2E6FB0), white, red),
                m("Vida V2 Plus", 2025, Paint("Matte Nexus Blue", 0xFF2C59A0), white, Paint("Matte Sports Red", 0xFFB01E23)),
            ),
        ),
        Brand(
            "honda", "Honda", 0xFFCC0000, gauge = GaugeStyle.MINIMAL, tagline = "The Power of Dreams",
            models = listOf(
                m("Activa 110", 2001, Paint("Pearl Precious White", 0xFFEDEEF0), Paint("Black", 0xFF16171A), Paint("Rebel Red Metallic", 0xFFA51C24), Paint("Pearl Siren Blue", 0xFF2E5E9E)),
                m("Activa 125", 2014, Paint("Pearl Night Star Black", 0xFF16171A), Paint("Pearl Precious White", 0xFFEDEEF0), Paint("Heavy Grey Metallic", 0xFF5D6066)),
                m("Activa e:", 2025, Paint("Pearl Shallow Blue", 0xFF5C8FB8), Paint("Pearl Misty White", 0xFFE6E7E9)),
                m("Dio 125", 2023, Paint("Sports Red", 0xFFC8102E), Paint("Pearl Siren Blue", 0xFF2E5E9E), black),
                m("Shine 100", 2023, black, red, blue, grey),
                m("Shine 125", 2006, black, Paint("Genny Grey", 0xFF6E7176), Paint("Rebel Red", 0xFFA51C24), Paint("Decent Blue", 0xFF1F4E9C)),
                m("SP 125", 2019, Paint("Matte Axis Grey", 0xFF5D6066), Paint("Imperial Red", 0xFFA51C24), black),
                m("SP 160", 2023, Paint("Pearl Igneous Black", 0xFF16171A), Paint("Matte Dark Blue", 0xFF243C66), Paint("Pearl Spartan Red", 0xFFA51C24)),
                m("Unicorn", 2004, black, Paint("Pearl Igneous Black", 0xFF16171A), Paint("Radiant Red", 0xFFC8102E)),
                m("Hornet 2.0", 2020, Paint("Repsol Edition", 0xFFFF7A00), Paint("Pearl Igneous Black", 0xFF16171A), Paint("Matte Axis Grey", 0xFF5D6066)),
                m("NX200", 2025, Paint("Pearl Igneous Black", 0xFF16171A), Paint("Athletic Blue", 0xFF2E5E9E)),
                m("CB350 H'ness", 2020, Paint("Precious Red", 0xFF8E1D24), Paint("Pearl Night Star Black", 0xFF16171A), Paint("Matte Marshal Green", 0xFF4A5A3A)),
                m("CB350RS", 2021, Paint("Radiant Red", 0xFFC8102E), Paint("Black", 0xFF16171A)),
                m("NX500", 2024, Paint("Grand Prix Red", 0xFFCC0000), Paint("Pearl Horizon White", 0xFFEDEEF0)),
            ),
        ),
        Brand(
            "tvs", "TVS Motor", 0xFF0F4A87, heritageAccent = 0xFFEE3642, gauge = GaugeStyle.RACE, tagline = "Racing DNA",
            models = listOf(
                m("Jupiter", 2013, Paint("Starlight Blue", 0xFF2E5E9E), Paint("Dawn Orange", 0xFFE07A2B), Paint("Galactic Copper", 0xFF8A5A3B), black, grey),
                m("Jupiter 125", 2021, Paint("Dawn Blue", 0xFF2E5E9E), Paint("Pristine White", 0xFFEDEEF0), black),
                m("Ntorq 125", 2018, Paint("Race Edition Red", 0xFFEE3642), Paint("Stealth Black", 0xFF16171A), Paint("Nardo Grey", 0xFF8A8D91)),
                m("Ntorq 150", 2025, Paint("Racing Red", 0xFFEE3642), Paint("Turbo Blue", 0xFF0F4A87), Paint("Nitro Green", 0xFF7BC043)),
                m("Raider 125", 2021, Paint("Wicked Black", 0xFF16171A), Paint("Fiery Yellow", 0xFFF2C21B), Paint("Blazing Blue", 0xFF0F4A87)),
                m("Radeon", 2018, black, Paint("Royal Purple", 0xFF5C3B7A), Paint("Metal Black", 0xFF26282B)),
                m("Apache RTR 160 4V", 2018, Paint("Racing Red", 0xFFEE3642), Paint("Matte Black", 0xFF1E1F22), Paint("Pearl White", 0xFFEDEEF0)),
                m("Apache RTR 200 4V", 2016, Paint("Matte Black", 0xFF1E1F22), Paint("Gloss Black", 0xFF16171A), Paint("Pearl White", 0xFFEDEEF0)),
                m("Apache RTR 310", 2023, Paint("Fury Yellow", 0xFFF2C21B), Paint("Arsenal Black", 0xFF16171A), Paint("Sepang Blue", 0xFF0F4A87)),
                m("Apache RR 310", 2017, Paint("Racing Red", 0xFFEE3642), Paint("Bomber Grey", 0xFF5D6066)),
                m("Apache RTX", 2025, Paint("Metallic Blue", 0xFF0F4A87), Paint("Tarn Bronze", 0xFF8A6A3B)),
                m("Ronin", 2022, Paint("Magma Red", 0xFFA51C24), Paint("Lightning Black", 0xFF16171A), Paint("Galactic Grey", 0xFF5D6066)),
                m("iQube", 2020, Paint("Titanium Grey", 0xFF8A8D91), Paint("Starlight Blue", 0xFF2E5E9E), white),
                m("Orbiter", 2025, Paint("Lunar Grey", 0xFFA0A3A8), Paint("Stratos Blue", 0xFF2E6FB0)),
            ),
        ),
        Brand(
            "bajaj", "Bajaj", 0xFF005DAA, gauge = GaugeStyle.RACE, tagline = "The World's Favourite Indian",
            models = listOf(
                m("Pulsar 150", 2001, black, Paint("Black Red", 0xFFC8102E), Paint("Black Blue", 0xFF005DAA)),
                m("Pulsar N160", 2022, Paint("Pearl Metallic White", 0xFFEDEEF0), Paint("Racing Red", 0xFFC8102E), Paint("Polar Sky Blue", 0xFF4F86C6), black),
                m("Pulsar N250", 2021, Paint("Brooklyn Black", 0xFF16171A), Paint("Racing Red", 0xFFC8102E), Paint("Pearl Metallic White", 0xFFEDEEF0)),
                m("Pulsar NS200", 2012, Paint("Pewter Grey", 0xFF5D6066), Paint("Metallic Pearl White", 0xFFEDEEF0), Paint("Glossy Ebony Black", 0xFF16171A)),
                m("Pulsar NS400Z", 2024, Paint("Brooklyn Black", 0xFF16171A), Paint("Glossy Racing Red", 0xFFC8102E), Paint("Pewter Grey", 0xFF5D6066)),
                m("Pulsar RS200", 2015, Paint("Racing Red", 0xFFC8102E), Paint("Pearl White", 0xFFEDEEF0), Paint("Glossy Black", 0xFF16171A)),
                m("Pulsar 220F", 2007, black, red, blue),
                m("Dominar 400", 2016, Paint("Aurora Green", 0xFF2E5A47), Paint("Charcoal Black", 0xFF1E1F22)),
                m("Dominar 250", 2020, Paint("Racing Red", 0xFFC8102E), Paint("Citrus Rush", 0xFFE0A030)),
                m("Avenger 220 Cruise", 2015, Paint("Auburn Black", 0xFF1E1A18), Paint("Moon White", 0xFFEDEEF0)),
                m("Platina 110", 2006, black, red, blue),
                m("Freedom 125", 2024, Paint("Caribbean Blue", 0xFF2E7DB8), Paint("Racing Red", 0xFFC8102E), Paint("Cyber White", 0xFFEDEEF0)),
                m("Chetak", 2020, Paint("Indigo Metallic", 0xFF2E3A6E), Paint("Brooklyn Black", 0xFF16171A), white),
            ),
        ),
        Brand(
            "yamaha", "Yamaha", 0xFF0033A0, gauge = GaugeStyle.RACE, tagline = "Revs Your Heart",
            models = listOf(
                m("R15 V4", 2021, Paint("Racing Blue", 0xFF0033A0), Paint("Metallic Red", 0xFFB01E23), Paint("Dark Knight", 0xFF16171A)),
                m("R15M", 2021, Paint("Metallic Grey", 0xFF5D6066), Paint("Carbon Fibre Pattern", 0xFF26282B)),
                m("R15S", 2023, Paint("Racing Blue", 0xFF0033A0), Paint("Matte Black", 0xFF1E1F22)),
                m("MT-15 Ver 2.0", 2022, Paint("Ice Fluo-Vermillion", 0xFFE04E2B), Paint("Cyan Storm", 0xFF2B8FB0), Paint("Metallic Black", 0xFF16171A)),
                m("XSR 155", 2025, Paint("Metallic Grey", 0xFF8A8D91), Paint("Vivid Red", 0xFFC8102E)),
                m("FZ-S Fi Ver 4.0", 2023, Paint("Racing Blue", 0xFF0033A0), Paint("Vermillion", 0xFFD9481E), Paint("Matte Black", 0xFF1E1F22)),
                m("FZ-X", 2021, Paint("Matte Titan", 0xFF6E6A5E), Paint("Dark Matte Blue", 0xFF243C66), black),
                m("R3", 2023, Paint("Racing Blue", 0xFF0033A0), Paint("Midnight Black", 0xFF16171A)),
                m("Aerox 155", 2021, Paint("Racing Blue", 0xFF0033A0), Paint("Grey Vermillion", 0xFF6E7176), Paint("Silver", 0xFFB9BCC1)),
                m("RayZR 125 Fi", 2016, Paint("Cyan Blue", 0xFF2B8FB0), Paint("Metallic Black", 0xFF16171A), Paint("Metallic Grey", 0xFF8A8D91)),
                m("Fascino 125 Fi", 2019, Paint("Cool Blue Metallic", 0xFF5C8FB8), Paint("Vivid Red", 0xFFC8102E), white),
            ),
        ),
        Brand(
            "suzuki", "Suzuki", 0xFF0F4C9C, heritageAccent = 0xFFE30613, gauge = GaugeStyle.MINIMAL, tagline = "Way of Life!",
            models = listOf(
                m("Access 125", 2007, Paint("Pearl Grace White", 0xFFEDEEF0), Paint("Metallic Matte Bordeaux Red", 0xFF6E1E2B), black),
                m("Burgman Street 125", 2018, Paint("Metallic Matte Black", 0xFF1E1F22), Paint("Pearl Mirage White", 0xFFEDEEF0)),
                m("Avenis 125", 2021, Paint("Champion Yellow", 0xFFF2C21B), Paint("Pearl Blaze Orange", 0xFFE06A2B), black),
                m("Gixxer", 2014, Paint("Metallic Triton Blue", 0xFF0F4C9C), Paint("Glass Sparkle Black", 0xFF16171A)),
                m("Gixxer SF 250", 2019, Paint("Metallic Triton Blue", 0xFF0F4C9C), Paint("Matte Black", 0xFF1E1F22)),
                m("V-Strom SX", 2022, Paint("Champion Yellow", 0xFFF2C21B), Paint("Pearl Blaze Orange", 0xFFE06A2B), Paint("Glass Sparkle Black", 0xFF16171A)),
                m("Hayabusa", 1999, Paint("Glass Sparkle Black", 0xFF16171A), Paint("Pearl Brilliant White", 0xFFEDEEF0), Paint("Metallic Thunder Grey", 0xFF5D6066)),
            ),
        ),
        Brand(
            "ather", "Ather Energy", 0xFF00C853, gauge = GaugeStyle.MINIMAL, tagline = "Warp mode on",
            models = listOf(
                m("450X", 2020, Paint("Space Grey", 0xFF5D6066), Paint("Still White", 0xFFEDEEF0), Paint("Cosmic Black", 0xFF16171A)),
                m("450 Apex", 2024, Paint("Indium Blue", 0xFF2E5E9E)),
                m("Rizta", 2024, Paint("Siachen White", 0xFFEDEEF0), Paint("Cyan Blue", 0xFF2B8FB0), Paint("Royal Purple", 0xFF5C3B7A)),
            ),
        ),
        Brand(
            "ola", "Ola Electric", 0xFFB8E01E, gauge = GaugeStyle.MINIMAL, tagline = "Electric for all",
            models = listOf(
                m("S1 Pro", 2021, Paint("Jet Black", 0xFF16171A), Paint("Porcelain White", 0xFFEDEEF0), Paint("Midnight Blue", 0xFF243C66)),
                m("S1 Air", 2023, Paint("Liquid Silver", 0xFFB9BCC1), Paint("Coral Glam", 0xFFE2725B)),
                m("S1 X", 2023, Paint("Stellar", 0xFF8A8D91), Paint("Jet Black", 0xFF16171A)),
                m("Roadster X", 2025, Paint("Ceramic White", 0xFFEDEEF0), Paint("Anthracite", 0xFF3B3E43)),
            ),
        ),
        Brand(
            "kawasaki", "Kawasaki", 0xFF66CC33, heritageAccent = 0xFFE60012, gauge = GaugeStyle.RACE, tagline = "Let the good times roll",
            models = listOf(
                m("Ninja 300", 2013, Paint("Lime Green", 0xFF66CC33), Paint("Candy Lime Green", 0xFF3E8E2A), Paint("Metallic Moondust Grey", 0xFF5D6066)),
                m("Ninja 500", 2024, Paint("Metallic Matte Carbon Grey", 0xFF3B3E43), Paint("Lime Green", 0xFF66CC33)),
                m("Z900", 2017, Paint("Metallic Spark Black", 0xFF16171A), Paint("Lime Green", 0xFF66CC33)),
                m("Versys 650", 2015, Paint("Lime Green", 0xFF66CC33), Paint("Metallic Flat Spark Black", 0xFF1E1F22)),
                m("KLX 230", 2024, Paint("Lime Green", 0xFF66CC33)),
            ),
        ),
        Brand(
            "triumph", "Triumph", 0xFFE8E8E8, gauge = GaugeStyle.CLASSIC, tagline = "For the ride",
            models = listOf(
                m("Speed 400", 2023, Paint("Racing Red", 0xFFB01E23), Paint("Phantom Black", 0xFF16171A), Paint("Caspian Blue", 0xFF2E5E9E)),
                m("Scrambler 400 X", 2023, Paint("Matte Khaki Green", 0xFF6E6A4A), Paint("Carnival Red", 0xFFA51C24), Paint("Phantom Black", 0xFF16171A)),
                m("Speed T4", 2025, Paint("Baja Orange", 0xFFE06A2B), Paint("Lava Red", 0xFFA51C24)),
                m("Street Triple 765", 2017, Paint("Silver Ice", 0xFFB9BCC1), Paint("Cosmic Yellow", 0xFFE3C21B)),
                m("Tiger 900", 2020, Paint("Snowdonia White", 0xFFEDEEF0), Paint("Sapphire Black", 0xFF16171A)),
            ),
        ),
        Brand(
            "bmw", "BMW Motorrad", 0xFF0166B1, gauge = GaugeStyle.MINIMAL, tagline = "Make Life a Ride",
            models = listOf(
                m("G 310 R", 2018, Paint("Racing Red", 0xFFC8102E), Paint("Cosmic Black", 0xFF16171A), Paint("Polar White", 0xFFEDEEF0)),
                m("G 310 GS", 2018, Paint("Racing Red", 0xFFC8102E), Paint("Polar White", 0xFFEDEEF0)),
                m("G 310 RR", 2022, Paint("Style Sport", 0xFF0166B1), Paint("Black Storm Metallic", 0xFF16171A)),
                m("F 900 GS", 2024, Paint("Racing Red", 0xFFC8102E), Paint("Light White", 0xFFEDEEF0)),
                m("R 1300 GS", 2023, Paint("Light White", 0xFFEDEEF0), Paint("Triple Black", 0xFF16171A)),
            ),
        ),
        Brand(
            "harley", "Harley-Davidson", 0xFFF26F21, gauge = GaugeStyle.CLASSIC, tagline = "All for Freedom",
            models = listOf(
                m("X440", 2023, Paint("Vivid Black", 0xFF16171A), Paint("Mustard Denim", 0xFFC8A040), Paint("Metallic Thick Red", 0xFF8E1D24)),
                m("X440 T", 2025, Paint("Pearl White", 0xFFEDEEF0), Paint("Vivid Black", 0xFF16171A)),
                m("Nightster", 2022, Paint("Vivid Black", 0xFF16171A), Paint("Gunship Grey", 0xFF5D6066)),
                m("Pan America", 2021, Paint("Vivid Black", 0xFF16171A), Paint("Baja Orange", 0xFFF26F21)),
            ),
        ),
        Brand(
            "jawa_yezdi", "Jawa / Yezdi", 0xFF7A1F2B, gauge = GaugeStyle.CLASSIC, tagline = "Forever Original",
            models = listOf(
                m("Jawa 42", 2019, Paint("Halley's Teal", 0xFF2B7A78), Paint("Orion Red", 0xFFA51C24), Paint("Sirius White", 0xFFEDEEF0)),
                m("Jawa 350", 2024, Paint("Maroon", 0xFF7A1F2B), Paint("Black", 0xFF16171A), Paint("Mystique Orange", 0xFFD9601E)),
                m("Jawa Perak", 2020, Paint("Matte Black", 0xFF1E1F22)),
                m("Yezdi Roadster", 2022, Paint("Smoke Grey", 0xFF6E7176), Paint("Hunter Green", 0xFF2F4A33)),
                m("Yezdi Adventure", 2022, Paint("Tornado Black", 0xFF16171A), Paint("Glacier White", 0xFFEDEEF0)),
            ),
        ),
        Brand(
            "ducati", "Ducati", 0xFFCC0000, gauge = GaugeStyle.RACE, tagline = "Style, Sophistication, Performance",
            models = listOf(
                m("Scrambler Icon", 2015, Paint("'62 Yellow", 0xFFF2C21B), Paint("Ducati Red", 0xFFCC0000)),
                m("Monster", 2021, Paint("Ducati Red", 0xFFCC0000), Paint("Dark Stealth", 0xFF16171A)),
                m("Panigale V2", 2020, Paint("Ducati Red", 0xFFCC0000)),
                m("Multistrada V2", 2022, Paint("Ducati Red", 0xFFCC0000), Paint("Storm Green", 0xFF3E4A3A)),
            ),
        ),
    )

    fun brand(id: String?): Brand? = brands.firstOrNull { it.id == id }

    fun model(brandId: String?, name: String?): Model? = brand(brandId)?.models?.firstOrNull { it.name == name }

    @Suppress("unused")
    internal val neutralPaints = listOf(black, white, grey, silver, red, blue)
}
