# Panel oprysku — płytka KiCad 7 (etap 22b)

Płytka pod obudowę z `../obudowa` („włóż, skręć, działa”): dźwignie, diody, czujniki rollera i czujnik światła
wlutowane w płytkę na pozycjach z tabeli w `../obudowa/README.md`. Licencja GPLv3.

| Plik | Co to |
|---|---|
| `panel.py` | schemat jako kod (SKiDL) — źródło prawdy; `/usr/bin/python3 panel.py` → `panel.net` |
| `place.py` | obrys płytki (wnętrze obudowy − 4 mm, wycięcia na słupki śrub pokrywy, START/STOP i włącznik), rozmieszczenie |
| `netclass.py` | klasy połączeń (sygnały 0,25 mm, zasilanie 0,5 mm) |
| `smartsteer_panel.pretty` | footprint dźwigni (nóżki PC co 4,70 mm — **sprawdzić z kartą wybranej dźwigni**) |
| `mpn.csv` | wartość → MPN (+ LCSC, gdzie znany) do BOM JLCPCB |
| `../produkcja` | Gerber, BOM, CPL dla JLCPCB (`../../narzedzia/fab_jlcpcb.py`) |

Kolejność (opis narzędzi: `../../narzedzia/README.md`): `panel.py` → kinet2pcb (`panel.net` → `panel.kicad_pcb`) → `netclass.py`
→ `place.py` → `przelotki_ep.py panel.kicad_pcb U7 U8` → `tie_pins.py` → `do_padu_ep.py panel.kicad_pcb U7 U8`
→ `plaszczyzny.py panel.kicad_pcb In1.Cu:GND In2.Cu:V3V3` → DSN bez płaszczyzn i sieci GND/V3V3 (sygnały tylko F/B) → Freerouting 1.9
→ `ses2pcb.py` → `dotrasuj.py --napraw --wewn In2.Cu --plaszczyzny In1.Cu,In2.Cu` → `sprzatnij.py` → `drc.py` → `fab_jlcpcb.py`.

Układ współrzędnych: X jak w obudowie, Y = 140 − y (operator na dole rysunku). Płytka **4 warstwy** (In1 masa, In2 3,3 V),
1,6 mm, miedź 1 oz, elementy SMD od góry (od płyty czołowej) — LED‑y 5 mm i dźwignie przewlekane.

## Zmiany względem specyfikacji 22a

- **ESP32‑S3‑MINI‑1** zamiast ESP32‑C3‑MINI‑1 — symbol i footprint są w bibliotece KiCad (pewny pinout), więcej pinów
  (bez dodatkowego ekspandera dla rollera, e‑papieru i pomiarów). BLE 5 jak w C3; firmware 22d pod S3.
- **4 × TCA9534** — dźwignie sekcji są chwilowe (ON)-OFF-(ON), czyli 2 styki na sekcję: 26 wejść + AUTO + ŚWIATŁO
  (+ 4 zapasowe). Adresy 0x20–0x23, wspólne przerwanie (`EXP_nINT` → IO1, budzenie z uśpienia).
- **Roller:** jeden **TMAG5111C2** (dwa zatrzaski Halla Z/Y w jednym układzie → kroki i kierunek) pod środkiem kieszeni
  (240, 62) zamiast 2 osobnych czujników; zasilany z pinu IO7 tylko przy pracy. Wciśnięcie: **SM351LT** (nanopower, zawsze zasilany) w (240, 71).
- **E‑papier 4,2"** jako gotowy moduł SPI (np. Waveshare 4.2" z płytką) na złączu JST‑PH 8 pin, zasilanie przez klucz
  BSS84 (IO40) — w uśpieniu moduł bez prądu.
- **START/STOP Ø22** i **włącznik kołyskowy** są za głębokie, żeby lutować je w płytkę: pod nimi wycięcia w płytce,
  podłączenie wtyczką JST‑XH (START 6 pin: masa, styk, anoda pierścienia, R, G, B; włącznik 2 pin). Bateria: JST‑XH 2 pin.
- Rezystor identyfikacyjny: IO41 z 100 k do masy = panel oprysku (panel hydrauliki: bez rezystora).
- **4 warstwy** (2026-11-24): masa i 3,3 V jako płaszczyzny — na 2 warstwach zostawały odcięte wyspy masy.
- Programowanie: pady 1×8 (3V3, GND, EN, IO0, TX, RX, USB D−, D+) — nie montowane; dalej aktualizacje przez BLE.

## Przydział pinów ESP32‑S3

| GPIO | Funkcja | GPIO | Funkcja |
|---|---|---|---|
| 1 | przerwanie ekspanderów (budzenie) | 13 / 14 | I²C SDA / SCL (ekspandery, sterowniki diod) |
| 2 | START/STOP (budzenie) | 15 | SDB sterowników diod (1 M do masy = wyłączone) |
| 4 | wciśnięcie rollera (budzenie) | 16 / 17 / 18 | e‑papier SCK / MOSI / CS |
| 5 / 6 | roller A / B (TMAG5111) | 21 / 38 / 39 | e‑papier DC / RST / BUSY |
| 7 | zasilanie TMAG5111 | 40 | klucz zasilania e‑papieru (0 = wł.) |
| 8 / 9 | czujnik światła: zasilanie / ADC | 41 | identyfikacja panelu |
| 10 / 11 | bateria: ADC / włącz dzielnik | 12 | ~SLEEP przetwornicy (0 = tryb oszczędny) |

Diody: 2 × IS31FL3236A (adres AD = GND / 3V3), OUT1–36 pierwszego: diody sekcji 1–12 (R, G, B kolejno), drugiego:
sekcja 13, dioda stanu, pierścień START (R, G, B). Wspólna anoda diod = 3,3 V.

## Do sprawdzenia przez elektronika (przed zamówieniem)

1. Dźwignie: rozstaw nóżek PC (przyjęto 4,70 mm w osi Y, jak C&K 7000), średnica otworów 1,2 mm, długość korpusu (płytka 19 mm pod wierzchem płyty).
2. Diody RGB 5 mm wspólna anoda: kolejność nóżek R‑A‑G‑B (footprint `LED_D5.0mm-4_RGB`); niebieska i zielona przy anodzie 3,3 V
   (zapas napięcia na wyjściu IS31FL3236A) — jeśli za ciemno, anoda z osobnego 5 V.
3. R_EXT IS31FL3236A = 10 k (prąd maks. kanału) — dobrać wg karty do jasności pod soczewką.
4. Pierścień START/STOP: wersja z diodą na 3 V bez wbudowanego rezystora 12 V.
5. TMAG5111C2 (osie Z/Y, 1,4 mT) a 8 magnesów Ø3 × 3 w kole Ø22 — odległość ok. 3–4 mm; ewentualnie wersja C4 (czulsza).
6. SMAJ12A: pin 1 = katoda (+), sprawdzić z symbolem; TPS62177 L = 2,2 µH (karta: 2,2 µH).
7. Pobór w uśpieniu (cel ~15 µA): TPS62177 w trybie SLEEP, ESP32‑S3 deep sleep, IS31 wyłączone SDB, ekspandery.
