package eu.smartsteer.navigator.core.settings

/** Katalog profili: Windows — Dokumenty użytkownika\SmartSteer\Pojazdy, Android — pamięć aplikacji. */
expect fun vehicleFiles(): TextFiles

/** Katalog pól: Windows — Dokumenty\SmartSteer\Pola, Android — pamięć aplikacji. */
expect fun fieldFiles(): TextFiles

/** Folder do importu (KML, ISOXML) — tu wrzuca się pliki z WAR i innych programów. */
expect fun importFiles(): TextFiles

/** Folder raportów (zabiegi dla WAR, GeoJSON). */
expect fun exportFiles(): TextFiles

/** Bieżąca data i godzina, np. „2026-11-10 14:05”. */
expect fun nowText(): String

/** Otwiera folder w Eksploratorze (Windows). Na Androidzie nic nie robi. */
expect fun openFolder(path: String): Boolean
