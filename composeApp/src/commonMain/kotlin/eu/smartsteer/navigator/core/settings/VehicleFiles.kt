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

/** Folder danych WAR (ustawienia połączenia, pola i zlecenia z WAR, kolejka wysyłki). */
expect fun warFiles(): TextFiles

/**
 * Podfolder danych AgOpenGPS (np. „VehicleProfiles”, „ToolProfiles”, „Vehicles”, „Fields”).
 * Windows: Dokumenty\AgOpenGPS; Android: folder importu\AgOpenGPS. Null = brak.
 */
expect fun agoFiles(sub: String): TextFiles?

/** Folder AgOpenGPS do pokazania rolnikowi. */
expect fun agoLocation(): String
