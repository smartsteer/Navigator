# Płytka Maszyna (opryskiwacz) — specyfikacja i schemat (etap 16a)

Uniwersalny sterownik opryskiwacza SmartSteer do Navigatora. Protokół: [docs/MASZYNA.md](../../docs/MASZYNA.md) (PGN 160/161).
Licencja: GPLv3 (sprzęt opisany otwarcie, jak cały projekt).

> **Stan:** specyfikacja, przydział pinów, wyprowadzenia złączy i lista części. To podstawa do narysowania schematu
> i płytki w KiCad (etap 16b). **Przed zamówieniem projekt musi przejrzeć elektronik** — lista „Do sprawdzenia” na końcu.
> Numery części producentów (MPN) podane są do sprawdzenia z kartami katalogowymi; numery LCSC uzupełnia się przy
> zamówieniu w JLCPCB (wyszukiwarka po MPN).

## Co umie

| Funkcja | Ile | Uwagi |
|---|---|---|
| Sekcje (zawory Arag 2‑pin lub 3‑pin) | **13** | każda jako pełny mostek **2 A ciągłe** (decyzja właściciela: wszystkie wyjścia zaworów 2 A, z zapasem) — zmiana biegunowości dla 2‑pin, sygnał dla 3‑pin; diagnostyka przerwy/zwarcia |
| Zawór główny | 1 | pełny mostek 2 A |
| Zawór regulacyjny (silnik) | 1 | pełny mostek 2 A, impulsy ms z Navigatora |
| Oświetlenie robocze | 1 | **20 A ciągłe**, klucz mocy z pomiarem prądu i zabezpieczeniem |
| Przepływomierz | 1 | wejście impulsowe 12 V (otwarty kolektor / push‑pull), zasilanie czujnika 12 V z bezpiecznikiem |
| Czujnik ciśnienia | 1 | 0,5–4,5 V (zasilanie 5 V) **albo** 4–20 mA — zworka |
| GPS (tylko prędkość) | 1 | moduł GNSS na płytce + gniazdo anteny SMA |
| Łączność z tabletem | — | **Bluetooth SPP** (płytka na opryskiwaczu, z ciągnika tylko zasilanie) **i** USB‑C (kabel, zapas, programowanie) |
| CAN | 1 | 250 kbit/s do płytki Ster (zestaw z autosterem), terminator 120 Ω na zworce |
| Rezerwa | 2 | dwa wejścia analogowe/cyfrowe pod zasuwy rozsiewacza (później) |

Zabezpieczenie: brak poleceń przez 0,5 s → zawór główny i sekcje zamknięte (programowo) — przy zaworach 3‑pin zamykają się też
same przy zaniku sygnału; przy 2‑pin trzeba je aktywnie przestawić, więc procesor ma watchdog, a sterowniki mostków
po resecie są wyłączone (zawór zostaje, ale główny 3‑pin — jeśli taki jest — zamyka się sam). **Zalecenie: zawór główny 3‑pin.**

## Schemat blokowy

```
 12 V z ciągnika (kostka B, 3 piny +, 3 piny −)
   │
   ├─ bezpiecznik główny 30 A (w wiązce) ─ ochrona: MOSFET‑P przeciw odwrotnej polaryzacji + TVS 26 V (zrzut obciążenia)
   │
   ├──────────────► BTS50015 (20 A) ──────────────► lampy robocze (kostka B, 2 piny)
   ├──────────────► 8 × DRV8962 (16 pełnych mostków po 2 A) ─► 13 sekcji + główny + regulacyjny + rezerwa
   ├─ PTC 0,5 A ──► zasilanie przepływomierza 12 V
   └─ przetwornica 12→5 V (LMR33610) ─┬─► czujnik ciśnienia 5 V, CH340C, moduł GPS
                                      └─ LDO 3,3 V (AP2114H) ─► ESP32, TJA1051, ADS1115

 ESP32‑WROOM‑32E ── SPI ──► 4 × 74HC595 (32 wyjścia: IN/EN mostków) + 1 × 74HC165 (8 × nFAULT)
                 ── I²C ──► ADS1115 (ciśnienie, napięcie zasilania, prąd lamp, rezerwa)
                 ── UART2 ─► GPS (Quectel L76K) ── u.FL ─► gniazdo SMA na obudowie
                 ── UART0 ─► CH340C ─► USB‑C (tablet / programowanie, auto‑reset)
                 ── TWAI ──► TJA1051T/3 ─► CAN (kostka B)
                 ── PCNT ◄── przepływomierz (filtr RC + przerzutnik Schmitta 74LVC1G17)
                 ── Bluetooth Classic SPP (wbudowany) ─► tablet
```

