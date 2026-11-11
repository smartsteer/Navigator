package eu.smartsteer.navigator.core.field

/**
 * Sekwencja na uwrociu (jak „headland management” w drogich systemach): kroki przy wjeździe na uwrocie i przy
 * wyjeździe w pole, każdy w zadanej odległości maszyny od linii uwrocia. Podnoszenie / opuszczanie idzie do modułu
 * maszyny (PGN 239), reszta to polecenia dla kierowcy na ekranie i głosem (WOM, obroty, blokada mostu…).
 */
object HeadlandSequence {
    enum class Kind(val label: String) { MESSAGE("Komunikat"), RAISE("Podnieś"), LOWER("Opuść") }

    data class Step(val entering: Boolean, val kind: Kind, val distM: Double, val text: String = "")

    fun parse(s: String): List<Step> = s.split(';').mapNotNull { e ->
        val f = e.split('|')
        if (f.size < 3) return@mapNotNull null
        val kind = Kind.entries.getOrNull(f[1].toIntOrNull() ?: return@mapNotNull null) ?: return@mapNotNull null
        Step(f[0] == "I", kind, f[2].toDoubleOrNull() ?: 0.0, f.getOrNull(3).orEmpty())
    }

    fun format(steps: List<Step>): String = steps.joinToString(";") { st ->
        "${if (st.entering) "I" else "O"}|${st.kind.ordinal}|${st.distM}|${st.text.replace(";", ",").replace("|", "/")}"
    }

    /** Domyślny przykład: przed uwrociem — WOM, na linii — podnieś; po wjeździe w pole — opuść, WOM. */
    val EXAMPLE = format(
        listOf(
            Step(true, Kind.MESSAGE, 5.0, "Wyłącz WOM"),
            Step(true, Kind.RAISE, 0.0),
            Step(false, Kind.LOWER, 0.0),
            Step(false, Kind.MESSAGE, 0.0, "Włącz WOM"),
        ),
    )
}
