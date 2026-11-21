# Płytka Maszyna Lite — tania i uniwersalna (etap 16L, część a: specyfikacja)

Tańsza wersja płytki [Maszyna](../maszyna/README.md) (ta zostaje jako „premium”). Cel ceny: jak płytki AIO,
**ok. 150–250 zł/szt. przy 5 szt. z montażem w JLCPCB** (decyzja właściciela 2026-11-20).
Ten sam protokół ([docs/MASZYNA.md](../../docs/MASZYNA.md), PGN 160/161) i ten sam firmware — inna tabela pinów
i inna warstwa wyjść (patrz „Firmware” niżej). Licencja: GPLv3.

> **Stan:** specyfikacja, przydział pinów, złącza i lista części ([bom.csv](bom.csv)). Numery MPN do sprawdzenia
> z kartami katalogowymi, numery LCSC uzupełnić wyszukiwarką JLCPCB po MPN (wybierać części „Basic”, gdzie się da —
> każda część „Extended” to dopłata ok. 3 USD za typ). **Przed zamówieniem — przegląd elektronika** (lista na końcu).

## Co umie (i czego nie ma względem wersji pełnej)

| Funkcja | Lite | Pełna Maszyna |
|---|---|---|
| Wyjścia zaworów | **15 mostków H** (13 sekcji + główny + regulacyjny), każdy **osobny mały układ** | 16 mostków na 8 × DRV8962 |
| Prąd wyjścia | do ~2 A (ograniczenie prądu rezystorem, szczyt układu 3,6 A) | 2 A ciągłe |
| Typy zaworów | **2‑pin** (zmiana biegunowości), **3‑pin** (sygnał), **zawór z silniczkiem „jak od wycieraczek”** z krańcówką lub bez (stop po prądzie) | 2‑pin, 3‑pin |
| Pomiar prądu każdego wyjścia | tak (bocznik każdego mostka → multiplekser → ADC) — do wykrycia końca ruchu, zwarcia, przerwy | błędy z nFAULT |
| Oświetlenie | **wyjście na cewkę przekaźnika samochodowego** (przekaźnik 40 A w wiązce) | klucz 20 A na płytce |
| Przepływomierz / ciśnienie | tak / tak (0,5–4,5 V albo 4–20 mA, zworka) | tak / tak |
| Łączność | Bluetooth SPP, USB‑C, CAN | to samo |
| GPS na płytce | **brak** (prędkość z Navigatora) | L76K |
| Silnik 15 A | **brak** | DRV8701E |
| Warstwy płytki | **2**, miedź 1 oz | 4, miedź 2 oz |
| Złącza | **listwy rozłączne (wtyk + gniazdo) w skrzynce hermetycznej z dławikami** | 2 × AMPSEAL |

## Schemat blokowy

```
 12 V z ciągnika (listwa ZAS, przewód 2,5 mm², bezpiecznik 15 A w wiązce)
   │
   ├─ P‑MOSFET (odwrotna polaryzacja) + TVS SMCJ24A
   │
   ├──────► 15 × DRV8870 (albo AT8870) ── bocznik 0,15 Ω na ISEN ──► 15 wyjść A/B
   ├──────► AO3400 + dioda ──► cewka przekaźnika lamp (przekaźnik 40 A w wiązce)
   ├─ PTC 0,5 A ──► zasilanie przepływomierza 12 V
   └─ przetwornica 12→5 V (TPS54202) ─┬─► czujnik ciśnienia 5 V, TJA1051 (VCC)
                                      └─ LDO 3,3 V (AMS1117‑3.3) ─► ESP32, CH340C, rejestry, multiplekser

 ESP32‑WROOM‑32E ── SPI (tylko zapis) ─► 4 × 74HC595 (32 wyjścia: IN1, IN2 każdego mostka; 2 zapasowe)
                 ── 4 × GPIO ─────────► 74HC4067 (16 → 1): napięcie bocznika wybranego mostka ─► ADC1
                 ── ADC1 ◄── ciśnienie, napięcie zasilania
                 ── UART0 ─► CH340C ─► USB‑C (tablet / programowanie, auto‑reset)
                 ── TWAI ──► TJA1051T/3 ─► CAN
                 ── PCNT ◄── przepływomierz (filtr RC + 74LVC1G17)
                 ── Bluetooth Classic SPP (wbudowany) ─► tablet
```

