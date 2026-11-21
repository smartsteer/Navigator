# Obudowy paneli — druk 3D (etap 22c, panel hydrauliki: część etapu 23c)

Pliki źródłowe OpenSCAD (parametryczne) i gotowe STL ułożone do druku. Licencja GPLv3.
Podgląd: [panel oprysku](podglad.png), [panel hydrauliki](../../hydraulika/obudowa/podglad.png).

| Plik | Co to |
|---|---|
| `panel_lib.scad` | wspólne bryły: spód z komorą baterii i otworami AMPS, płyta czołowa, otwory, uchwyt z magnesami, grzebienie osłon |
| `panel_oprysk.scad` | panel oprysku (wersja 2) |
| `../../hydraulika/obudowa/panel_hydraulika.scad` | panel hydrauliki |

Eksport jednej części: `openscad -D 'czesc="spod"' -o oprysk_spod.stl panel_oprysk.scad`.

## Części do wydruku

| STL | Ile | Materiał | Uwagi |
|---|---|---|---|
| `oprysk_pokrywa.stl` / `hydraulika_pokrywa.stl` | 1 | ASA (PETG) | płyta czołowa, drukować **wierzchem w dół** (bez podpór); napisy wytłoczone 0,6 mm — wypełnić farbą |
| `oprysk_spod.stl` / `hydraulika_spod.stl` | 1 | ASA | dnem na stole; ścianki 4 mm, 5 obrysów albo 30 % wypełnienia |
| `oprysk_klapka.stl` | 1 na panel | ASA | klapka komory baterii (ta sama dla obu paneli) |
| `oprysk_garb.stl` | 1 | ASA albo TPU 95A | podparcie dłoni, wklejane na 2 kołkach Ø3 (epoksyd) |
| `oprysk_rant.stl` | 1 | ASA | osłony między dźwigniami sekcji, grzbiet wklejany w rowek |
| `hydraulika_oslona.stl` | **2** | ASA | osłony dźwigni (jedna na rząd) |
| `oprysk_roller_kolo.stl` | 1 | ASA / PETG | koło rollera, 2 o‑ringi 20 × 2,5 jako bieżnik, 8 magnesów Ø3 × 3 |
| `oprysk_roller_widelki.stl` | 1 | **TPU 95A** | widełki rollera (sprężynują przy wciśnięciu) |
| `oprysk_uchwyt_magnes.stl` | 1 na panel | ASA / PETG | płytka z 4 magnesami; zamiast niej: kula RAM 1" na podstawie AMPS |

## Do kupienia (poza drukiem)

- Tuleje wtapiane: M3 (śruby pokrywy: 10 oprysk / 8 hydraulika; słupki PCB: 6 / 5), M4 × 6 (klapka 2, AMPS 4).
- Śruby M3 × 8 z łbem stożkowym (pokrywa), M3 × 6 (PCB), **2 × śruba radełkowana M4** (klapka — kręcone palcami, bez narzędzi).
- Sznur uszczelki Ø2 mm EPDM / silikon (obwód ok. 0,8 m + komora baterii 0,35 m).
- Koszyk 6 × AA płaski z przewodem i wtyczką (ok. 92 × 58 × 16 mm — **zmierzyć**, komora ma 96 × 62 × 18).
- Szybka PC / akryl 96 × 75 × 2 mm nad e‑papierem (klej‑uszczelniacz MS polimer).
- 4 × magnes w kubku Ø25 z trzpieniem M4 + nakrętki (uchwyt magnesowy) albo uchwyt RAM z podstawą AMPS (30 × 38 mm, M4).
- Roller: kołek stalowy Ø3 × 16, 2 o‑ringi 20 × 2,5, 8 magnesów N52 Ø3 × 3, 1 magnes Ø4 × 1.

## Położenie elementów na płycie (wymiary dla płytki PCB, etap 22b)

Układ: x od lewej krawędzi, y od krawędzi przy operatorze, w mm. **Wierzch PCB 19 mm pod wierzchem płyty**
(płyta 3 + 16 mm na korpus dźwigni — dobrać do wybranej dźwigni, parametr `pcb_odst`). Śruby PCB: M3 w słupkach.

