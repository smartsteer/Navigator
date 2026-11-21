# Panel przełączników opryskiwacza (bezprzewodowy, 6 × AA) — specyfikacja (etap 22a)

Zewnętrzny panel do przypięcia w kabinie w dowolnym miejscu: przełączniki sekcji jak w klasycznym terminalu,
diody RGB pokazujące to samo co Navigator, pokrętło dawki. Bez kabla — zasilanie z 6 baterii AA.
Wizualizacja: [wersja 2 — pod dłoń, e‑papier, roller](wizualizacja_v2.png) (aktualna), [wersja 1](wizualizacja.png).
Licencja GPLv3. **Przed zamówieniem projekt przegląda elektronik** (lista „Do sprawdzenia” na końcu).

Decyzje właściciela (2026-11-20): przełączniki dźwigniowe IP67, diody RGB w stylu ekranu Navigatora,
pokrętło do dawki, 6 × AA, płytka w całości montowana w JLCPCB, nowoczesna opływowa obudowa (własny projekt).

## Wersja 2 (decyzja właściciela 2026-11-20)

- **Obudowa pod dłoń**: z prawej wyprofilowane podparcie dłoni (miękka nakładka); kciuk: roller dawki i START/STOP, palce: dźwignie sekcji.
  Wersja dla leworęcznych — lustrzana obudowa, ta sama płytka.
- **Roller zamiast pokrętła**: kółko pod kciuk z magnesami, czujniki Halla pod zamkniętą ścianką (bez otworu → łatwe IP67; zamiast AS5600 — patrz obudowa/README.md);
  wciśnięcie (mikroprzełącznik pod uszczelką) = powrót do dawki zadanej.
- **Wyświetlacz e‑papier 4,2"** (np. moduł SPI 400 × 300): dawka zadana/rzeczywista, ciśnienie, zbiornik, hektary, prędkość;
  czytelny w słońcu, prąd tylko przy odświeżeniu (co 2–5 s przy pracy) — bilans baterii prawie bez zmian.
- **Dźwignie sekcji chwilowe** (samopowrót do środka): w górę = włącz, w dół = wyłącz. Stan pokazuje tylko dioda —
  **zielona** = pryska, **żółta** = wyłączona przez automat (pokrycie, granica, uwrocie), **ciemna** = wyłączona,
  **czerwona** = błąd zaworu sekcji. Nie ma już niezgodności dźwignia ↔ ekran (miganie niepotrzebne), a dźwignie w spoczynku
  nie pobierają prądu. Zmiana z tabletu po prostu zmienia diodę.
- **Włącznik zasilania** (kołyskowy I/O, uszczelniony) — odcina baterie całkowicie; panel wraca do ostatnio połączonej płytki.
- **Montaż „włóż, skręć, działa”** (decyzja właściciela 2026-11-20): każdy panel ma **własną płytkę**, wymiarami i położeniem
  otworów dokładnie pod swoją obudowę — dźwignie, przyciski, roller, diody i e‑papier są wlutowane w płytkę (JLCPCB),
  płytkę trzymają nakrętki dźwigni + 4 wkręty, baterie jedną wtyczką z komory. Zero przewodów do lutowania.
  Wspólny jest **schemat bloków** (procesor, zasilanie, sterownik diod, BLE) i **firmware** — płytka rozpoznaje sama,
  czy jest panelem oprysku, czy hydrauliki (rezystor identyfikacyjny na płytce).
- ~~Ta sama płytka panelu~~ Panel hydrauliki ma osobną płytkę o tym samym schemacie bloków — **panelu hydrauliki** (hardware/hydraulika) — inna płyta czołowa i firmware.

## Elementy na panelu