## Wyjścia — mostek H na każde wyjście

**DRV8870DDAR** (TI, HSOP‑8 z padem, 6,5–45 V, 3,6 A szczyt) albo tańszy **AT8870** (zamiennik w tej samej obudowie —
sprawdzić zgodność wyprowadzeń i ograniczenia prądu z kartą). Każdy układ: 2 wejścia (IN1, IN2), 2 wyjścia, pin ISEN.

| IN1 | IN2 | Wyjście | Zastosowanie |
|---|---|---|---|
| 0 | 0 | odłączone (po 1 ms układ sam zasypia) | spoczynek, start płytki |
| 1 | 0 | A = +12, B = 0 | otwórz (2‑pin, silniczek) / sygnał 3‑pin = otwarty |
| 0 | 1 | A = 0, B = +12 | zamknij (2‑pin, silniczek) / sygnał 3‑pin = zamknięty |
| 1 | 1 | hamowanie (oba do masy) | zatrzymanie silniczka bez wybiegu |

**Ograniczenie prądu** (wbudowane w układ, przez bocznik): I = VREF / (10 · R_ISEN). VREF = 3,0 V z dzielnika
(wspólny dla wszystkich mostków), R_ISEN = 0,15 Ω (2512, 1 W) → **I ≈ 2,0 A**. Zmiana progu = inny bocznik
(0,3 Ω → 1 A, 0,1 Ω → 3 A — wtedy cieplej, sprawdzić).

**Pomiar prądu:** napięcie na boczniku (0,3 V przy 2 A) → filtr RC 1 kΩ / 1 µF na każdym kanale → 74HC4067 →
ADC1 ESP32 (tłumienie 0 dB, kalibracja z eFuse). Firmware przegląda 15 kanałów co ~50 ms (~3 ms na kanał).

**Jak firmware kończy ruch zaworu** (wymaganie: „czas pracy + ograniczenie prądu przy zatrzymaniu”):

| Typ | Koniec ruchu |
|---|---|
| 2‑pin Arag (krańcówki w zaworze) | prąd spada prawie do zera **albo** minął czas z PGN 160 bajt 5 (domyślnie 3 s) |
| silniczek z krańcówką („wycieraczka”) | prąd spadł < ~50 mA na > 100 ms (krańcówka rozłączyła) → wyłącz |
| silniczek bez krańcówki | prąd na ograniczeniu > ~300 ms (zablokowany na końcu) → hamowanie i wyłącz |
| 3‑pin | sygnał trzymany na stałe (mały prąd), bez limitu czasu |
| każdy | czas maksymalny (bezpiecznik programowy); prąd na ograniczeniu od razu po starcie = zwarcie → błąd wyjścia; przy 2‑pin brak prądu od początku = przerwa → błąd |

Zawory przestawiane **kolejno co 50 ms** (jak w pełnej płytce) — szczyt prądu kilka A, bezpiecznik 15 A wystarcza.

**Bezpieczny start:** 74HC595 mają nOE podciągnięte do 3,3 V (wyjścia odcięte do czasu inicjalizacji) i rezystory
10 kΩ do masy na każdym IN → przy resecie i zawieszeniu procesora (watchdog) wszystkie mostki = 0/0 (nic się nie rusza).
Zabezpieczenie 0,5 s bez poleceń jak w pełnej płytce. **Zalecenie: zawór główny 3‑pin** (zamyka się sam przy zaniku sygnału).

Kolejność bitów w łańcuchu 74HC595: bit 2k = IN1, bit 2k+1 = IN2 mostka k (k = 0…14: sekcje 1–13, 13 = główny,
14 = regulacyjny), bity 30–31 zapasowe. Przy rysowaniu płytki wolno zmienić kolejność pod ścieżki — wtedy tabela w firmware.

## Przydział pinów ESP32‑WROOM‑32E

