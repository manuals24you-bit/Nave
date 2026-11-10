package pl.drogowskaz.nav

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/** Klasy dróg - kolejność zgodna z tools/build_graph.py */
object RoadClass {
    const val MOTORWAY = 0
    const val MOTORWAY_LINK = 1
    const val TRUNK = 2
    const val TRUNK_LINK = 3
    const val PRIMARY = 4
    const val PRIMARY_LINK = 5
    const val SECONDARY = 6
    const val SECONDARY_LINK = 7
    const val TERTIARY = 8
    const val TERTIARY_LINK = 9
    const val UNCLASSIFIED = 10
    const val RESIDENTIAL = 11
    const val LIVING_STREET = 12
    const val SERVICE = 13
    const val ROAD = 14
    const val TRACK = 15
    const val PEDESTRIAN = 16
    const val FOOTWAY = 17
    const val CYCLEWAY = 18
    const val PATH = 19
    const val STEPS = 20
    const val BRIDLEWAY = 21

    /** Domyślna prędkość przejazdu [km/h] gdy brak limitu. */
    val DEFAULT_KMH = intArrayOf(120, 60, 90, 50, 70, 45, 60, 40, 50, 35, 40, 30, 10, 15, 25,
        15, 5, 5, 5, 5, 5, 5)

    /** Mnożnik "jakości" drogi dla trasy najkrótszej - unika słabych dróg. */
    val SHORTEST_FACTOR = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.05, 1.05, 1.3, 1.6, 3.0, 4.0, 2.0,
        5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0)

    /** Preferencje roweru (mnożnik czasu): <1 = chętniej. */
    val BIKE_FACTOR = doubleArrayOf(99.0, 99.0, 3.0, 3.0, 2.0, 2.0, 1.5, 1.5, 1.15, 1.15, 1.1, 1.0, 1.0, 1.1, 1.2,
        1.3, 1.3, 1.25, 0.8, 1.0, 5.0, 2.0)

    /** Preferencje pieszego (mnożnik czasu). */
    val FOOT_FACTOR = doubleArrayOf(99.0, 99.0, 2.0, 2.0, 1.4, 1.4, 1.25, 1.25, 1.1, 1.1, 1.1, 1.0, 0.9, 1.0, 1.0,
        1.1, 0.9, 0.9, 1.3, 0.95, 1.2, 1.1)

    fun isLink(c: Int) = c == MOTORWAY_LINK || c == TRUNK_LINK || c == PRIMARY_LINK ||
        c == SECONDARY_LINK || c == TERTIARY_LINK

    fun isHighway(c: Int) = c == MOTORWAY || c == TRUNK
}

/**
 * Graf drogowy zapisany w pliku roads.bin (mapowany do pamięci - nie zajmuje RAM-u).
 */
class RoadGraph private constructor(private val buf: ByteBuffer) {
    val nodeCount: Int
    val edgeCount: Int
    private val geomCount: Int
    private val adjCount: Int
    private val cellCount: Int
    private val cellEntryCount: Int
    private val nameCount: Int
    private val nameBytesLen: Int
    private val gridMinLat: Double
    private val gridMinLon: Double
    private val cellDeg: Double
    private val gridCols: Int
    private val gridRows: Int

    private val nodeLatB: IntBuffer
    private val nodeLonB: IntBuffer
    private val edgeFromB: IntBuffer
    private val edgeToB: IntBuffer
    private val geomStartB: IntBuffer
    private val edgeLenB: FloatBuffer
    private val edgeAttrB: IntBuffer
    private val edgeNameB: IntBuffer
    private val gLatB: IntBuffer
    private val gLonB: IntBuffer
    private val adjStartB: IntBuffer
    private val adjB: IntBuffer
    private val cellKeyB: IntBuffer
    private val cellStartB: IntBuffer
    private val cellEdgeB: IntBuffer
    private val nameStartB: IntBuffer
    private val nameBytesOffset: Int
    private val restrictionCount: Int
    private val rFromB: IntBuffer
    private val rToB: IntBuffer
    private val rKindB: IntBuffer

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val h = IntArray(16) { buf.getInt(it * 4) }
        require(h[0] == 0x4E415647) { "Nieprawidłowy plik roads.bin" }
        require(h[1] == 2) { "Dane w starym formacie (v${h[1]}) - pobierz mapy ponownie." }
        nodeCount = h[2]; edgeCount = h[3]; geomCount = h[4]; adjCount = h[5]
        cellCount = h[6]; cellEntryCount = h[7]; nameCount = h[8]; nameBytesLen = h[9]
        gridMinLat = h[10] / 1e7; gridMinLon = h[11] / 1e7; cellDeg = h[12] / 1e7
        gridCols = h[13]; gridRows = h[14]
        restrictionCount = h[15]