## Przydział pinów ESP32‑WROOM‑32E

| GPIO | Funkcja |
|---|---|
| 18 / 23 / 19 | SPI: SCK / MOSI (→ 74HC595) / MISO (← 74HC165) |
| 5 | RCLK 74HC595 (zatrzaśnięcie wyjść) |
| 4 | nOE 74HC595 — podciągnięty do 3,3 V: po resecie wyjścia rejestrów odcięte, wejścia mostków ściągnięte do masy = **wszystko wyłączone** |
| 2 | PL 74HC165 (odczyt nFAULT) |
| 34 | nFAULT zbiorczo (OR na drucie, szybki alarm) |
| 21 / 22 | I²C SDA / SCL (ADS1115) |
| 32 / 33 | CAN TX / RX (TWAI → TJA1051T/3) |
| 16 / 17 | UART2 RX / TX ← GPS |
| 1 / 3 | UART0 TX / RX ↔ CH340C (USB) |
| EN, 0 | auto‑reset i tryb programowania z CH340C (2 tranzystory, jak w DevKitC) |
| 35 | przepływomierz (licznik PCNT, wejście tylko) |
| 25 | IN klucza lamp BTS50015 |
| 26 / 27 | dioda LED zielona (praca/łączność) / czerwona (błąd) |
| 13 / 14 | rezerwa (rozsiewacz) — wyprowadzone na kostkę B przez zabezpieczenie |
| 39 | rezerwa wejście |
| 36 | zworka „CAN / tylko BT” (odczyt przy starcie) |

ADS1115: AIN0 ciśnienie, AIN1 napięcie zasilania (dzielnik 100k/15k), AIN2 prąd lamp (wyjście IS BTS50015), AIN3 rezerwa.

## Wyjścia zaworów — 16 pełnych mostków po 2 A (8 × DRV8962)

DRV8962 (TI): 4 niezależne półmostki 5 A szczytowo, ~50 mΩ na klucz — przy 2 A ciągłych ~0,2 W na mostek, czyli chłodno
i z dużym zapasem (wymaganie „ma być wieczne”). Każdy układ = 2 pełne mostki. Wbudowane: zabezpieczenie nadprądowe,
termiczne, podnapięciowe, sygnał nFAULT.

| Mostek | Układ | Funkcja |
|---|---|---|
| 1–13 | U1–U7 | sekcje 1–13 |
| 14 | U7 (2. mostek) | zawór główny |
| 15 | U8 (1. mostek) | zawór regulacyjny |
| 16 | U8 (2. mostek) | rezerwa (np. mieszadło / zasuwa rozsiewacza) |

Sterowanie: 32 sygnały IN/EN z 4 rejestrów 74HC595 (SPI), błędy z 74HC165. Wyjścia rejestrów po resecie odcięte,
rezystory 10 k do masy na wejściach mostków → przy starcie i przy zawieszeniu procesora (watchdog) **nic się nie włącza samo**.

Budżet prądu: wszystkie wyjścia 2 A to 32 A naraz — w praktyce zawory pracują przez 1–3 s przy przestawianiu,
więc firmware przestawia sekcje **kolejno, z odstępem ~50 ms** (szczyt ≤ 6–8 A), a bezpiecznik główny 30 A wystarcza
dla lamp 20 A + zaworów.

Zawór **2‑pin**: A/B = +12/0 (otwórz) albo 0/+12 (zamknij), zasilany przez ustawiony czas (domyślnie 3 s), potem oba wyłączone.
Zawór **3‑pin** (Arag: brązowy +12, niebieski masa, czarny sygnał): brązowy i niebieski ze wspólnych pinów zasilania kostki A,
czarny = wyjście A kanału (+12 otwarty, 0 zamknięty), wyjście B nieużywane.

## Złącza (dwie kostki — decyzja właściciela)

**Kostka A — sekcje: AMPSEAL 35‑pin** (gniazdo na płytkę, TE 776231‑1 lub odpowiednik — do potwierdzenia)

| Piny | Sygnał |
|---|---|
| 1–26 | sekcje 1–13: A, B (pin 2k−1 = A, 2k = B) |
| 27–30 | +12 V wspólne dla zaworów 3‑pin (za bezpiecznikiem 10 A na płytce) |
| 31–34 | masa wspólna zaworów |
| 35 | ekran / masa |

**Kostka B — reszta: AMPSEAL 23‑pin** (TE 770680‑1 lub odpowiednik — do potwierdzenia)

