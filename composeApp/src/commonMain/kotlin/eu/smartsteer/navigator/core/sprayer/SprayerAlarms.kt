package eu.smartsteer.navigator.core.sprayer

/** Alarm opryskiwacza: [loud] = dźwięk alarmu (zawory zamknięte / brak łączności), inaczej ostrzeżenie. */
enum class SprayerAlarm(val text: String, val loud: Boolean) {
    LINK("Brak łączności z płytką opryskiwacza — zawory zamknięte", true),
    FAILSAFE("Płytka zamknęła zawory (brak poleceń)", true),
    RATE("Dawka poza tolerancją ±10 %", false),
    PRESSURE("Ciśnienie poza zakresem dyszy", false),
    RESERVE("Zbiornik: rezerwa", false),
    EMPTY("Zbiornik pusty", true),
    VALVE("Błąd zaworu (przerwa lub zwarcie)", true),
    SUPPLY("Za niskie napięcie zasilania płytki", false),
}

/**
 * Alarmy z opóźnieniem (żeby chwilowe skoki po zmianie prędkości nie piszczały):
 * dawka i ciśnienie po 4 s poza normą, reszta od razu. [step] zwraca nowo włączone alarmy.
 */
class SprayerAlarms(private val delayMs: Int = 4000) {
    private val since = HashMap<SprayerAlarm, Int>()
    var active: Set<SprayerAlarm> = emptySet(); private set

    data class Input(
        val running: Boolean,
        val linkOk: Boolean,
        val status: SprayerStatus?,
        val regulating: Boolean,
        val rateOk: Boolean,
        val pressureOk: Boolean,
        val tankL: Double,
        val reserveL: Double,
    )

    fun step(dtMs: Int, i: Input): List<SprayerAlarm> {
        val raw = buildSet {
            if (i.running && !i.linkOk) add(SprayerAlarm.LINK)
            val s = i.status
            if (i.running && i.linkOk && s != null) {
                if (s.failsafe) add(SprayerAlarm.FAILSAFE)
                if (s.sectionFaults != 0 || s.faults and (SprayerStatus.FAULT_MAIN or SprayerStatus.FAULT_REG) != 0) add(SprayerAlarm.VALVE)
                if (s.supplyVolts in 0.1..10.5 || s.faults and SprayerStatus.FAULT_SUPPLY != 0) add(SprayerAlarm.SUPPLY)
            }
            if (i.running && i.regulating && !i.rateOk) add(SprayerAlarm.RATE)
            if (i.running && i.regulating && !i.pressureOk) add(SprayerAlarm.PRESSURE)
            if (i.running && i.tankL <= 0.0) add(SprayerAlarm.EMPTY)
            else if (i.tankL <= i.reserveL && i.reserveL > 0) add(SprayerAlarm.RESERVE)
        }
        since.keys.retainAll(raw)
        for (a in raw) since[a] = (since[a] ?: 0) + dtMs
        val now = raw.filter { a ->
            val delayed = a == SprayerAlarm.RATE || a == SprayerAlarm.PRESSURE
            !delayed || (since[a] ?: 0) >= delayMs
        }.toSet()
        val fresh = now - active
        active = now
        return fresh.sortedBy { it.ordinal }
    }
}