| Element | Ile | Działanie |
|---|---|---|
| Przełącznik dźwigniowy sekcji, IP67 (ON‑OFF) | 1–13 | góra = sekcja dozwolona (AUTO w Navigatorze), dół = sekcja wyłączona na stałe |
| Dioda RGB przy każdej sekcji | 1–13 | **zielona** = sekcja pryska, **żółta** = wyłączona przez automat (pokrycie, granica, uwrocie), **czerwona** = wyłączona przełącznikiem / na ekranie, **miga** = stan na ekranie inny niż dźwignia (np. zmieniony z tabletu) |
| Przycisk START/STOP z podświetlanym pierścieniem, IP67 | 1 | to samo co duży przycisk na ekranie Oprysk (zawór główny) |
| Przełącznik AUTO / RĘCZNIE (regulacja dawki) | 1 | jak na ekranie |
| Przełącznik oświetlenia roboczego | 1 | jak na ekranie |
| Roller pod kciuk (magnes + AS5600), z wciśnięciem | 1 | obrót: dawka ±5 % (AUTO) albo impulsy zaworu regulacyjnego (RĘCZNIE); wciśnięcie: powrót do dawki zadanej |
| Dioda stanu RGB | 1 | łączność (zielona), szukanie (niebieska miga), bateria słaba (żółta), brak łączności (czerwona) |
| Czujnik światła | 1 | jasność diod: pełna w dzień, przygaszona w nocy |

Płytka zawsze z 13 miejscami na sekcje (jak płytka Maszyna) — w obudowie zaślepki tam, gdzie opryskiwacz ma mniej sekcji.

## Łączność

```
Panel (ESP32‑C3, BLE) ──Bluetooth LE──► płytka Maszyna (ESP32, BLE obok Bluetooth SPP) ──PGN 161/162──► Navigator
                       ◄── stan diod ──                                                  ◄──PGN 160/163──
```

- Panel łączy się z **płytką Maszyna**, nie z tabletem: działa z Windows bez Bluetootha, z USB, z płytką Ster po CAN.
- Parowanie: przytrzymanie START/STOP przy włożeniu baterii → panel szuka; w Navigatorze „Oprysk → Ustawienia → Panel: Paruj”.
- Navigator jest jedynym „szefem” stanu: panel zgłasza zmianę dźwigni / przycisku / pokrętła, Navigator ją przyjmuje
  i odsyła stan wszystkich diod. Dzięki temu ekran i panel zawsze pokazują to samo.
- **Bezpieczeństwo:** brak łączności z panelem nic nie wyłącza ani nie włącza — sterowanie zostaje na ekranie,
  Navigator pokazuje „panel niedostępny”. Dźwignia w dół zawsze wygrywa (sekcja wyłączona), także gdy ekran mówi inaczej.
- Nowe ramki (szczegóły w etapie 22d, docs/MASZYNA.md): PGN 162 panel → Navigator (dźwignie, przyciski, kroki pokrętła,
  bateria), PGN 163 Navigator → panel (kolory diod, jasność). Po CAN: 0x384 / 0x385.

## Zasilanie — 6 × AA

- 6 × AA szeregowo: 9 V (alkaliczne 6,0–9,6 V; akumulatory NiMH 6 × 1,2 V = 7,2 V też działają).
- Przetwornica obniżająca 3,3 V z bardzo małym poborem własnym (TPS62177, ~5 µA), ochrona przed odwrotnym włożeniem
  baterii (MOSFET P), pomiar napięcia baterii dzielnikiem z kluczem (prąd płynie tylko przy pomiarze).
- Przełączniki: rezystory podciągające 1 MΩ (dźwignia „włączona” pobiera 3,3 µA, a nie 330 µA przy 10 k).
- Diody: zwykłe diody RGB (nie WS2812 — te pobierają ~1 mA każda, nawet zgaszone) sterowane z **2 × IS31FL3236A**
  (36 kanałów PWM po I²C, w uśpieniu ~1 µA) — 14 diod × 3 kolory = 42 kanały.

| Stan | Pobór (szacunek) |
|---|---|
| praca, diody w dzień (14 × ~2 mA) + BLE | ~30 mA z 3,3 V ≈ 100 mW |
| praca, diody w nocy (przygaszone) | ~8 mA ≈ 30 mW |
| uśpienie (STOP > 10 min albo brak Navigatora > 1 min) | ~15 µA |

6 × AA alkaliczne ≈ 20 Wh użytecznej energii → **ok. 200 h pracy w dzień, ponad 500 h nocą** (orientacyjnie — do
potwierdzenia na prototypie). To z grubsza sezon oprysków; Navigator pokaże „bateria panelu: X %” i ostrzeże z wyprzedzeniem.
Wybudzenie: dowolna dźwignia, przycisk albo pokrętło (przerwanie z ekspandera / pinów).

