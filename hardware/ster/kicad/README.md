# Płytka Ster — schemat jako kod (etap 15b)

Schemat płytki Ster w SKiDL → netlista KiCad. Specyfikacja: [`../README.md`](../README.md). Licencja GPLv3.
Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), pliki `TeensyModules/AIO Standard v4/PCB Build/AIO v4.5 Std/`
(PCB EasyEDA, „Ampseal pinout.jpg”) i `TeensyModules/AIO v4 Firmware/` z github.com/AgOpenGPS-Official/Boards.

| Plik | Co to |
|---|---|
| `ster.py` | schemat — źródło prawdy; `pip install skidl==1.2.3`, potem `python3 ster.py` → `ster.net` (bez bibliotek KiCad — wszystkie części zdefiniowane w kodzie) |
| `ster.net` | netlista KiCad (248 części, 208 sieci) do 15c |
| `imxrt1062_kulki.csv` | wszystkie 196 kulek MIMXRT1062 (MAPBGA‑196): kulka, sygnał, pin Teensy 4.1 |
| `bga196.py` → `smartsteer_ster.pretty` | obudowa MAPBGA‑196 12 × 12 mm / 0,8 mm (pola NSMD 0,40 mm); reszta obudów z bibliotek KiCad 7 |
| `place.py` | obrys 110 × 80 mm, 4 warstwy, rozmieszczenie (etap 15c1) |
| `netclass.py` | klasy połączeń: Default 0,15/0,127 mm (przelotka 0,45/0,2), Moc 1,2 mm, Zasilanie 0,4 mm, RF 0,35 mm, USB 0,2 mm |
| `ster.kicad_pcb`, `ster.kicad_pro` | płytka: części rozmieszczone, płaszczyzny In1 = GND, In2 = V3V3_STER; **bez ścieżek** (15c2) |

Kolejność (narzędzia: `../../narzedzia/README.md`): `ster.py` → `kinet2pcb -i ster.net -w -l .` → `netclass.py` → `place.py`
→ `plaszczyzny.py ster.kicad_pcb In1.Cu:GND In2.Cu:V3V3_STER` → (15c2) wyprowadzenie BGA, Freerouting, `dotrasuj.py`, wylewki mocy,
`drc.py`, `fab_jlcpcb.py`.

`ster.py` przy każdym uruchomieniu sprawdza (assert): AMPSEAL 1:1 z AIO v4.5 Std (poza 13 i 19 — CAN Maszyna), piny
Teensy z firmware AIO na tych samych kulkach co w Teensy 4.1, wszystkie kulki zasilania i masy podłączone; ERC bez uwag.

**Mapa kulek** pochodzi z `bga_pinout_t41` (PJRC, w repozytorium SparkFun MicroMod Teensy) i jest zgodna z kartą NXP
IMXRT1060CEC (tabele 86–87) dla wszystkich 196 kulek. Rdzeń (DCDC 1,1 V, kondensatory, kwarce 24 MHz / 32 kHz, flash QSPI
na SD_B1, bootloader MKL02 na JTAG/POR_B/BOOT_MODE0, kluczowanie 3,3 V przez PMIC_ON_REQ) odtworzony z połączeń
otwartego projektu SparkFun MicroMod Teensy (CC BY-SA 4.0) — czyli tak jak w Teensy 4.x, więc Teensy Loader działa.

## AMPSEAL 23 — ta sama wiązka co AIO v4.5 Std

| Pin | AIO v4.5 Std | Ster |
|---|---|---|
| 1 | 5V WAS | 5 V czujnika kąta (PTC 100 mA) |
| 2 / 3 | WAS High / Low | ADS1115 AIN0 / AIN1 (5 V, adres 0x48) |
| 4, 21, 23 | GND | masa |
| 5 / 6 | LEFT / RIGHT (Cytron) | wyjścia mostka DRV8701E |
| 7 | LOCK | AOD4185 z 12 V (5 A), dioda gasząca |
| 8 / 9 | Steer / Work | transoptory → piny 32 / 34, przyciski serwisowe |
| 10 | Pres/Rem | zworka: Remote (pin 37, domyślnie) albo ciśnienie (A10) |
| 11 / 12 | A12 / A13 | piny 26 / 27 przez zworki (zwarte) |
| 13 | A14 | **zmiana:** domyślnie CAN „Maszyna” L; zworka 2‑3 → A14 (pin 38) jak AIO |
| 14 / 15 | Speed + / − | transoptor od pinu 36 (otwarty kolektor; zworki: podciągnięcie 12 V, minus do masy) |
| 16 / 17 | CAN H / L | CAN3 (ciągnik / Keya), terminator zworką |
| 18 | Alt cytron pwr | zasilanie mostka; zworka z 12 V zwarta (otworzyć przy osobnym przewodzie) |
| 19 | — (wolny) | **CAN „Maszyna” H** (CAN2, piny 0/1) |
| 20 | +12 V Board | 12 V za idealną diodą |
| 22 | +12 V in | wejście 12 V |