        var off = 64
        fun ints(n: Int): IntBuffer {
            val b = slice(off, n * 4).asIntBuffer(); off += n * 4; return b
        }
        fun floats(n: Int): FloatBuffer {
            val b = slice(off, n * 4).asFloatBuffer(); off += n * 4; return b
        }
        nodeLatB = ints(nodeCount); nodeLonB = ints(nodeCount)
        edgeFromB = ints(edgeCount); edgeToB = ints(edgeCount); geomStartB = ints(edgeCount + 1)
        edgeLenB = floats(edgeCount); edgeAttrB = ints(edgeCount); edgeNameB = ints(edgeCount)
        gLatB = ints(geomCount); gLonB = ints(geomCount)
        adjStartB = ints(nodeCount + 1); adjB = ints(adjCount)
        cellKeyB = ints(cellCount); cellStartB = ints(cellCount + 1); cellEdgeB = ints(cellEntryCount)
        nameStartB = ints(nameCount + 1)
        nameBytesOffset = off
        off += (nameBytesLen + 3) and 3.inv()
        rFromB = ints(restrictionCount); rToB = ints(restrictionCount); rKindB = ints(restrictionCount)
    }

    private fun slice(offset: Int, len: Int): ByteBuffer {
        val d = buf.duplicate()
        d.position(offset); d.limit(offset + len)
        return d.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    fun nodeLat(n: Int) = nodeLatB.get(n) / 1e7
    fun nodeLon(n: Int) = nodeLonB.get(n) / 1e7
    fun edgeFrom(e: Int) = edgeFromB.get(e)
    fun edgeTo(e: Int) = edgeToB.get(e)
    fun geomStart(e: Int) = geomStartB.get(e)
    fun geomEnd(e: Int) = geomStartB.get(e + 1) // wyłącznie
    fun edgeLen(e: Int) = edgeLenB.get(e).toDouble()
    fun attr(e: Int) = edgeAttrB.get(e)
    fun roadClass(e: Int) = attr(e) and 31
    fun fwdOnly(e: Int) = (attr(e) and (1 shl 5)) != 0
    fun bwdOnly(e: Int) = (attr(e) and (1 shl 6)) != 0
    fun isRoundabout(e: Int) = (attr(e) and (1 shl 7)) != 0
    fun isToll(e: Int) = (attr(e) and (1 shl 24)) != 0
    fun isTunnel(e: Int) = (attr(e) and (1 shl 25)) != 0
    fun canTraverse(e: Int, dir: Int) = if (dir == 0) !bwdOnly(e) else !fwdOnly(e)
    fun bikeDesignated(e: Int) = (attr(e) and (1 shl 26)) != 0
    fun carNo(e: Int) = (attr(e) and (1 shl 27)) != 0
    fun bikeNo(e: Int) = (attr(e) and (1 shl 28)) != 0
    fun footNo(e: Int) = (attr(e) and (1 shl 29)) != 0
    fun bikeOnewayFree(e: Int) = (attr(e) and (1 shl 30)) != 0
    fun unpaved(e: Int) = attr(e) < 0

    /** Czy profil może przejechać/przejść odcinek e w kierunku dir. */
    fun allowed(e: Int, dir: Int, p: Profile): Boolean = when (p) {
        Profile.CAR_FASTEST, Profile.CAR_SHORTEST -> !carNo(e) && canTraverse(e, dir)
        Profile.BIKE -> !bikeNo(e) && (bikeOnewayFree(e) || canTraverse(e, dir))
        Profile.FOOT -> !footNo(e)
    }

    /** Czy odcinek w ogóle nadaje się dla profilu (bez kierunku). */
    fun usable(e: Int, p: Profile): Boolean = when (p) {
        Profile.CAR_FASTEST, Profile.CAR_SHORTEST -> !carNo(e)
        Profile.BIKE -> !bikeNo(e)
        Profile.FOOT -> !footNo(e)
    }

    /** Limit prędkości w danym kierunku (0 = brak danych). */
    fun maxSpeed(e: Int, dir: Int): Int {
        val a = attr(e)
        return if (dir == 0) (a shr 8) and 0xFF else (a shr 16) and 0xFF
    }

    /**
     * Czy z łuku przyjazdu [arrive] (edge*2+kierunek) wolno skręcić w łuk [leave].
     * Uwzględnia zakazy (no_*) i nakazy (only_*) z OpenStreetMap.
     */
    fun turnAllowed(arrive: Int, leave: Int): Boolean {
        if (restrictionCount == 0) return true
        var lo = 0
        var hi = restrictionCount
        while (lo < hi) { // pierwszy wpis o kluczu >= arrive
            val mid = (lo + hi) ushr 1
            if (rFromB.get(mid) < arrive) lo = mid + 1 else hi = mid
        }
        var onlyFound = false
        var onlyMatch = false
        var i = lo
        while (i < restrictionCount && rFromB.get(i) == arrive) {
            val to = rToB.get(i)
            if (rKindB.get(i) == 0) { if (to == leave) return false }
            else { onlyFound = true; if (to == leave) onlyMatch = true }
            i++
        }
        return !onlyFound || onlyMatch
    }

    fun gLat(i: Int) = gLatB.get(i) / 1e7
    fun gLon(i: Int) = gLonB.get(i) / 1e7

    fun adjStart(n: Int) = adjStartB.get(n)
    fun adjEnd(n: Int) = adjStartB.get(n + 1)
    fun adj(i: Int) = adjB.get(i)

    /** Węzeł końcowy przy przejeździe odcinkiem e w kierunku dir. */
    fun target(e: Int, dir: Int) = if (dir == 0) edgeTo(e) else edgeFrom(e)
    fun source(e: Int, dir: Int) = if (dir == 0) edgeFrom(e) else edgeTo(e)

    private val nameCache = HashMap<Int, String>()

    @Synchronized
    fun name(e: Int): String? {
        val id = edgeNameB.get(e)
        if (id < 0) return null
        nameCache[id]?.let { return it }
        val s = nameStartB.get(id)
        val t = nameStartB.get(id + 1)
        val bytes = ByteArray(t - s)
        val d = buf.duplicate(); d.position(nameBytesOffset + s); d.get(bytes)
        val str = String(bytes, Charsets.UTF_8)
        if (nameCache.size > 5000) nameCache.clear()
        nameCache[id] = str
        return str
    }

    /** Odcinki w promieniu ~radiusM od punktu (z dokładnością do komórki siatki). */
    fun edgesNear(lat: Double, lon: Double, radiusM: Double, out: MutableSet<Int>) {
        val dLat = radiusM / 110540.0
        val dLon = radiusM / (111320.0 * cos(Math.toRadians(lat)))
        val r0 = floor((lat - dLat - gridMinLat) / cellDeg).toInt().coerceAtLeast(0)
        val r1 = floor((lat + dLat - gridMinLat) / cellDeg).toInt().coerceAtMost(gridRows - 1)
        val c0 = floor((lon - dLon - gridMinLon) / cellDeg).toInt().coerceAtLeast(0)
        val c1 = floor((lon + dLon - gridMinLon) / cellDeg).toInt().coerceAtMost(gridCols - 1)
        for (r in r0..r1) for (c in c0..c1) {
            val key = r * gridCols + c
            val idx = findCell(key)
            if (idx < 0) continue
            for (i in cellStartB.get(idx) until cellStartB.get(idx + 1)) out.add(cellEdgeB.get(i))
        }
    }

    private fun findCell(key: Int): Int {
        var lo = 0
        var hi = cellCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = cellKeyB.get(mid)
            if (v < key) lo = mid + 1 else if (v > key) hi = mid - 1 else return mid
        }
        return -1
    }

    fun covers(lat: Double, lon: Double): Boolean =
        lat >= gridMinLat && lon >= gridMinLon &&
            lat <= gridMinLat + gridRows * cellDeg && lon <= gridMinLon + gridCols * cellDeg

    companion object {
        fun load(file: File): RoadGraph {
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                val size = ch.size()
                require(size < Int.MAX_VALUE) { "Plik danych za duży (>2 GB) - użyj mniejszego regionu" }
                val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, size)
                return RoadGraph(mapped)
            }
        }

        @Suppress("unused")
        private fun ceilDiv(a: Double, b: Double) = ceil(a / b).toInt()
    }
}
