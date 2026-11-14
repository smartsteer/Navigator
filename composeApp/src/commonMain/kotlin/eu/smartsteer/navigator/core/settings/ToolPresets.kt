package eu.smartsteer.navigator.core.settings

/**
 * Zapisane maszyny (jak „ToolProfiles” w AgOpenGPS): talerzówka, agregat, siewnik… Przy zmianie maszyny ciągnik
 * zostaje ten sam — podmieniamy tylko ustawienia maszyny (szerokość, sekcje, zaczep i odległość od osi, podnośnik, ścieżki).
 */
class ToolPresets(private val files: TextFiles) {
    fun names(): List<String> = files.list().filter { it.endsWith(EXT) }.map { it.removeSuffix(EXT) }.sortedBy { it.lowercase() }

    fun save(name: String, p: VehicleProfile) = files.write(Profiles.sanitize(name) + EXT, p.toJson())

    fun load(name: String): VehicleProfile? = files.read(name + EXT)?.let { VehicleProfile.fromJson(it) }

    fun delete(name: String) = files.delete(name + EXT)

    companion object {
        const val EXT = ".narzedzie"

        /** Ustawienia maszyny z [tool] na profilu ciągnika [base]. */
        fun apply(base: VehicleProfile, tool: VehicleProfile): VehicleProfile = base.copy(
            toolWidth = tool.toolWidth, toolOverlap = tool.toolOverlap, toolOffset = tool.toolOffset,
            sectionWidths = tool.sectionWidths, numSections = tool.numSections,
            hitchLength = tool.hitchLength, toolTrailing = tool.toolTrailing, trailingHitchLength = tool.trailingHitchLength,
            lookAheadOn = tool.lookAheadOn, lookAheadOff = tool.lookAheadOff, slowSpeedCutoff = tool.slowSpeedCutoff,
            sectionOffWhenOut = tool.sectionOffWhenOut, sectionOffInHeadland = tool.sectionOffInHeadland,
            useWorkSwitch = tool.useWorkSwitch, workSwitchActiveLow = tool.workSwitchActiveLow,
            machineInvertRelays = tool.machineInvertRelays, hydEnabled = tool.hydEnabled, hydAuto = tool.hydAuto,
            hydRaiseTime = tool.hydRaiseTime, hydLowerTime = tool.hydLowerTime,
            machineUser1 = tool.machineUser1, machineUser2 = tool.machineUser2, machineUser3 = tool.machineUser3, machineUser4 = tool.machineUser4,
            rateBase = tool.rateBase, rateBaseSpeed = tool.rateBaseSpeed, rateHintMode = tool.rateHintMode, rateStepPct = tool.rateStepPct,
            isSprayer = tool.isSprayer, headlandSeq = tool.headlandSeq, tramEvery = tool.tramEvery, tramTrack = tool.tramTrack, tramWheel = tool.tramWheel, tramShift = tool.tramShift,
            tramWidth = tool.tramWidth, tramPasses = tool.tramPasses, tramStartPass = tool.tramStartPass, tramFlip = tool.tramFlip,
            tramMode = tool.tramMode, tramOuterInverted = tool.tramOuterInverted,
            toolTbt = tool.toolTbt, tankHitchLength = tool.tankHitchLength, trailingToolToPivot = tool.trailingToolToPivot,
            turnOffDelay = tool.turnOffDelay, minCoverage = tool.minCoverage, workSwitchManualSections = tool.workSwitchManualSections,
            hydLiftLookAhead = tool.hydLiftLookAhead,
        )
    }
}
