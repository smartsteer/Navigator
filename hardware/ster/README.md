# Płytka Ster — AIO z wszystkim wlutowanym (etap 15, część a: specyfikacja)

Nasza wersja płytki **AIO Standard v4.5** z AgOpenGPS: ten sam procesor co Teensy 4.1, te same piny i porty,
więc **firmware AIO działa prawie bez zmian**, a wgrywanie jest takie samo jak do Teensy (Teensy Loader / PlatformIO).
Różnica: nic nie jest wpinane — procesor, GPS, sterownik silnika, Ethernet i IMU są na jednej płytce. Do tego
**CAN do płytki Maszyna** ([docs/MASZYNA.md](../../docs/MASZYNA.md)). Licencja: GPLv3.

Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), pliki `TeensyModules/AIO Standard v4/PCB Build/AIO v4.5 Std/`
(BOM, schemat) i `TeensyModules/AIO v4 Firmware/AIO_v4_Firmware/` (przydział pinów) z repozytorium
github.com/AgOpenGPS-Official/Boards. Teensy 4.1: projekt referencyjny PJRC (schemat publiczny).

> **Stan:** specyfikacja, schemat blokowy, przydział pinów, złącze i lista części ([bom.csv](bom.csv)). MPN do sprawdzenia
> z kartami, numery LCSC uzupełnić wyszukiwarką JLCPCB. **Przed zamówieniem — przegląd elektronika** (lista na końcu).
> Dalej: 15b schemat SKiDL → netlista, 15c PCB + pliki JLCPCB.

## Co jest na płytce (względem AIO v4.5 Std)

| Blok | AIO v4.5 Std | Ster |
|---|---|---|
| Procesor | Teensy 4.1 wpinany w listwy | **MIMXRT1062DVJ6A** (BGA‑196) + flash QSPI 8 MB + **układ bootloadera PJRC** (MKL02) — jak Teensy 4.1 |
| Ethernet | zestaw Ethernet Teensy + RJ45 HR913550A | **DP83825I** na płytce (ten sam PHY co Teensy 4.1) + RJ45 z transformatorem HR913550A |
| GPS | ZED‑F9P na płytce‑nakładce (jedna lub dwie) | **ZED‑F9P wlutowany**, jedna antena, gniazdo SMA na krawędzi |
| IMU | BNO085 wpinany (RVC, Serial5) | **złącze TM171** (UART, zasilanie do wyboru) + miejsce na BNO085 RVC (ten sam port) |
| Silnik skrętu | Cytron MD13S wpinany | **DRV8701E + 4 × MOSFET 40 V**, do 15 A, pomiar prądu bocznikiem (zamiast ACS712) |
| Czujnik kąta (WAS) | ADS1115, 5 V dla czujnika | to samo (ADS1115, I²C, 5 V przez diodę) |
| Przełączniki | Steer / Work / Remote przez transoptory EL3H7 | to samo |
| Ciśnienie / prąd z czujnika | wejście 0–5 V / 4–20 mA (zworka) | to samo |
| Impuls prędkości | wyjście przez transoptor | to samo |
| Zawór Danfoss | TC4427 (opcja) | to samo (opcja, można nie montować) |
| Blokada hydrauliki (LOCK) | IRF5305 do 5 A | to samo |
| CAN | MCP2562 (opcja, CAN3 — Keya) | **2 × CAN**: CAN3 jak AIO (Keya / ciągnik), CAN2 **do płytki Maszyna** |
| Złącze | AMPSEAL 23 (776087‑1) | **AMPSEAL 23 — ta sama wiązka co AIO** + 2 pary pinów na silnik |
| USB | USB Teensy | USB‑C procesora + USB‑C „GPS” (konfiguracja u‑center) |
| Zasilanie | K7805 ×2 + MC7805 | przetwornica 12→5 V 2 A + LDO 3,3 V procesora + osobne LDO 3,3 V dla GPS |

## Schemat blokowy