## Procesor i układy

| Blok | Część (MPN — do sprawdzenia z kartą i dostępnością w JLCPCB) |
|---|---|
| Procesor + BLE 5 | Espressif **ESP32‑C3‑MINI‑1‑N4** (antena na module, ta sama rodzina co płytka Maszyna — wspólny kod) |
| Sterownik diod | 2 × Lumissil **IS31FL3236A‑QFLS2** (36 kanałów PWM, I²C) |
| Wejścia (17 dźwigni/przycisków) | 3 × **TCA9534** (ekspander I²C z przerwaniem) albo bezpośrednio piny ESP32‑C3 + 1 ekspander |
| Zasilanie | **TPS62177** (buck 3,3 V, Iq 4,8 µA), MOSFET P przeciw odwrotnej polaryzacji, TVS |
| Diody RGB | 14 × dioda RGB ze wspólną anodą, obudowa 5050 albo przewlekane 5 mm pod soczewką (do wyboru przy obudowie) |
| Czujnik światła | fototranzystor (np. TEMT6000) na ADC |
| Przełączniki sekcji | dźwigniowe IP67 z nakrętką i uszczelką, ON‑OFF — np. APEM seria 600H albo C&K 7000 z uszczelnieniem panelowym (wybór w 22b) |
| START/STOP | przycisk wandaloodporny IP67 Ø 22 mm z pierścieniem RGB |
| Pokrętło | enkoder z przyciskiem w wersji uszczelnionej (np. Bourns PEC11R z uszczelką panelową albo enkoder IP65 do panelu) |

Elementy panelowe (dźwignie, przycisk, enkoder) są przewlekane i wkręcane w ściankę obudowy — w JLCPCB montaż SMD
plus THT; jeśli elementu IP67 nie ma w magazynie JLCPCB, płytka przychodzi bez niego (lutuje się 13 dźwigni ręcznie).

## Obudowa (etap 22c)

- Własny projekt, nowoczesna opływowa bryła (zaokrąglone krawędzie, pochylona płyta czołowa pod kciuk) — druk 3D z ASA,
  uszczelka na całym obwodzie, IP65/IP67 przy dobrze dokręconych nakrętkach przełączników.
- Opisy sekcji 1–13 wytłoczone przy dźwigniach, osłona przed przypadkowym przestawieniem (rant wokół dźwigni).
- Komora baterii 6 × AA od spodu z osobną uszczelką i zatrzaskiem (bez narzędzi).
- Mocowanie: płytka z magnesami neodymowymi (do błotnika / słupka kabiny) **albo** kula 1" (system RAM) — oba w STL.
- Wymiary orientacyjne: ok. 260 × 110 × 55 mm (13 dźwigni co 17 mm + pokrętło i START/STOP).

## Plan

- **22a** — ta specyfikacja i lista części (bom.csv).
- **22b** — schemat i PCB w KiCad (jak płytka Maszyna: schemat jako kod, router, pliki JLCPCB).
- **22c** — obudowa (OpenSCAD/CadQuery → STL).
- **22d** — firmware panelu (ESP32‑C3, BLE, uśpienie) + obsługa w płytce Maszyna (BLE obok SPP, PGN 162/163) + Navigator
  (Oprysk → Ustawienia → Panel: parowanie, bateria, „panel niedostępny”).

## Do sprawdzenia przez elektronika

1. BLE na płytce Maszyna równolegle z Bluetooth SPP (ESP32 tryb dual) — zasięg w kabinie i stabilność z tabletem.
2. Pobór diod RGB w pełnym słońcu (czy 2 mA na diodę wystarczy pod soczewką) — od tego zależy czas na bateriach.
3. Rzeczywisty pobór ESP32‑C3 z BLE w trybie lekkiego uśpienia (interwał połączenia 100–500 ms).
4. Wybór dźwigni IP67 dostępnej w Polsce / JLCPCB i ich rozstaw na płycie czołowej.
5. Antena modułu przy krawędzi płytki, obudowa z tworzywa (nie metal), bez miedzi pod anteną.
