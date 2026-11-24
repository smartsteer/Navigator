# Płytka Maszyna Lite — projekt KiCad 7 (etap 16Lb)

Schemat jako kod i płytka **4‑warstwowa** 170 × 105 mm według `../README.md` (In1 masa, In2 +12 V wyjść). Licencja GPLv3.

| Plik | Co to |
|---|---|
| `lite.py` | schemat (SKiDL) — źródło prawdy; `/usr/bin/python3 lite.py` → `lite.net` |
| `place.py` | obrys i rozmieszczenie (listwy W1–W8 u góry, W9–W15 + LAMPY u dołu, zasilanie/czujniki/CAN/USB z lewej, ESP32 anteną w prawo) |
| `netclass.py` | klasy połączeń: Moc 1,0 mm (V12, wejście), Wyjscia 0,6 mm (A/B zaworów, boczniki), Zasilanie 0,4 mm, sygnały 0,25 mm |
| `smartsteer_lite.pretty` | ESP32‑WROOM‑32D z przelotkami pod padem 0,3 mm (z czasów wersji 2‑warstwowej; zostaje) |
| `mpn.csv` | wartość → MPN (+ LCSC, gdzie znany) do BOM JLCPCB |
| `../produkcja` | Gerber, BOM, CPL dla JLCPCB |

Kolejność (opis narzędzi: `../../narzedzia/README.md`): `lite.py` → kinet2pcb → `netclass.py` → `place.py` →
`plaszczyzny.py lite.kicad_pcb In1.Cu:GND In2.Cu:V12_WYJSCIA` → DSN bez płaszczyzn i bez sieci GND, V12_WYJSCIA, sygnały tylko F/B
→ Freerouting 1.9 → `ses2pcb.py` → `dotrasuj.py --napraw --wewn In2.Cu --plaszczyzny In1.Cu,In2.Cu` (pady masy i +12 V
przelotką do płaszczyzn, resztki) → `strefy_mocy.py lite.kicad_pcb VIN_RAW:4,3,28,35 V12:14,35,44,58` → `drc.py` → `fab_jlcpcb.py`.

Masa i +12 V wyjść nie są prowadzone ścieżkami — każdy pad ma przelotkę do płaszczyzny.

## Zmiany względem specyfikacji 16La

- Przetwornica 12→5 V **LMR33610** (jak pełna płytka, wejście do 36 V) zamiast TPS54202 (28 V) — punkt 4 listy do sprawdzenia.
- Ochrona bramki P‑MOSFET wejścia diodą Zenera 15 V (BZT52C15) — przy zrzucie obciążenia Vgs nie przekroczy 20 V.
- Zasilanie: 2 × 100 µF 35 V (elektrolit SMD) + 10 µF przy każdym mostku.
- **4 warstwy zamiast 2** (2026-11-24): na 2 warstwach masa nie docierała do kilkunastu padów (wyspy wylewki zamknięte ścieżkami).
- Kanały multipleksera: SEKCJA11 → kanał 15, kanał 10 wolny (`MUX_CH` w `lite.py`) — tabela dla firmware 16Lc.

## Do sprawdzenia przez elektronika

1. Lista z `../README.md` (AT8870/DRV8870, prąd 2 A, boczniki, zrzut obciążenia, ADC, listwy, antena, ESD).
2. +12 V wyjść idzie płaszczyzną In2 (1 oz wewnątrz) i przelotkami 0,6 mm do każdego VM — przy wielu zaworach 2‑pin naraz sprawdzić spadek napięcia.
3. Footprint listew 3,5 mm: Phoenix MC 1,5/x‑G‑3,5 (zamiennik Degson 15EDGRC‑3.5) — sprawdzić rozstaw nóżek kupionych gniazd.
4. USB‑C od krawędzi lewej — w skrzynce przez dławik albo zaślepkę.