```
 12 V z ciągnika (AMPSEAL, 2 piny; bezpiecznik 20 A w wiązce)
   │
   ├─ idealna dioda (kontroler + 2 × MOSFET) + TVS ── 12 V „moc”
   │     ├─► DRV8701E + 4 × BUK7J1R4‑40H ── bocznik 3 mΩ ──► silnik skrętu (2 × 2 piny AMPSEAL)
   │     ├─► IRF5305 (LOCK, 5 A) ──► zawór blokady
   │     └─► TC4427 (Danfoss, opcja)
   │
   └─ PTC + filtr LC ── przetwornica 12→5 V (2 A)
         ├─► 5 V: czujnik kąta (przez diodę), czujnik ciśnienia, MCP2562 ×2, antena GNSS (PTC 100 mA), TM171 (zworka)
         ├─► LDO 3,3 V 1 A (TLV75733) ─► i.MXRT1062, flash, MKL02, DP83825, ADS1115
         └─► LDO 3,3 V niskoszumowe ─► ZED‑F9P (osobna wylewka, filtr ferrytowy)

 i.MXRT1062 ── Serial7 (piny 28/29) ◄──► ZED‑F9P UART1 (460800, NMEA GGA/VTG)
            ── Serial3 (piny 15/14) ◄─── radio RTK (złącze 4‑pin, 115200)
            ── Serial5 (piny 21/20) ◄──► TM171 / BNO085 RVC
            ── Serial2 (piny 7/8)  ─── wyprowadzone na pady: druga antena / UM982 (zapas)
            ── I²C Wire (18/19) ──► ADS1115 (0x48) ◄── czujnik kąta
            ── ENET (RMII) ──► DP83825I ──► RJ45 (tablet, 192.168.5.x)
            ── CAN3 (30/31) ──► MCP2562 ──► AMPSEAL (Keya / ciągnik, 250 kbit/s)
            ── CAN2 (0/1)  ──► MCP2562 ──► AMPSEAL (płytka Maszyna, 250 kbit/s)
            ── USB1 ──► USB‑C (AgIO USB, programowanie)
 ZED‑F9P ── USB ──► USB‑C „GPS” (u‑center, aktualizacja firmware odbiornika)
```

## Przydział pinów (numeracja Teensy 4.1 — tak jak w firmware AIO v4)

Płytka ma prowadzić **te same pady i.MXRT** co Teensy 4.1 (w 15b tabela „pin Teensy → pad i.MXRT” ze schematu PJRC),
wtedy firmware i biblioteki (FlexCAN_T4, QNEthernet/NativeEthernet) działają bez zmian.

| Pin Teensy | Funkcja w AIO v4 | Na płytce Ster |
|---|---|---|
| 2 | PWM1_LPWM | DRV8701E **EN** (PWM silnika) |
| 4 | DIR1_RL_ENABLE | DRV8701E **PH** (kierunek) — tryb „Cytron” w ustawieniach AgOpenGPS/Navigatora |
| 3 | PWM2_RPWM (w trybie Cytron: wyjście „przekaźnik/enable”) | LOCK (IRF5305) i — przez zworkę — nSLEEP DRV8701E |
| 32 / 34 / 37 | STEERSW / WORKSW / REMOTE | transoptory EL3H7 + przyciski serwisowe na płytce |
| A17 (41) | CURRENT_SENSOR_PIN | wyjście SO DRV8701E przez dzielnik (zamiast ACS712 — inne skalowanie, firmware 17) |
| A10 (24) | PRESSURE_SENSOR_PIN | wejście ciśnienia / 4–20 mA (zworka, rezystor 250 Ω) |
| 36 | velocityPWM_Pin | impuls prędkości przez transoptor |
| 5, 6, 9, 10, 11, 12, 13 | diody: zasilanie, Ethernet, GPS czerw./ziel., autoster czuwanie/aktywny, GGA | te same diody (na krawędzi, widoczne przez okienko) |
| 28 / 29 | Serial7 RX/TX | ZED‑F9P UART1 |
| 15 / 14 | Serial3 RX/TX | radio RTK |
| 21 / 20 | Serial5 RX/TX | TM171 / BNO085 RVC |
| 7 / 8 | Serial2 RX/TX | pady zapasowe (druga antena) |
| 18 / 19 | I²C SDA/SCL | ADS1115 |
| 30 / 31 | CAN3 RX/TX | CAN „ciągnik/Keya” (jak AIO Keya) |
| 0 / 1 | CAN2 RX/TX | CAN „Maszyna” |
| wolny pin (15b) | — | nFAULT DRV8701E |

## IMU TM171

Brak karty katalogowej (strona sklepu blokuje pobieranie). Dlatego: **złącze 6‑pin JST‑GH** — zasilanie wybierane
zworką (3,3 / 5 / 12 V przez PTC 200 mA), masa, TX, RX (3,3 V, z rezystorami 100 Ω), oraz **miejsce na MAX3232**
(montowany tylko, jeśli TM171 ma RS232 — zworki omijają go dla TTL). **Do potwierdzenia** z instrukcją od właściciela.
Do czasu firmware 17 można wpiąć BNO085 w trybie RVC na to samo złącze (firmware AIO bez zmian).

## Silnik skrętu