| GPIO | Funkcja |
|---|---|
| 18 / 23 | SPI SCK / MOSI → 74HC595 |
| 5 | RCLK 74HC595 (zatrzaśnięcie) |
| 4 | nOE 74HC595 — 10 k do 3,3 V: po resecie wyjścia odcięte |
| 16 / 17 / 13 / 14 | S0–S3 multipleksera 74HC4067 |
| 34 | ADC1_CH6 ← wyjście multipleksera (prąd wybranego mostka) |
| 36 | ADC1_CH0 ← czujnik ciśnienia (dzielnik / bocznik 4–20 mA) |
| 39 | ADC1_CH3 ← napięcie zasilania (dzielnik 100 k / 15 k) |
| 35 | przepływomierz (PCNT, wejście tylko) |
| 32 / 33 | CAN TX / RX (TWAI → TJA1051T/3) |
| 25 | przekaźnik lamp (bramka AO3400) |
| 26 / 27 | LED zielona (praca/łączność) / czerwona (błąd) |
| 19 | zworka „CAN / tylko BT” (podciągnięta, założona = 0) |
| 21 / 22 | wyprowadzone na złącze serwisowe (I²C na przyszłość, np. ADS1115 albo panel) |
| 1 / 3 | UART0 ↔ CH340C (USB) |
| EN, 0 | auto‑reset i programowanie z CH340C (2 tranzystory jak w DevKitC) |
| 2, 12, 15 | nieużywane (piny startowe — 12 i 2 z 10 k do masy) |

Tylko ADC1 — ADC2 nie działa przy włączonym radiu (Bluetooth).

**Czujnik ciśnienia:** 0,5–4,5 V → dzielnik 10 k / 20 k → 0,33–3,0 V; 4–20 mA → bocznik 150 Ω 0,1 % → 0,6–3,0 V
(zworka wybiera). Dokładność ADC ESP32 po kalibracji ok. ±1–2 % zakresu — wystarcza do oprysku; jeśli nie, na złącze
serwisowe można dołożyć ADS1115.

## Złącza — skrzynka hermetyczna z dławikami (najtańsze i pasuje do każdej wiązki)

Na płytce **listwy rozłączne 3,5 mm** (gniazdo na płytce + wtyk śrubowy, np. typ 15EDGRC/15EDGK) — monter odpina
wtyk, przykręca przewody i wpina z powrotem. Płytka w skrzynce **IP67 z tworzywa** (ABS/PC, ok. 200 × 150 × 75 mm),
przewody przez **dławiki M16/M20** (wielootworowe wkładki na kilka przewodów), membrana odpowietrzająca M12.
Wersja „bez skrzynki” (Deutsch DT na płytce) — później, jeśli klienci będą chcieli.

| Listwa | Piny | Sygnały |
|---|---|---|
| W1…W15 (każde wyjście) | 4 | **A, B, +12, masa** — 2‑pin i silniczek: A/B; 3‑pin Arag: brązowy +12, niebieski masa, czarny sygnał = A |
| ZAS | 2 (raster 5,08 mm) | +12 V wejście / masa (2,5 mm², bezpiecznik 15 A w wiązce) |
| CZUJ | 6 | przepływomierz +12 / sygnał / masa, ciśnienie +5 / sygnał / masa |
| CAN | 3 | CAN‑H / CAN‑L / masa (terminator 120 Ω na zworce) |
| LAMPY | 2 | cewka przekaźnika: +12 / wyjście (niska strona, AO3400, dioda gasząca na płytce); przekaźnik 40 A i bezpiecznik lamp w wiązce |
| SERW | 4 (goldpin) | 3,3 V / SDA / SCL / masa |

Na krawędzi płytki: USB‑C (przez dławik/zaślepkę w skrzynce albo przewód USB przez dławik), diody LED widoczne przez
przezroczystą pokrywę. Wspólne +12 dla W1…W15 za bezpiecznikiem polimerowym/topikowym 10 A na płytce.

Rozkład: płytka ok. **160 × 100 mm**, listwy wyjść na dwóch długich krawędziach (8 + 7 × 14 mm), zasilanie, czujniki
i CAN na krótkiej krawędzi, ESP32 anteną do krawędzi, bez miedzi pod anteną.

## Ochrona i trwałość (taniej niż w pełnej, ale bez skrótów na bezpieczeństwie)

- Odwrotna polaryzacja: P‑MOSFET (np. AOD4185, 40 V) zamiast idealnej diody LM74700 — przy kilku amperach straty < 0,5 W.
- Zrzut obciążenia: TVS SMCJ24A na wejściu (DRV8870 wytrzymuje 45 V, TPS54202 28 V — przetwornica za TVS
  i dodatkowo rezystor/ferryt; **do sprawdzenia**).
