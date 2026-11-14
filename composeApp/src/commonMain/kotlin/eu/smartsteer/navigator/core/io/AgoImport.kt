package eu.smartsteer.navigator.core.io

import eu.smartsteer.navigator.core.field.FieldData
import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.hw.AbLine
import eu.smartsteer.navigator.core.settings.HeadingFrom
import eu.smartsteer.navigator.core.settings.SteerAlgorithm
import eu.smartsteer.navigator.core.settings.SteerEnable
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.cos

/**
 * „Kopiuj z AgOpenGPS”: profile pojazdu/narzędzia (XML) i pola (Field.txt, Boundary.txt, TrackLines.txt).
 *
 * Formaty według AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), pliki
 * SourceCode/AgLibrary/Settings/XmlSettingsHandler.cs, SourceCode/GPS/Properties/VehicleSettings.cs, ToolSettings.cs,
 * SourceCode/GPS/IO/FieldPlaneFiles.cs, BoundaryFiles.cs, TrackFiles.cs, SourceCode/AgOpenGPS.Core/Models/Base/LocalPlane.cs.
 */
object AgoImport {

    // ---------------- ustawienia ----------------

    /** `<setting name="X" serializeAs="String"><value>…</value></setting>` → mapa nazwa → wartość. */
    fun parseSettings(xml: String): Map<String, String> =
        Regex("<setting\\s+name=\"([^\"]+)\"[^>]*>\\s*<value>([^<]*)</value>", RegexOption.MULTILINE)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2].trim() }

    /**
     * Nakłada ustawienia z AgOpenGPS na profil (pojazd i narzędzie mogą być w osobnych plikach —
     * wtedy wywołać dwa razy). Nieznane i brakujące pola zostają bez zmian. Zwraca profil i liczbę przeniesionych pól.
     */
    fun applySettings(base: VehicleProfile, s: Map<String, String>): Pair<VehicleProfile, Int> {
        var n = 0
        fun d(k: String): Double? = s[k]?.replace(',', '.')?.toDoubleOrNull()?.also { n++ }
        fun i(k: String): Int? = s[k]?.trim()?.toIntOrNull()?.also { n++ }
        fun b(k: String): Boolean? = s[k]?.trim()?.lowercase()?.let { if (it == "true") true else if (it == "false") false else null }?.also { n++ }
        var p = base
        d("setVehicle_wheelbase")?.let { p = p.copy(wheelbase = it) }
        d("setVehicle_trackWidth")?.let { p = p.copy(trackWidth = it) }
        d("setVehicle_antennaHeight")?.let { p = p.copy(antennaHeight = it) }
        d("setVehicle_antennaPivot")?.let { p = p.copy(antennaPivot = it) }
        d("setVehicle_antennaOffset")?.let { p = p.copy(antennaOffset = it) }
        d("setVehicle_maxSteerAngle")?.let { p = p.copy(maxSteerAngle = it) }
        d("setAS_maxSteerSpeed")?.let { p = p.copy(maxSteerSpeed = it) }
        d("setAS_minSteerSpeed")?.let { p = p.copy(minSteerSpeed = it) }
        // narzędzie
        d("setVehicle_toolWidth")?.let { p = p.copy(toolWidth = it) }
        d("setVehicle_toolOverlap")?.let { p = p.copy(toolOverlap = it) }
        d("setVehicle_hitchLength")?.let { p = p.copy(hitchLength = it) }
        d("setVehicle_toolOffset")?.let { p = p.copy(toolOffset = it) }
        i("setAS_deadZoneHeading")?.let { p = p.copy(deadZoneDeg = it / 100.0) }
        i("setAS_deadZoneDelay")?.let { p = p.copy(deadZoneDelay = it) }
        d("setGPS_dualHeadingOffset")?.let { p = p.copy(dualHeadingOffset = it) }
        i("setArdMac_setting0")?.let { v -> p = p.copy(machineInvertRelays = v and 1 != 0, hydEnabled = v and 2 != 0) }
        i("setArdMac_hydRaiseTime")?.let { p = p.copy(hydRaiseTime = it) }
        i("setArdMac_hydLowerTime")?.let { p = p.copy(hydLowerTime = it) }
        i("setArdMac_user1")?.let { p = p.copy(machineUser1 = it) }
        i("setArdMac_user2")?.let { p = p.copy(machineUser2 = it) }
        i("setArdMac_user3")?.let { p = p.copy(machineUser3 = it) }
        i("setArdMac_user4")?.let { p = p.copy(machineUser4 = it) }
        b("setTool_isToolTrailing")?.let { p = p.copy(toolTrailing = it) }
        d("setVehicle_toolTrailingHitchLength")?.let { p = p.copy(trailingHitchLength = it) }
        b("setTool_isToolTBT")?.let { p = p.copy(toolTbt = it) }
        d("setVehicle_tankTrailingHitchLength")?.let { p = p.copy(tankHitchLength = it) }
        d("setTool_trailingToolToPivotLength")?.let { p = p.copy(trailingToolToPivot = it) }
        d("setVehicle_vehicleType")?.let { p = p.copy(articulated = it.toInt() == 2) }
        i("setVehicle_numSections")?.let { p = p.copy(numSections = it.coerceIn(1, 16)) }
        d("setVehicle_toolLookAheadOn")?.let { p = p.copy(lookAheadOn = it) }
        d("setVehicle_toolLookAheadOff")?.let { p = p.copy(lookAheadOff = it) }
        d("setVehicle_toolOffDelay")?.let { p = p.copy(turnOffDelay = it) }
        d("setVehicle_minCoverage")?.let { p = p.copy(minCoverage = it.toInt().coerceIn(0, 100)) }
        d("setVehicle_slowSpeedCutoff")?.let { p = p.copy(slowSpeedCutoff = it) }
        b("setTool_isSectionOffWhenOut")?.let { p = p.copy(sectionOffWhenOut = it) }
        b("setF_isWorkSwitchEnabled")?.let { p = p.copy(useWorkSwitch = it) }
        b("setF_isWorkSwitchActiveLow")?.let { p = p.copy(workSwitchActiveLow = it) }
        // prowadzenie
        b("setVehicle_isStanleyUsed")?.let { p = p.copy(algorithm = if (it) SteerAlgorithm.STANLEY else SteerAlgorithm.PURE_PURSUIT) }
        d("setVehicle_goalPointLookAheadHold")?.let { p = p.copy(lookAheadHold = it) }
        d("setVehicle_goalPointLookAheadMult")?.let { p = p.copy(lookAheadMult = it) }
        d("setVehicle_goalPointAcquireFactor")?.let { p = p.copy(acquireFactor = it) }
        d("purePursuitIntegralGainAB")?.let { p = p.copy(purePursuitIntegral = it) }
        d("stanleyDistanceErrorGain")?.let { p = p.copy(stanleyDistanceGain = it) }
        d("stanleyHeadingErrorGain")?.let { p = p.copy(stanleyHeadingGain = it) }
        d("stanleyIntegralGainAB")?.let { p = p.copy(stanleyIntegral = it) }
        d("setAS_sideHillComp")?.let { p = p.copy(sideHillComp = it) }
        d("set_youTurnRadius")?.let { p = p.copy(youTurnRadius = it) }
        i("set_youSkipWidth")?.let { p = p.copy(youTurnSkip = it.coerceIn(1, 20)) }
        d("set_youTurnDistanceFromBoundary")?.let { p = p.copy(youTurnFromBoundary = it) }
        // IMU
        d("setIMU_rollZero")?.let { p = p.copy(rollZero = it) }
        d("setIMU_rollFilter")?.let { p = p.copy(rollFilter = it) }
        b("setIMU_invertRoll")?.let { p = p.copy(invertRoll = it) }
        d("setIMU_fusionWeight2")?.let { p = p.copy(fusionWeight = it) }
        s["setGPS_headingFromWhichSource"]?.trim()?.let { v -> HeadingFrom.entries.firstOrNull { it.ago.equals(v, true) }?.let { n++; p = p.copy(headingFrom = it) } }
        b("setIMU_isReverseOn")?.let { p = p.copy(reverseDetect = it) }
        d("setGPS_minimumStepLimit")?.let { p = p.copy(minStepM = it) }
        d("setF_minHeadingStepDistance")?.let { p = p.copy(minHeadingStepM = it) }
        d("setGPS_forwardComp")?.let { p = p.copy(forwardComp = it) }
        d("setGPS_reverseComp")?.let { p = p.copy(reverseComp = it) }
        b("setAutoSwitchDualFixOn")?.let { p = p.copy(autoSwitchDualFix = it) }
        d("setAutoSwitchDualFixSpeed")?.let { p = p.copy(autoSwitchDualFixSpeed = it) }
        b("setIMU_isDualAsIMU")?.let { p = p.copy(dualAsImu = it) }
        d("setGPS_dualReverseDetectionDistance")?.let { p = p.copy(dualReverseDistM = it) }
        // moduł skrętu (PGN 252)
        i("setAS_Kp")?.let { p = p.copy(gainP = it) }
        i("setAS_highSteerPWM")?.let { p = p.copy(highPwm = it) }
        i("setAS_lowSteerPWM")?.let { p = p.copy(lowPwm = it) }
        i("setAS_minSteerPWM")?.let { p = p.copy(minPwm = it) }
        i("setAS_countsPerDegree")?.let { p = p.copy(countsPerDegree = it) }
        i("setAS_wasOffset")?.let { p = p.copy(wasOffset = it) }
        i("setAS_ackerman")?.let { p = p.copy(ackerman = it) }
        // płytka (PGN 251) — bity jak w FormSteer.cs
        i("setArdSteer_setting0")?.let { v ->
            p = p.copy(
                invertWas = v and 1 != 0, invertRelays = v and 2 != 0, invertMotor = v and 4 != 0,
                singleInput = v and 8 != 0, cytron = v and 16 != 0,
                steerEnable = when { v and 32 != 0 -> SteerEnable.SWITCH; v and 64 != 0 -> SteerEnable.BUTTON; else -> SteerEnable.NONE },
                encoder = v and 128 != 0,
            )
        }
        i("setArdSteer_setting1")?.let { v ->
            p = p.copy(danfoss = v and 1 != 0, pressureSensor = v and 2 != 0, currentSensor = v and 4 != 0, axisY = v and 8 != 0)
        }
        i("setArdSteer_maxPulseCounts")?.let { p = p.copy(maxPulseCounts = it) }
        // szerokości sekcji z pozycji krawędzi (setSection_position1…17, od lewej)
        val pos = (1..17).mapNotNull { k -> s["setSection_position$k"]?.replace(',', '.')?.toDoubleOrNull() }
        val ns = p.numSections.coerceIn(1, 64)
        if (pos.size > ns) {
            val widths = (0 until ns).map { pos[it + 1] - pos[it] }
            if (widths.all { it > 0.01 }) {
                n++
                val equal = widths.all { kotlin.math.abs(it - widths[0]) < 0.005 }
                p = p.copy(sectionWidths = if (equal) emptyList() else widths)
            }
        }
        return p to n
    }

    // ---------------- pola ----------------

    /** Field.txt → początek pola (szerokość, długość) z linii po „StartFix”. */
    fun parseOrigin(fieldTxt: String): Pair<Double, Double>? {
        val lines = fieldTxt.lines()
        val i = lines.indexOfFirst { it.trim().startsWith("StartFix", ignoreCase = true) }
        if (i < 0 || i + 1 >= lines.size) return null
        val p = lines[i + 1].split(',')
        val lat = p.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val lon = p.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    /** Boundary.txt → pierścienie (pierwszy = granica zewnętrzna) we współrzędnych AgOpenGPS (wschód, północ). */
    fun parseBoundary(text: String): List<List<Vec2>> {
        val out = ArrayList<List<Vec2>>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var k = 0
        if (k < lines.size && lines[k].startsWith("$")) k++
        while (k < lines.size) {
            while (k < lines.size && (lines[k].equals("true", true) || lines[k].equals("false", true))) k++
            val count = lines.getOrNull(k)?.toIntOrNull() ?: break
            k++
            val ring = ArrayList<Vec2>()
            repeat(count) {
                val p = lines.getOrNull(k)?.split(',')
                k++
                val e = p?.getOrNull(0)?.toDoubleOrNull()
                val n = p?.getOrNull(1)?.toDoubleOrNull()
                if (e != null && n != null) ring.add(Vec2(e, n))
            }
            if (ring.size >= 3) out.add(ring)
        }
        return out
    }

    class Track(val name: String, val mode: Int, val a: Vec2, val b: Vec2, val curve: List<Vec2>)

    /** TrackLines.txt → linie (tryb 2 = AB, 4 = krzywa). */
    fun parseTracks(text: String): List<Track> {
        val lines = text.lines()
        if (lines.isEmpty() || !lines[0].trim().startsWith("$")) return emptyList()
        val twol = lines[0].trim() == "\$TwolTracks"
        val out = ArrayList<Track>()
        var k = 1
        fun next(): String? = lines.getOrNull(k++)
        fun pt(s: String?): Vec2? {
            val p = s?.split(',') ?: return null
            return Vec2(p.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null, p.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null)
        }
        while (k < lines.size) {
            val name = next()?.trim() ?: break
            if (name.isEmpty()) continue
            next() // kurs
            val a = pt(next()) ?: break
            val b = pt(next()) ?: break
            next() // przesunięcie
            val mode = next()?.trim()?.toIntOrNull() ?: break
            next() // widoczność
            val count = next()?.trim()?.toIntOrNull() ?: break
            val curve = ArrayList<Vec2>()
            repeat(count) { pt(next())?.let { curve.add(it) } }
            if (twol) { next(); next() }
            out.add(Track(name, mode, a, b, curve))
        }
        return out
    }

    /**
     * Pole AgOpenGPS → pole Navigatora: ten sam początek, punkty przeliczone przez współrzędne geograficzne
     * (AgOpenGPS liczy długość z metrów na stopień przy szerokości punktu).
     */
    fun toField(name: String, fieldTxt: String, boundaryTxt: String?, tracksTxt: String?): FieldData? {
        val (lat0, lon0) = parseOrigin(fieldTxt) ?: return null
        val ours = LocalPlane(lat0, lon0)
        val mLat0 = mLat(lat0)
        fun conv(p: Vec2): Vec2 {
            val lat = lat0 + p.y / mLat0
            val lon = lon0 + p.x / mLon(lat)
            return ours.toLocal(lat, lon)
        }
        val boundary = boundaryTxt?.let { parseBoundary(it).firstOrNull() }?.map { conv(it) }
            ?.let { Polygon.simplify(it, minSpacing = 0.5, tolerance = 0.02) }?.takeIf { it.size >= 3 }
        val tracks = tracksTxt?.let { parseTracks(it) }.orEmpty()
        val ab = tracks.firstOrNull { it.mode == 2 }?.let { AbLine(conv(it.a), conv(it.b)) }
        val curve = tracks.firstOrNull { it.mode == 4 && it.curve.size >= 2 }?.curve?.map { conv(it) }
        // wszystkie linie z AgOpenGPS (AB = tryb 2, krzywa = tryb 4) i wewnętrzne granice jako przeszkody
        val all = tracks.mapNotNull { t ->
            when {
                t.mode == 2 -> eu.smartsteer.navigator.core.field.Track(t.name.ifBlank { "AB" }, AbLine(conv(t.a), conv(t.b)), null)
                t.mode == 4 && t.curve.size >= 2 -> eu.smartsteer.navigator.core.field.Track(t.name.ifBlank { "Krzywa" }, null, t.curve.map { conv(it) })
                else -> null
            }
        }
        val inner = boundaryTxt?.let { parseBoundary(it).drop(1) }.orEmpty()
            .map { r -> Polygon.simplify(r.map { conv(it) }, minSpacing = 0.5, tolerance = 0.02) }.filter { it.size >= 3 }
        return FieldData(
            name, lat0, lon0, boundary, 0.0, ab, curve,
            tracks = all, activeTrack = if (all.isEmpty()) -1 else all.indexOfFirst { it.ab != null }.takeIf { it >= 0 } ?: 0,
            obstacles = inner,
        )
    }

    private fun mLat(latDeg: Double): Double {
        val p = latDeg * PI / 180
        return 111132.92 - 559.82 * cos(2 * p) + 1.175 * cos(4 * p) - 0.0023 * cos(6 * p)
    }

    private fun mLon(latDeg: Double): Double {
        val p = latDeg * PI / 180
        return 111412.84 * cos(p) - 93.5 * cos(3 * p) + 0.118 * cos(5 * p)
    }
}