| Piny | Sygnał |
|---|---|
| 1–3 | **+12 V wejście** (3 piny równolegle, przewód 4 mm², bezpiecznik 30 A przy akumulatorze) |
| 4–6 | **masa wejście** (3 piny równolegle, 4 mm²) |
| 7–8 | **lampy robocze 20 A** (2 piny równolegle, przewód 2,5 mm²; masa lamp do ramy) |
| 9–10 | zawór główny A / B |
| 11–12 | zawór regulacyjny A / B |
| 13–15 | przepływomierz: +12 V / sygnał / masa |
| 16–18 | czujnik ciśnienia: +5 V / sygnał / masa |
| 19–20 | CAN‑H / CAN‑L |
| 21–22 | rezerwa (rozsiewacz) |
| 23 | masa sygnałowa |

Styki AMPSEAL ~17 A na pin — dlatego zasilanie i lampy po kilka pinów równolegle. **Do sprawdzenia:** prąd dopuszczalny styku
dla użytego przekroju przewodu (karta katalogowa TE); jeśli za mało — lampy na osobnym złączu Deutsch DTP (25 A).

Dodatkowo na obudowie: USB‑C (z zaślepką), gniazdo SMA anteny GPS, dioda LED widoczna przez okienko.

## Prąd 20 A na płytce

- Klucz: Infineon **BTS50015‑1TAD** (1,5 mΩ, ochrona przed zwarciem i przegrzaniem, wyjście IS do pomiaru prądu).
- Ścieżka +12 → klucz → piny 7–8: polygon na obu warstwach z przelotkami, **miedź 2 oz (70 µm)**, szerokość ≥ 8 mm
  (dla 20 A i przyrostu temperatury ~20 °C przy 2 oz), jak najkrótsza; tak samo wejście zasilania (do 30 A łącznie) — ≥ 12 mm.
- Straty klucza ~0,6 W przy 20 A — pole miedzi pod obudową TO‑263 jako radiator.

## Trwałość („ma być wieczne”)

- Części w wersjach samochodowych / przemysłowych (−40…+105 °C), kondensatory ceramiczne X7R zamiast elektrolitów
  tam, gdzie się da; elektrolity tylko 105 °C, 5000 h+.
- Każde wyjście 2 A pracuje z ≥ 2× zapasem prądu i bez radiatora; lampy 20 A na kluczu 1,5 mΩ.
- Płytka lakierowana (lakier ochronny / conformal coating — opcja w JLCPCB), obudowa szczelna IP67, odpowietrznik membranowy.
- Wszystkie piny kostek z diodami TVS, wejścia z filtrami RC, ochrona przed odwrotną polaryzacją i zrzutem obciążenia.
- Złącza AMPSEAL (IP67, samochodowe), styki cynowane / złocone wg wiązki.

## Lista części (BOM) — w pliku [bom.csv](bom.csv)

## Do sprawdzenia przez elektronika (przed zamówieniem)

1. DRV8962: wersja i obudowa (HTSSOP PowerPAD) oraz chłodzenie przez miedź; zamiennik przy braku: ST VNH7070AS
   (pełny mostek, PowerSSO‑36) — 15 sztuk, większa płytka.
2. Ochrona wejścia 12 V: zrzut obciążenia (ISO 7637‑2 / ISO 16750‑2) — TVS i dobór MOSFET‑a przeciw odwrotnej polaryzacji.
3. Ścieżki 20–30 A, grubość miedzi, nagrzewanie BTS50015 w zamkniętej obudowie.
4. Antena Bluetooth ESP32: moduł przy krawędzi płytki, bez miedzi pod anteną; obudowa z tworzywa (nie metal), okienko w ramie.
5. Wejście przepływomierza: poziomy różnych czujników (otwarty kolektor / push‑pull 12 V), częstotliwość do ~2 kHz.
6. Czujnik ciśnienia 4–20 mA: rezystor 150 Ω 0,1 % + ochrona wejścia ADS1115.
7. Złącza AMPSEAL: dokładne numery gniazd na płytkę (proste/kątowe) i obciążalność styków.
8. ESD/EMC na wszystkich pinach kostek (diody TVS, filtry RC na wejściach).

## Dalej

- **16b:** schemat i PCB w KiCad (hardware/maszyna/kicad), pliki Gerber + BOM/CPL dla JLCPCB.
- **16c:** obudowa do druku 3D (STL + źródło OpenSCAD) z otworami na dwie kostki, USB‑C, SMA, LED i uszczelką.
- **18:** firmware ESP32 (PGN 160/161 przez Bluetooth SPP / USB / CAN, sterowanie mostków DRV8962 przez 74HC595, PCNT, ADS1115, GPS).