Silnik zostaje na **jednym styku na fazę** (5, 6) i zasilanie 18 — jak AIO, żeby wiązka pasowała (w 15a było „2 + 2”,
ale AMPSEAL 23 nie ma wolnych pinów). Sprawdzić prąd styku przy przekroju przewodu (lista niżej).

## Piny Teensy → pad i.MXRT (te używane na płytce)

| Pin Teensy | Pad i.MXRT | Kulka BGA | Na płytce Ster |
|---|---|---|---|
| 0 | GPIO_AD_B0_03 | G11 | CAN2 RX → MCP2562 „Maszyna” |
| 1 | GPIO_AD_B0_02 | M11 | CAN2 TX → MCP2562 „Maszyna” |
| 2 | GPIO_EMC_04 | F2 | PWM silnika → DRV8701E EN |
| 3 | GPIO_EMC_05 | G5 | LOCK (2N7002 → AOD4185) + zworka nSLEEP |
| 4 | GPIO_EMC_06 | H5 | kierunek → DRV8701E PH |
| 5 | GPIO_EMC_08 | H3 | dioda: zasilanie |
| 6 | GPIO_B0_10 | D9 | dioda: Ethernet |
| 7 | GPIO_B1_01 | B11 | Serial2 RX (zapas) |
| 8 | GPIO_B1_00 | A11 | Serial2 TX (zapas) |
| 9 | GPIO_B0_11 | A10 | dioda: GPS czerwona |
| 10 | GPIO_B0_00 | D7 | dioda: GPS zielona |
| 11 | GPIO_B0_02 | E8 | dioda: autoster czuwanie |
| 12 | GPIO_B0_01 | E7 | dioda: autoster aktywny |
| 13 | GPIO_B0_03 | D8 | dioda: GGA |
| 14 | GPIO_AD_B1_02 | L11 | Serial3 TX → radio RTK |
| 15 | GPIO_AD_B1_03 | M12 | Serial3 RX ← radio RTK |
| 18 | GPIO_AD_B1_01 | K11 | I²C SDA → ADS1115 (translator 5 V) |
| 19 | GPIO_AD_B1_00 | J11 | I²C SCL → ADS1115 |
| 20 | GPIO_AD_B1_10 | L13 | Serial5 TX → IMU |
| 21 | GPIO_AD_B1_11 | J13 | Serial5 RX ← IMU (TM171 / BNO085 RVC) |
| 24 | GPIO_AD_B0_12 | K14 | A10 ciśnienie (dzielnik 10k/20k, 4–20 mA zworką) |
| 26 | GPIO_AD_B1_14 | G12 | A12 ← AMPSEAL 11 |
| 27 | GPIO_AD_B1_15 | J14 | A13 ← AMPSEAL 12 |
| 28 | GPIO_EMC_32 | D5 | Serial7 RX ← ZED‑F9P TXD |
| 29 | GPIO_EMC_31 | C5 | Serial7 TX → ZED‑F9P RXD |
| 30 | GPIO_EMC_37 | E4 | CAN3 RX (ciągnik / Keya) |
| 31 | GPIO_EMC_36 | C3 | CAN3 TX |
| 32 | GPIO_B0_12 | C10 | STEERSW ← transoptor |
| 33 | GPIO_EMC_07 | H4 | nFAULT DRV8701E (nowe, firmware 17) |
| 34 | GPIO_B1_13 | D14 | WORKSW ← transoptor |
| 36 | GPIO_B1_02 | C11 | impuls prędkości → transoptor |
| 37 | GPIO_B1_03 | D11 | REMOTE ← transoptor |
| 38 | GPIO_AD_B1_12 | H12 | A14 ← AMPSEAL 13 (zworka 2‑3) |
| 41 | GPIO_AD_B1_05 | K12 | A17 prąd silnika (SO DRV8701E, 60 mV/A) |

