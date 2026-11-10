package eu.smartsteer.navigator.core.sound

/** Stan, z którego wynikają dźwięki (sprawdzany co 100 ms). */
data class AlertState(
    val hardware: Boolean,
    val engaged: Boolean,
    /** Ostatnie włączenie/wyłączenie autosteru przez rolnika (s). */
    val userToggleS: Double,
    val gpsOk: Boolean,
    val rtkFix: Boolean,
    val turning: Boolean,
    val anySection: Boolean,
    val newOrders: Int,
    val nowS: Double,
)

/** Zamienia zmiany stanu na dźwięki. Czysta logika — testowalna. */
class AlertTracker {
    private var prev: AlertState? = null
    private var lastSectionSoundS = Double.NEGATIVE_INFINITY

    fun events(cur: AlertState): List<SoundKind> {
        val p = prev
        prev = cur
        if (p == null) return emptyList()
        val out = ArrayList<SoundKind>()
        if (!p.engaged && cur.engaged) out.add(SoundKind.AUTOSTEER_ON)
        if (p.engaged && !cur.engaged) {
            // wyłączył rolnik (przed chwilą dotknął) albo samo — wtedy alarm
            out.add(if (cur.nowS - cur.userToggleS < 0.6) SoundKind.AUTOSTEER_OFF else SoundKind.ALARM)
        }
        if (cur.hardware && p.gpsOk && !cur.gpsOk && SoundKind.ALARM !in out) out.add(SoundKind.ALARM)
        if (cur.hardware && cur.gpsOk && p.rtkFix && !cur.rtkFix) out.add(SoundKind.WARNING)
        if (!p.turning && cur.turning) out.add(SoundKind.UTURN)
        if (p.anySection != cur.anySection && cur.nowS - lastSectionSoundS > 1.0) {
            lastSectionSoundS = cur.nowS
            out.add(if (cur.anySection) SoundKind.SECTION_ON else SoundKind.SECTION_OFF)
        }
        if (cur.newOrders > p.newOrders) out.add(SoundKind.ORDER)
        return out
    }
}
