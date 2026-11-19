# Firmware płytki Maszyna (ESP32) — etap 18a

Płytka: [hardware/maszyna](../../hardware/maszyna) (piny z `kicad/maszyna.py`). Protokół: [docs/MASZYNA.md](../../docs/MASZYNA.md). Licencja GPLv3.

## Co robi (18a)

- Odbiera **PGN 160** z Navigatora przez **USB‑C** (CH340C, 115200) i **Bluetooth SPP** („SmartSteer Maszyna”), a z płytki Ster przez **CAN** 0x300/0x301 (250 kbit/s; zworka JP_CAN założona = CAN wyłączony).
- Co 100 ms wysyła **PGN 161** (USB, Bluetooth, a po CAN 0x380/0x381/0x383 — tylko gdy płytka Ster coś nadaje).
- **Zabezpieczenie:** brak poleceń przez 0,5 s → zawór główny i sekcje zamknięte, impuls zaworu regulacyjnego skasowany (zawór zostaje w miejscu), zielona dioda miga. Oświetlenie zostaje, jak było (nie gaśnie nagle w nocy).
- Po starcie: mostki uśpione, rejestry wyzerowane; potem wszystkie zawory przestawiane na **zamknięte** (2‑pin też, bo ich położenie po zaniku zasilania jest nieznane).
- Zawory **3‑pin**: wyjście A = sygnał na stałe. **2‑pin**: biegunowość przez czas z Navigatora (domyślnie 3 s), potem wyłączone.
- Zawory przestawiane **kolejno co 50 ms** (najpierw główny) — mniejszy szczyt prądu.
- Sekcje na mostkach w kolejności płytki (`BRIDGE_SECTION` w `src/machine.h` = ta sama tabela co w `hardware/maszyna/kicad/maszyna.py`) — kostka A ma sekcje 1–13 na pinach 1–26 bez zmian.
- **Zawór regulacyjny:** impulsy ms z Navigatora wykonywane raz na licznik, zaległe się sumują (jak w symulatorze), po impulsie 100 ms hamowania.
- Przepływomierz: przerwanie na GPIO35, czas między impulsami (gdy przerwa > 1,5 × ostatni okres — podawana przerwa, szybsza reakcja na zamknięcie).
- ADS1115: ciśnienie (0,5–4,5 V albo 4–20 mA, zakres w `src/config.h`, domyślnie 10 bar) i napięcie zasilania (alarm < 10,5 V i > 16 V).
- GPS L76K: prędkość z RMC, satelity i fix z GGA.
- Błędy mostków: nFAULT 8 × DRV8962 przez 74HC165 (jeden układ = 2 mostki — błąd zgłaszany dla obu). **Przerwy w obwodzie DRV8962 nie wykrywa** (tylko zwarcie, przegrzanie, za niskie napięcie).
- Watchdog 2 s.

## Jeszcze nie (18b)

Tryb zgodny z AgOpenGPS (PGN 239 z AgIO przez Wi‑Fi), silnik 15 A (PWM, kierunek, prąd — dopisać do PGN 160/161), ustawienia czujnika ciśnienia z Navigatora, prąd lamp (IS BTS50015).

## Budowa i wgrywanie

```
pio run -d firmware/maszyna                 # .pio/build/maszyna/firmware.bin
pio run -d firmware/maszyna -t upload       # przez USB-C
```

Gotowy plik `maszyna-firmware.bin` jest w każdej budowie GitHub Actions (Artifacts). Bez PlatformIO: `esptool.py write_flash 0x10000 maszyna-firmware.bin` (bootloader i partycje — pierwsze wgranie przez PlatformIO).

Testy logiki na komputerze: `cd test && g++ -std=c++17 -I ../src ../src/protocol.cpp ../src/machine.cpp ../src/sensors.cpp test_main.cpp -o t && ./t`.
Wektory ramek są wspólne z Navigatorem (`SprayerFirmwareVectorTest.kt`).

## Próba bez płytki (płytka ESP32 DevKit)

Zwykły ESP32 DevKit z wgranym firmware: Navigator → Oprysk → Ustawienia → USB (port COM) albo Bluetooth. Powinno być „Łączność z płytką: OK”, prędkość „brak” (bez GPS), ciśnienie „brak”. Wyjęcie kabla → po 0,5 s zabezpieczenie.