- Każde wyjście: wbudowane w DRV8870 zabezpieczenie nadprądowe i termiczne; diody gaszące niepotrzebne (mostek sam
  przejmuje prąd indukcyjności), TVS 0402/SOD‑323 na pinach listew przy złączach.
- Wejścia czujników z filtrami RC i diodami ograniczającymi; zasilanie przepływomierza przez PTC 0,5 A.
- Lakier ochronny (opcja JLCPCB), części −40…+105 °C, ceramika X7R.

## Koszt (szacunek, do sprawdzenia w koszyku JLCPCB/LCSC przy 5 szt.)

| Pozycja | na 1 płytkę |
|---|---|
| 15 × DRV8870 (AT8870 ok. 3× taniej) | 4–14 USD |
| ESP32‑WROOM‑32E, CH340C, TJA1051, TPS54202, AMS1117 | 5–6 USD |
| 74HC595 × 4, 74HC4067, 74LVC1G17, AO3400, P‑MOSFET, TVS, bierne | 3–4 USD |
| 15 boczników 2512 | 1–2 USD |
| listwy rozłączne (gniazda + wtyki, ~75 pinów) | 4–7 USD |
| płytka 2‑warstwowa 160 × 100 + montaż (udział w opłatach przy 5 szt.) | 6–10 USD |
| **razem płytka** | **ok. 25–43 USD ≈ 100–175 zł** |
| skrzynka IP67 + dławiki + membrana | 30–60 zł |

Mieści się w celu 150–250 zł z AT8870 i tanią skrzynką; z DRV8870 i lepszą skrzynką — górna granica.

## Firmware (etap 16Lc)

Ten sam kod z `firmware/maszyna` (protokół, zabezpieczenia, przepływomierz, CAN, Bluetooth, USB). Różnice:
osobne środowisko `[env:maszyna-lite]` z `-DSS_BOARD_LITE` i własnym `config_lite.h`; warstwa wyjść: 4 × 74HC595
(2 bity na mostek zamiast 3), brak 74HC165 — błędy z pomiaru prądu; nowy moduł pomiaru prądu z multipleksera
i logika końca ruchu z tabeli wyżej; ciśnienie z ADC1 zamiast ADS1115; brak GPS (pola GPS w PGN 161 = brak fix).
**Protokół:** typ „silniczek z krańcówką / stop po prądzie” jako nowy bit w PGN 160 bajt 1 (bit3) — do dopisania
w docs/MASZYNA.md razem z firmware, z zachowaniem zgodności (0 = jak dziś).

## Do sprawdzenia przez elektronika (przed zamówieniem)

1. AT8870 a DRV8870: zgodność wyprowadzeń, ograniczenie prądu (wzór z ISEN), zachowanie przy hamowaniu; dostępność w LCSC.
2. Ograniczenie prądu 2 A wystarcza dla silniczków „wycieraczkowych” (prąd rozruchu) — ewentualnie bocznik 0,1 Ω (3 A).
3. Moc boczników 0,15 Ω (0,6 W przy 2 A — tylko w czasie ruchu) i nagrzewanie HSOP‑8 na 2 warstwach 1 oz.
4. Ochrona 12 V: zrzut obciążenia (ISO 16750‑2) przy TPS54202 28 V — może lepiej LMR16006 (60 V) albo TVS niższy.
5. Pomiar prądu przez ADC ESP32 (0–0,3 V): szum i rozdzielczość; ewentualnie wzmacniacz ×10 (LM358/TLV9062) przed multiplekserem.
6. Obciążalność listew 3,5 mm (zwykle 8 A) i zasilania 5,08 mm (15 A) przy przewodach 1,5 / 2,5 mm².
7. Antena Bluetooth w skrzynce z tworzywa na ramie opryskiwacza.
8. ESD/EMC na pinach listew.

## Dalej

- **16Lb:** schemat (SKiDL → netlista, jak w `hardware/maszyna/kicad`) i PCB 2‑warstwowe, Gerber + BOM/CPL JLCPCB.
- **16Lc:** firmware w wersji Lite (`env:maszyna-lite`) + bit typu zaworu w protokole i wybór w Navigatorze („2‑pin / 3‑pin / silniczek”).
- **16Ld:** skrzynka: wybór gotowej obudowy IP67 + płyta montażowa do druku 3D, rozmieszczenie dławików.
