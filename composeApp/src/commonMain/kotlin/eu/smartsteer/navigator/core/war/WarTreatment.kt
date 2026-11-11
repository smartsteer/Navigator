package eu.smartsteer.navigator.core.war

/**
 * Co zrobiono na polu — odpowiedź na pytanie przy zamknięciu pola (tylko WAR Navigator). Idzie w paczce pracy jako
 * `zabieg` (docs/WAR.md 3.5); WAR wypełnia nią wpis do ewidencji zabiegów albo karty pola.
 * [kind] jak rodzaje w WAR: oprysk, nawoz, dolistny, siew, uprawa, inne.
 */
data class WarTreatment(
    val kind: String,
    val product: String = "",
    val permit: String = "",
    val dose: Double? = null,
    val unit: String = "",
    val crop: String = "",
    val variableRate: Boolean = false,
) {
    fun toJson(): String = Json.obj(
        "rodzaj" to kind, "produkt" to product.trim().ifEmpty { null }, "nr" to permit.ifEmpty { null },
        "dawka" to dose, "jedn" to unit.ifEmpty { null }, "uprawa" to crop.ifEmpty { null },
        "zmienna_dawka" to if (variableRate) true else null,
    )

    companion object {
        /** Rodzaje do wyboru: klucz WAR → nazwa na przycisku. */
        val KINDS = listOf("oprysk" to "Oprysk", "nawoz" to "Nawożenie", "dolistny" to "Dolistne", "siew" to "Siew", "uprawa" to "Uprawa", "inne" to "Inne")

        /** Jednostki dawki dla rodzaju (pierwsza = domyślna). */
        fun units(kind: String): List<String> = when (kind) {
            "oprysk", "dolistny" -> listOf("l/ha", "kg/ha", "ml/ha", "g/ha")
            "nawoz" -> listOf("kg/ha", "t/ha", "l/ha", "m³/ha")
            "siew" -> listOf("kg/ha", "szt./m²", "j.s./ha")
            else -> emptyList()
        }
    }
}

/** Produkt z WAR (paczka `produkty_war`): nazwa, nr zezwolenia (środki), ostatnia dawka, jednostka, uprawa (odmiany). */
data class WarProduct(val name: String, val permit: String = "", val dose: Double? = null, val unit: String = "", val crop: String = "", val kind: String = "")

/** Listy produktów gospodarstwa z WAR — najpierw ostatnio używane. */
data class WarProducts(val sprays: List<WarProduct> = emptyList(), val fertilizers: List<WarProduct> = emptyList(), val varieties: List<WarProduct> = emptyList()) {
    /** Lista do podpowiedzi dla rodzaju zabiegu. */
    fun forKind(kind: String): List<WarProduct> = when (kind) {
        "oprysk" -> sprays.filter { it.kind != "dolistny" } + sprays.filter { it.kind == "dolistny" }
        "dolistny" -> sprays.filter { it.kind == "dolistny" } + fertilizers.filter { it.kind == "dolistny" } + sprays.filter { it.kind != "dolistny" }
        "nawoz" -> fertilizers
        "siew" -> varieties
        else -> emptyList()
    }

    companion object {
        fun parse(m: Map<*, *>): WarProducts {
            fun list(key: String) = (m[key] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.mapNotNull { x ->
                val n = (x["n"] as? String)?.trim().orEmpty().ifEmpty { return@mapNotNull null }
                WarProduct(
                    n, (x["nr"] as? String).orEmpty(), (x["d"] as? Number)?.toDouble(), (x["j"] as? String).orEmpty(),
                    (x["u"] as? String).orEmpty(), (x["r"] as? String).orEmpty(),
                )
            }.take(300)
            return WarProducts(list("srodki"), list("nawozy"), list("odmiany"))
        }
    }
}
