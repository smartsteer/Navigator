package eu.smartsteer.navigator.core.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Podkład mapy (zdjęcia lotnicze/satelitarne) — kafelki w odwzorowaniu Web Mercator. */
enum class TileSource(val label: String, val short: String, val maxZoom: Int, val attribution: String) {
    NONE("Brak (ciemne tło)", "BRAK", 0, ""),
    /** Ortofotomapa GUGiK (Geoportal) — oficjalne, bezpłatne zdjęcia lotnicze Polski. */
    ORTO_PL("Ortofotomapa Polski (Geoportal)", "ORTO", 19, "© GUGiK — ortofotomapa"),
    /** Esri World Imagery — zdjęcia satelitarne całego świata. */
    ESRI("Satelita (Esri, cały świat)", "SAT", 19, "© Esri, Maxar, Earthstar Geographics"),
    ;

    fun url(z: Int, x: Int, y: Int): String? = when (this) {
        NONE -> null
        ORTO_PL -> ORTO_URL.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
        ESRI -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
    }

    companion object {
        /** WMTS Geoportalu w układzie EPSG:3857 (KVP). */
        var ORTO_URL = "https://mapy.geoportal.gov.pl/wss/service/PZGIK/ORTO/WMTS/HighResolution?SERVICE=WMTS&REQUEST=GetTile" +
            "&VERSION=1.0.0&LAYER=ORTOFOTOMAPA&STYLE=default&FORMAT=image/jpeg&TILEMATRIXSET=EPSG:3857" +
            "&TILEMATRIX=EPSG:3857:{z}&TILEROW={y}&TILECOL={x}"
    }
}

/** Rachunek kafelków XYZ (Web Mercator). */
object TileMath {
    fun tileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)
    fun tileY(lat: Double, z: Int): Double {
        val r = lat * PI / 180
        return (1.0 - ln(tan(r) + 1 / cos(r)) / PI) / 2.0 * (1 shl z)
    }
    fun lon(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0
    fun lat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl z)
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }
    /** Metry na piksel kafelka 256 px na danej szerokości. */
    fun metersPerPixel(lat: Double, z: Int): Double = 156543.03392 * cos(lat * PI / 180) / (1 shl z)

    /** Kafelki (x, y) pokrywające prostokąt geograficzny. */
    fun range(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double, z: Int): List<Pair<Int, Int>> {
        val x0 = floor(tileX(lonMin, z)).toInt(); val x1 = floor(tileX(lonMax, z)).toInt()
        val y0 = floor(tileY(latMax, z)).toInt(); val y1 = floor(tileY(latMin, z)).toInt()
        val out = ArrayList<Pair<Int, Int>>()
        for (x in x0..x1) for (y in y0..y1) out.add(x to y)
        return out
    }
}

/** Dysk i sieć dla kafelków (Windows: Dokumenty\SmartSteer\Mapa, Android: pamięć aplikacji). */
expect object TileIo {
    fun read(key: String): ByteArray?
    fun write(key: String, bytes: ByteArray)
    /** Pobranie kafelka; null przy błędzie / braku internetu. Nie wywoływać z wątku UI. */
    fun download(url: String): ByteArray?
    /** Rozmiar pamięci podręcznej, bajty. */
    fun cacheBytes(): Long
}
