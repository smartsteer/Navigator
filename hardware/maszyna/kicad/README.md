# Płytka Maszyna — projekt KiCad 7

| Plik | Co to |
|---|---|
| `maszyna.py` | schemat jako kod (SKiDL) — źródło prawdy; `python3 maszyna.py` → `maszyna.net` + `piny.tsv` |
| `maszyna.net` | netlista KiCad (279 elementów) |
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
   (w DSN są już klasy: Moc20A 3 mm — VIN_RAW, V12, V12_ZAWORY, LAMPA_OUT; Moc2A 1 mm — sekcje, zawory, rezerwa; Zasilanie 0,6 mm.
   GPS_RF jest poprowadzony ręcznie 0,36 mm i zablokowany. Pary USB_DP/USB_DM i CANH/CANL najlepiej poprowadzić ręcznie —
   w GUI Freeroutingu można je odznaczyć przed routingiem.)
3. KiCad → otwórz `maszyna.kicad_pcb` → Plik → Import → Specctra Session → `maszyna.ses`.
4. Dodaj wylewki masy na F.Cu i B.Cu (B: wypełnij strefy), sprawdź DRC, popraw ścieżki mocy (polygony 20–30 A).
5. Plik → Fabrication Outputs → Gerbers + Drill; BOM/CPL do JLCPCB.

## Przegląd zewnętrzny 2026-11-12 — co zmieniono, co nie

Wprowadzone:
- GPS (L76K): zasilanie VCC/V_BCKP przez koralik ferrytowy 600 Ω + 10 µF + 100 nF (sieć V3V3_GPS).
- ADS1115: zasilanie przez filtr RC 10 Ω + 1 µF (V3V3_ADC).
- Pomiar prądu lamp: R_IS 2,2 kΩ (20 A → ~2,0 V), do ADC przez 10 kΩ + 100 nF (ochrona przy sygnale błędu na IS).
- Nazwy sieci pompy ładunkowej DRV8962: DRVk_VCP / DRVk_CPL / DRVk_CPH (wcześniej mylące Uk_…).

Nie zmieniono (uzasadnienie):
- OCPM do DVDD: wg karty TI stan wysoki = **automatyczny restart** po przeciążeniu (stan niski = zatrzaśnięcie) — zostawione;
  firmware i tak czyta nFAULT przez 74HC165 i może dać impuls nSLEEP.
- GPS_BIAS: zasilanie anteny aktywnej idzie z pinu **VDD_RF** modułu (wyjście = VCC) przez 10 Ω i 47 nH do RF_IN —
  połączenie z V3V3 nie jest potrzebne (do potwierdzenia w L76K Hardware Design).
- Dławik CMC na CAN — opcjonalny; do decyzji przy długiej wiązce (pinout dławika trzeba wtedy sprawdzić z kartą).
- Footprint J2 „P0.4mm” to nazwa z biblioteki KiCad; pady faktycznie co 4,0 mm.

## Wytyczne do prowadzenia ścieżek (z przeglądu)

- VIN_RAW (J2 1–3 → Q1, D1) i V12 do U8/mostków: wylewki 5–8 mm na F.Cu i B.Cu, przelotki co 3–4 mm; LAMPA_OUT (U8 piny 5–7 → J2 7–8): wylewka ≥ 4–5 mm.
- Wyjścia mostków: 3 piny OUTx połączone szerokim polem przy układzie, dalej 1,2–1,5 mm; diody TVS przy pinach złączy.
- Pod padem każdego DRV8962: 18–24 przelotki termiczne (0,3/0,6 mm) do masy i odkryta maska od spodu; pod LMR33610 6–9 przelotek.
- Kondensatory 10 nF / 10 µF przy pinach VM–PGND, 100 nF CPH–CPL i 1 µF VCP–VM tuż przy pinach 1, 43, 44.
- Przetwornica: zwarta pętla Cin → VIN → SW → L1 → Cout → GND; BUCK_FB z dala od L1 i węzła SW.
- GPS_RF (U10 pin 11 → J7): 50 Ω, ok. 0,35–0,38 mm przy prepregu 0,2 mm nad masą (sprawdzić w kalkulatorze JLCPCB dla wybranego stosu), bez przelotek, płot z przelotek GND.
- USB_DP/DM para 90 Ω, CANH/CANL para 120 Ω. Antena ESP32 poza miedzią wszystkich warstw (strefa zakazu jest w projekcie).
- Masa jedna, ciągła (In1) — bez cięcia; podział stref fizyczny: moc (dół/środek, złącza), cyfrowe/RF (prawa strona).

## Sprzętowy PWM (przegląd 2026-11-12)

- ~~Mostek 16 z PWM~~ — wycofane: silnik może brać do 15 A, a DRV8962 daje 2 A / 5 A szczytowo. Zamiast tego osobny
  **mostek silnika 15 A**: DRV8701E + 4 × BUK7J1R4‑40H, EN = PWM z IO14 (impulsy IO14 przy starcie nieszkodliwe: nSLEEP = 0 i EN ściągnięte),
  PH = IO15, nFAULT = IO34, prąd przez bocznik 3 mΩ → SO → AIN3, wyjście końcówki M4 → Deutsch DTP 2‑pin.
- Zasilanie główne przez Deutsch DTP 2‑pin → końcówki M4 (X1/X2), idealna dioda na dwóch MOSFET‑ach równolegle.
- Klucz lamp U8 (IO25) obsługuje PWM 200 Hz–1 kHz bez zmian na płytce (silnik jednokierunkowy dużej mocy, pomiar prądu AIN2).
- Uwagi z przeglądu nie wprowadzone: IO27 i IO33 nie są wolne (dioda czerwona, CAN RX); F1 i zworki nie nachodzą na inne
  elementy — `crtyd.py` liczy przecięcia courtyardów: 0 (F1 ma 0,7 mm odstępu od U20/U22, zworki są co 8 mm).

## Przegląd 2026-11-12 (mostek silnika) — odpowiedzi

- F1 nie nachodzi na U20: pady F1 kończą się na y = 55,0 mm, pady U20 zaczynają na 56,5 mm (courtyardy bez przecięcia — `crtyd.py`).
- Oznaczenia: Q1, Q2 = idealna dioda; Q3, Q4 = MMBT3904 (auto‑reset); Q5–Q8 = mostek silnika. Aktualna lista z płytki: `../bom_jlcpcb.csv`
  (generowana z netlisty — `bom.csv` ma opisy i MPN, oznaczenia tam orientacyjne).
- DRV8701E: footprint VQFN‑24 4 × 4 mm, pad termiczny 2,45 × 2,45 mm z przelotkami (wg przeglądu; potwierdzić z rysunkiem TI RGE).
- IO15 (MTDO) jako kierunek silnika: stan przy starcie wycisza tylko logi ROM, nie blokuje startu.
- Obudowa: okna pod gniazda panelowe DTP04‑2P prostokątne (20,5 × 24,5 mm, parametr `dtp_okno`) — dopasować do wariantu (np. ‑L012) z rysunku TE.
