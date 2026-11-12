# Płytka Maszyna — projekt KiCad 7

| Plik | Co to |
|---|---|
| `maszyna.py` | schemat jako kod (SKiDL) — źródło prawdy; `python3 maszyna.py` → `maszyna.net` + `piny.tsv` |
| `maszyna.net` | netlista KiCad (274 elementy) |
| `maszyna.kicad_pro` / `.kicad_pcb` | projekt i płytka: 165 × 120 mm, 4 warstwy (F.Cu sygnały, In1 masa, In2 +12 V, B.Cu sygnały), elementy **rozmieszczone, bez ścieżek** |
| `place.py` / `netclass.py` | skrypty rozmieszczenia i klas połączeń (Moc20A 3 mm, Moc2A 1 mm, Zasilanie 0,6 mm) |
| `review.py` → `../przeglad_schematu.pdf` | raport połączeń do przeglądu (pin → sieć → z czym połączony) |
| `smartsteer.pretty` | footprint AMPSEAL 35 (wyprowadzony z 23‑pin — sprawdzić z rysunkiem TE 776231) |

## Stan (2026-11-12)

- Schemat (netlista) kompletny, ERC bez błędów poza znanym (V3 CH340C na 3,3 V — zgodnie z kartą).
- Rozmieszczenie automatyczne w strefach: kostki AMPSEAL przy przedniej krawędzi, 8 × DRV8962 w rzędzie pod nimi, rejestry,
  zasilanie i lampy na dole, ESP32 z anteną przy prawej krawędzi (strefa zakazu miedzi), GPS, USB‑C, CAN, ADC po prawej.
- **Ścieżek brak** — automatyczny router (Freerouting) nie mógł zostać uruchomiony w środowisku, w którym powstał projekt.
  Do zrobienia: ścieżki (ręcznie albo Freerouting: Plik → Eksport → Specctra DSN, potem import SES),
  polygony 20–30 A na F.Cu/B.Cu dla VIN_RAW → Q1 → V12 i U8 → LAMPA_OUT → J2 7–8 (miedź 2 oz),
  wylanie masy na F.Cu/B.Cu, DRC, potem pliki do JLCPCB (Gerber, wiercenia, BOM, CPL).

## Ścieżki automatem (Freerouting) — u siebie, ok. 10 minut

`maszyna.dsn` jest gotowy (wyeksportowany bez wylewek masy na F.Cu/B.Cu — warstwy wewnętrzne to płaszczyzny GND i V12).

1. Zainstaluj Javę 21 i pobierz Freerouting z https://github.com/freerouting/freerouting/releases (plik `freerouting-*.jar`).
2. `java -jar freerouting.jar -de maszyna.dsn -do maszyna.ses -mp 25`
3. KiCad → otwórz `maszyna.kicad_pcb` → Plik → Import → Specctra Session → `maszyna.ses`.
4. Dodaj wylewki masy na F.Cu i B.Cu (B: wypełnij strefy), sprawdź DRC, popraw ścieżki mocy (polygony 20–30 A).
5. Plik → Fabrication Outputs → Gerbers + Drill; BOM/CPL do JLCPCB.