**Panel oprysku** (obrys 300 × 140, wys. 36 z przodu / ok. 58 z tyłu + garb 13):

| Element | Położenie |
|---|---|
| dźwignie sekcji 1–13 (tuleja 1/4", otwór 6,6) | x = 22 + 17·k (k = 0…12), y = 30 |
| diody sekcji (5 mm) | x jak dźwignie, y = 48 |
| okno e‑papieru 86 × 65 | środek (62, 94) |
| dźwignia AUTO / ŚWIATŁO | (150, 100) / (175, 100) |
| włącznik kołyskowy 19,2 × 12,9 | środek (205, 100) |
| dioda stanu / czujnik światła (Ø3,2) | (226, 122) / (212, 122) |
| START/STOP Ø22 | (262, 108) |
| kieszeń rollera 16 × 36 (dno zamknięte) | środek (240, 62); pod dnem czujniki Halla: 2 × zatrzask (kroki i kierunek) przy (240, 59,5) i (240, 64,5) — rozstaw dobrać do 8 magnesów; 1 × przełącznik (wciśnięcie) przy (240, 71) |
| słupki PCB | (20, 20), (149,5, 20), (272, 24), (20, 57), (112, 120), (272, 116) |
| słupki śrub pokrywy (PCB je omija, Ø9 + 1 mm) | (40, 7,5), (120, 7,5), (200, 7,5), (258, 7,5), (120, 132,5), (200, 132,5), (258, 132,5), (7,5, 40), (7,5, 110), (292,5, 70) |
| wnętrze (PCB mieści się w) | obrys − 4 mm; narożniki prawe R36, lewe R14 |

**Panel hydrauliki** (obrys 280 × 150, R22, wys. 36 / ok. 57):

| Element | Położenie |
|---|---|
| 12 dźwigni (ON‑OFF‑ON chwilowe, tuleja 1/4") | x = 30 + 38·k (k = 0…5), y = 42 (rząd przedni) i 100 (tylny) |
| dioda przy dźwigni | (x + 14, y + 14) |
| wgłębienie na pasek z opisem 34 × 8 | środek (x, y + 26) |
| HYDR. AKTYWNA Ø22 / STOP Ø22 | (255, 108) / (255, 68) |
| włącznik kołyskowy | (255, 28) |
| dioda stanu | (255, 140) |
| słupki PCB | (18, 18), (262, 18), (18, 132), (262, 132), (125, 71) |
| słupki śrub pokrywy | (60, 7,5), (140, 7,5), (220, 7,5), (60, 142,5), (140, 142,5), (220, 142,5), (7,5, 75), (272,5, 88) |

## Zmiana względem specyfikacji: czujnik rollera

AS5600 wymaga magnesu na osi obrotu naprzeciw czujnika — przy płaskiej płytce pod rollerem (oś pozioma) to się nie składa.
W obudowie przyjęto: **8 magnesów na obwodzie koła (bieguny na przemian) + 2 czujniki Halla typu zatrzask** (np. DRV5011)
pod zamkniętym dnem kieszeni — kroki i kierunek jak w enkoderze, nadal **bez otworu w obudowie**; wciśnięcie: widełki z TPU
uginają się ~1 mm, magnes pod nimi zbliża się do **przełącznika Halla** (np. DRV5032). Do potwierdzenia przy 22b (odległości, progi).

## Do sprawdzenia przed drukiem

1. Długość tulei i korpusu wybranych dźwigni IP67 → `pcb_odst`; średnica otworu i wypust podkładki ustalającej.
2. Wymiary kupionego koszyka 6 × AA, włącznika kołyskowego i przycisków Ø22 (głębokość pod płytą ≤ ok. 35 mm z przodu).
3. Okno e‑papieru względem pola aktywnego wybranego modułu 4,2" (przesunąć `ekran_c`).
4. Szczelność: docisk uszczelki śrubami co ok. 80 mm — jeśli przecieka, dodać śruby w `sruby`.
5. Roller i widełki — wydrukować najpierw same (małe części) i sprawdzić w ręku.
6. Wersja dla leworęcznych — jeszcze nie (wymaga lustrzanej płytki PCB).