Ethernet RMII jak Teensy 4.1: GPIO_B1_04…B1_11, B1_14/B1_15 (MDC/MDIO), B0_14 reset, B0_15 power‑down, zegar 50 MHz
z GPIO_B1_10 do XI układu PHY. Flash QSPI: GPIO_SD_B1_06…11.

## Zmiany względem specyfikacji 15a

- Procesor **MIMXRT1062DVL6A** (12 × 12 mm, raster 0,8 mm) zamiast DVJ6A (to 10 × 10 mm, 0,65 mm — trudne na 4 warstwach JLCPCB).
  Rozkład kulek identyczny; Teensy 4.1 ma DVJ6A, firmware nie widzi różnicy.
- Przetwornica **LMR33620** (2 A, rodzina i piny jak LMR33610 na płytkach Maszyna; wejście do 36 V) zamiast LMR16020.
- LOCK na **AOD4185** (P‑MOSFET D‑PAK, jak Maszyna Lite) z Zenerem na bramce zamiast IRF5305 TO‑220.
- ADS1115 zasilany 5 V z translatorami I²C (2 × BSS138) — jak AIO, żeby czujnik kąta 0–5 V nie przekraczał zasilania ADC.
- Zasilanie z USB‑C przy programowaniu bez 12 V (dioda VBUS → 5 V).
- Dioda stanu przy każdym wejściu Steer/Work/Remote.

## Rozmieszczenie (15c1, 2026-11-28)

Płytka **110 × 80 mm** (w 15a cel 100 × 75 — nie zmieściło się z AMPSEAL 23 o szerokości 61 mm i dwoma kondensatorami
1000 µF; Hammond 1455N2201 ma 103 × 160 wewnątrz — do potwierdzenia przy obudowie).
- Dół: AMPSEAL 23 (krawędź obudowy na krawędzi płytki), nad nim mostek silnika (4 × BUK7J1R4) i DRV8701E, LOCK (AOD4185) z lewej.
- Prawy dół: wejście 12 V (idealna dioda 2 × BUK7J1R4 + LM74700, TVS SMCJ24A), LMR33620 z dławikiem, 2 × 1000 µF przy krawędzi.
- Góra: RJ45 (lewy róg), USB‑C procesora, USB‑C GPS, SMA anteny (prawy róg, nad ZED‑F9P). Prawa krawędź: JST GH radio RTK i IMU.
- Środek: i.MXRT1062 (strefa 16 × 16 mm wolna od drobnych części na wyprowadzenie BGA), flash, MKL02, kwarce, DP83825I między procesorem a RJ45.

Zmiany obudów: RJ45 **HR911105A** (LCSC C12074, jest w bibliotece KiCad) zamiast HR913550A; przyciski TS‑1187A na footprincie
**TL3342** (5,2 × 5,2 mm, 4 pady — TS‑1187A ma ten sam rozstaw, sprawdzić przy zakupie); DP83825I na QFN‑24 3 × 3 mm 0,4 mm,
pole 1,75 × 1,6 mm (sprawdzić z rysunkiem RMQ0024A).

DP83825I — numery pinów z karty TI SNLS638C (tabela 4‑1). Tryb RMII „follower” jak Teensy 4.1: 50 MHz z procesora na XI (13),
XO (12) i pin 2 wolne; biblioteki NativeEthernet/QNEthernet same ustawiają rejestr RCSR (0x17) = 0x0081. Paski (piny 2, 4, 17,
18, 20, 22) zostają na wewnętrznych ściągnięciach jak w Teensy 4.1 (adres PHY 0).

## Do sprawdzenia (przed 15c i przez elektronika)

1. RJ45 HR911105A: piny diod 9–12 (zielona 11/12, żółta 9/10) z karty producenta przed zamówieniem.
2. DP83825I: pole centralne (rysunek RMQ0024A), RBIAS 6,49 kΩ, czy firmware AIO (NativeEthernet) ustawia tryb 50 MHz — jak Teensy 4.1.
3. Styk AMPSEAL dla silnika (5, 6, 18): prąd ciągły silnika na kierownicy u właściciela; w razie potrzeby grubszy przewód.
4. LMR33620 do 36 V za SMCJ24A (napięcie zacisku ok. 39 V przy pełnym udarze) — jak na płytkach Maszyna; ewentualnie TVS 22 V.
5. Lista z [`../README.md`](../README.md) (bootloader PJRC, zasilanie anteny, RF 50 Ω, TM171).
