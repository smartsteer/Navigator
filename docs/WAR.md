# Wymiana danych z WAR (Wirtualny Asystent Rolnika)

Na razie przez pliki — działa bez internetu i bez logowania. Połączenie przez API WAR dojdzie, gdy będzie opis interfejsu.

## Z WAR do Navigatora: pola, granice, linie

Folder importu: `Dokumenty\SmartSteer\Import` (ekran Pola → „Otwórz folder importu”).

- **KML** (`*.kml`): pierwszy wielokąt = granica pola, linia z 2 punktów = linia AB, dłuższa linia = krzywa.
  Nazwa pola = nazwa dokumentu KML (albo nazwa granicy, albo nazwa pliku).
- **ISOXML** (`TASKDATA.XML` albo cały folder `TASKDATA`): każde pole `PFD` → granica z `PLN` typu 1,
  linie prowadzenia z `LSG` typu 5 (2 punkty = AB, więcej = krzywa). Nazwa pola z atrybutu `C`.

Ekran Pola → lista plików → „Wczytaj”. Pole trafia na listę zapisanych pól (przy tej samej nazwie dostaje „(2)”).

## Z Navigatora do WAR: raport zabiegu

Folder raportów: `Dokumenty\SmartSteer\Raporty`. Raport zapisuje się sam przy zamykaniu pola (gdy coś obrobiono)
albo przyciskiem „Zapisz raport teraz”. Plik: `<pole> <rrrr-mm-dd ggmm>.geojson`.

GeoJSON (`FeatureCollection`, współrzędne WGS84 `[długość, szerokość]`):
- `properties`: `program`, `pole`, `start`, `koniec`, `obrobiono_ha` (bez liczenia zakładek), `powierzchnia_pola_ha`,
  `profil` (profil pojazdu), `szerokosc_m`, `sekcje`;
- `features`: `Polygon` z `"rodzaj": "granica"` i wielokąty `"rodzaj": "pokrycie"` (obrobione pasy sekcji).