DRV8701E (PH/EN) + 4 × BUK7J1R4‑40H (jak w płytce Maszyna) — ten sam sposób sterowania co Cytron MD13S
(PWM + kierunek), więc ustawienie „Cytron” działa od razu. Ograniczenie prądu z VREF (cel 15 A szczyt, ok. 8 A ciągłe
dla typowego silnika na kierownicy), bocznik 3 mΩ, wzmacniacz SO wbudowany. Przy PWM = 0 mostek hamuje (jak Cytron);
po zworce nSLEEP od pinu 3 — przy utracie łączności silnik jest **odłączony** (kierownica lżejsza w ręku).

## Złącze — AMPSEAL 23 (ta sama wiązka co AIO)

Gniazdo **776087‑1** (C378893) jak w AIO v4.5 Std: zasilanie, silnik, czujnik kąta, przełączniki, ciśnienie,
impuls prędkości, LOCK, Danfoss, CAN. W 15b przepisujemy wyprowadzenia 1:1 ze schematu
`AiO-v4.5-Std_Schematic_2024-03-27.pdf`, żeby **istniejąca wiązka AIO pasowała bez zmian**; CAN „Maszyna” na pinach,
które w AIO są wolne/opcjonalne (do ustalenia w 15b). Silnik na 2 + 2 pinach (styk AMPSEAL ok. 7–17 A zależnie od
przekroju — sprawdzić). Poza kostką: RJ45, SMA, 2 × USB‑C, złącze TM171, złącze radia.

## Wymiary i obudowa

Cel: **ok. 100 × 75 mm**, 4 warstwy (BGA 0,8 mm wymaga co najmniej 4; jeśli wyprowadzenie BGA nie wyjdzie —
6 warstw w JLCPCB). Obudowa: gotowa Hammond 1455 (jak AIO Micro/Std) albo własna do druku (później, jak 16c).
Antena GPS na kablu do gniazda SMA (GPS na płytce — sama płytka może być w kabinie).

## Koszt (szacunek, do sprawdzenia w koszyku JLCPCB przy 5 szt.)

Największa pozycja to **ZED‑F9P** (ok. 180–250 USD/szt.), potem i.MXRT1062 (ok. 15–25 USD), układ bootloadera PJRC,
AMPSEAL (ok. 9 USD), mostek silnika (ok. 8 USD). Reszta to drobnica. Razem szacunkowo **ok. 1100–1500 zł/szt.**
z montażem — porównywalnie z AIO + Teensy + F9P + Cytron + BNO kupowanymi osobno, ale bez lutowania i wpinania.

## Do sprawdzenia przez elektronika (przed zamówieniem)

1. **Układ bootloadera PJRC** (MKL02 programowany przez PJRC): kupno w PJRC, dostawa do JLCPCB jako część klienta
   albo lutowanie ręczne (QFN‑16) — i warunki licencji PJRC na płytki własne.
2. i.MXRT1062: dostępność w LCSC (DVJ6A/B), wyprowadzenie BGA‑196 0,8 mm na 4 warstwach, zasilanie DCDC rdzenia jak w Teensy 4.1.
3. DP83825I: zegar 50 MHz z i.MXRT jak w Teensy 4.1 (bez osobnego kwarcu?), adres PHY, długości par RMII, ESD na RJ45.
4. ZED‑F9P: zasilanie anteny (5 V czy 3,3 V — zależnie od anteny), wykrywanie zwarcia anteny, prowadzenie RF 50 Ω
   do SMA, wylewka pod modułem, odległość od mostka silnika i przetwornicy.
5. Mostek silnika: prąd ciągły / szczytowy silników na kierownicy (typowo 5–10 A, rozruch więcej), chłodzenie MOSFET
   na 4 warstwach, nagrzewanie styków AMPSEAL, VREF dla 15 A.
6. Zrzut obciążenia 12 V (ISO 16750‑2): TVS i przetwornica z zapasem napięcia (np. 60 V).
7. Zgodność wyprowadzeń AMPSEAL z AIO v4.5 Std (ze schematu PDF) i wolne piny na CAN „Maszyna”.
8. TM171: zasilanie, poziomy (TTL/RS232), prędkość — po dostarczeniu dokumentacji.
9. Terminatory 120 Ω na obu CAN (zworki), ESD (PESD1CAN jak w AIO).

## Dalej

- **15b:** schemat (SKiDL → netlista, jak `hardware/maszyna/kicad`), tabela pin Teensy → pad i.MXRT, wyprowadzenia AMPSEAL.
- **15c:** PCB 4‑warstwowe, Gerber + BOM/CPL JLCPCB.
- **17:** firmware Ster na bazie AIO v4: TM171 zamiast BNO, skalowanie prądu DRV8701E, mostek UDP ↔ CAN do płytki Maszyna.
