package eu.smartsteer.navigator.core.io

import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.settings.SteerAlgorithm
import eu.smartsteer.navigator.core.settings.SteerEnable
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgoImportTest {
    private val vehicleXml = """<?xml version="1.0" encoding="utf-8"?>
<configuration><userSettings><AgOpenGPS.Properties.VehicleSettings>
<setting name="setVehicle_wheelbase" serializeAs="String"><value>2.85</value></setting>
<setting name="setVehicle_antennaPivot" serializeAs="String"><value>0.6</value></setting>
<setting name="setVehicle_antennaOffset" serializeAs="String"><value>-0.2</value></setting>
<setting name="setAS_Kp" serializeAs="String"><value>70</value></setting>
<setting name="setAS_wasOffset" serializeAs="String"><value>-120</value></setting>
<setting name="setArdSteer_setting0" serializeAs="String"><value>69</value></setting>
<setting name="setIMU_invertRoll" serializeAs="String"><value>True</value></setting>
<setting name="setBrand_TBrand" serializeAs="String"><value>Fendt</value></setting>
</AgOpenGPS.Properties.VehicleSettings></userSettings></configuration>"""

    private val toolXml = """<setting name="setVehicle_toolWidth" serializeAs="String"><value>6</value></setting>
<setting name="setVehicle_numSections" serializeAs="String"><value>4</value></setting>
<setting name="setTool_isToolTrailing" serializeAs="String"><value>False</value></setting>
<setting name="setVehicle_isStanleyUsed" serializeAs="String"><value>True</value></setting>
<setting name="set_youTurnRadius" serializeAs="String"><value>9.5</value></setting>"""

    @Test
    fun settingsFromVehicleAndTool() {
        val (p1, n1) = AgoImport.applySettings(VehicleProfile(), AgoImport.parseSettings(vehicleXml))
        assertEquals(7, n1)
        assertEquals(2.85, p1.wheelbase); assertEquals(0.6, p1.antennaPivot); assertEquals(-0.2, p1.antennaOffset)
        assertEquals(70, p1.gainP); assertEquals(-120, p1.wasOffset); assertEquals(true, p1.invertRoll)
        // 69 = 1 + 4 + 64: odwrócony czujnik, odwrócony silnik, różnicowy, IBT2, przycisk
        assertEquals(true, p1.invertWas); assertEquals(true, p1.invertMotor); assertEquals(false, p1.singleInput)
        assertEquals(false, p1.cytron); assertEquals(SteerEnable.BUTTON, p1.steerEnable)
        assertEquals(69, p1.setting0)
        val (p2, n2) = AgoImport.applySettings(p1, AgoImport.parseSettings(toolXml))
        assertEquals(5, n2)
        assertEquals(6.0, p2.toolWidth); assertEquals(4, p2.numSections); assertEquals(false, p2.toolTrailing)
        assertEquals(SteerAlgorithm.STANLEY, p2.algorithm); assertEquals(9.5, p2.youTurnRadius)
        assertEquals(2.85, p2.wheelbase)
    }

    @Test
    fun fieldWithBoundaryAndTracks() {
        val field = "2026-November-10 08:00:00 AM\n\$FieldDir\nFieldNew\n\$Offsets\n0,0\nConvergence\n0\nStartFix\n52.1773,19.4695\n"
        val boundary = "\$Boundary\nFalse\n4\n-100,-50,0\n100,-50,0\n100,50,0\n-100,50,0\nTrue\n3\n0,0,0\n5,0,0\n0,5,0\n"
        val tracks = "\$TrackLines\nAB 1\n0\n0.000,-40.000\n0.000,40.000\n0\n2\nTrue\n0\n" +
            "Krzywa\n0\n0,0\n0,0\n0\n4\nTrue\n3\n-50.000,-40.000,0\n-50.000,0.000,0\n-45.000,40.000,0\n"
        assertEquals(52.1773 to 19.4695, AgoImport.parseOrigin(field))
        assertEquals(2, AgoImport.parseBoundary(boundary).size)
        val t = AgoImport.parseTracks(tracks)
        assertEquals(listOf("AB 1", "Krzywa"), t.map { it.name })
        val f = assertNotNull(AgoImport.toField("Za stodołą", field, boundary, tracks))
        val area = Polygon.area(f.boundary!!)
        assertTrue(abs(area - 20_000.0) < 20.0, "pole = $area")
        assertTrue(abs(f.ab!!.a.y + 40.0) < 0.05 && abs(f.ab!!.a.x) < 0.05)
        assertEquals(3, f.curve!!.size)
    }

    @Test
    fun sectionPositionsAndMachine() {
        val xml = """<setting name="setVehicle_numSections" serializeAs="String"><value>3</value></setting>
<setting name="setSection_position1" serializeAs="String"><value>-3</value></setting>
<setting name="setSection_position2" serializeAs="String"><value>-1</value></setting>
<setting name="setSection_position3" serializeAs="String"><value>1</value></setting>
<setting name="setSection_position4" serializeAs="String"><value>3.5</value></setting>
<setting name="setVehicle_toolOffset" serializeAs="String"><value>0.25</value></setting>
<setting name="setAS_deadZoneHeading" serializeAs="String"><value>20</value></setting>
<setting name="setArdMac_setting0" serializeAs="String"><value>2</value></setting>"""
        val (p, _) = AgoImport.applySettings(VehicleProfile(), AgoImport.parseSettings(xml))
        assertEquals(listOf(2.0, 2.0, 2.5), p.sectionWidths)
        assertEquals(0.25, p.toolOffset); assertEquals(0.2, p.deadZoneDeg)
        assertEquals(true, p.hydEnabled); assertEquals(false, p.machineInvertRelays)
    }
}
