package pl.drogowskaz.nav

/** Rodzaj trasy. */
enum class Profile(val label: String, val icon: String) {
    CAR_FASTEST("Najszybsza", "🚗"),
    CAR_SHORTEST("Najkrótsza", "🚗"),
    BIKE("Rower", "🚲"),
    FOOT("Pieszo", "🚶");

    val isCar get() = this == CAR_FASTEST || this == CAR_SHORTEST

    /** Szacowana prędkość przejazdu odcinka [m/s] - do czasu dojazdu. */
    fun speedMs(g: RoadGraph, e: Int, dir: Int): Double = when (this) {
        CAR_FASTEST, CAR_SHORTEST -> carSpeedMs(g, e, dir)
        BIKE -> (if (g.unpaved(e)) 12.0 else 18.0) / 3.6
        FOOT -> (if (g.roadClass(e) == RoadClass.STEPS) 2.5 else 5.0) / 3.6
    }

    /** Koszt przejazdu 1 metra odcinka (im mniej tym lepiej). */
    fun costPerMeter(g: RoadGraph, e: Int, dir: Int): Double {
        val c = g.roadClass(e)
        return when (this) {
            CAR_FASTEST -> {
                var k = 1.0 / carSpeedMs(g, e, dir)
                if (c == RoadClass.SERVICE || c == RoadClass.TRACK) k *= 1.5
                if (g.unpaved(e)) k *= 1.5
                k
            }
            CAR_SHORTEST -> RoadClass.SHORTEST_FACTOR[c] * (if (g.unpaved(e)) 2.0 else 1.0)
            BIKE -> {
                var f = RoadClass.BIKE_FACTOR[c]
                if (g.bikeDesignated(e)) f = minOf(f, 0.85)
                if (g.unpaved(e)) f *= 1.3
                f / speedMs(g, e, dir)
            }
            FOOT -> RoadClass.FOOT_FACTOR[c] / speedMs(g, e, dir)
        }
    }

    /** Dolne ograniczenie kosztu na metr (dla heurystyki A*). */
    val minCostPerMeter: Double
        get() = when (this) {
            CAR_FASTEST -> 1.0 / (145.0 / 3.6)
            CAR_SHORTEST -> 1.0
            BIKE -> 0.8 / (18.0 / 3.6)
            FOOT -> 0.9 / (5.0 / 3.6)
        }

    companion object {
        fun carSpeedMs(g: RoadGraph, e: Int, dir: Int): Double {
            val c = g.roadClass(e)
            val def = RoadClass.DEFAULT_KMH[c].toDouble()
            val max = g.maxSpeed(e, dir)
            val kmh = if (max > 0) minOf(max * 0.92, def * 1.4) else def
            return kmh.coerceAtLeast(5.0) / 3.6
        }
    }
}
