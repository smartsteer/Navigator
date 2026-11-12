# Protokół opryskiwacza SmartSteer (płytki Ster i Maszyna)

Implementacja w Navigatorze: `core/sprayer/SprayerProtocol.kt` (kodowanie/dekodowanie), `core/sprayer/SprayerSim.kt` (symulator), testy w `commonTest/.../sprayer`.

## Układ

```
Tablet (Navigator) ──UDP 192.168.5.x──► płytka Ster ──CAN 250 kbit/s──► płytka Maszyna (opryskiwacz)
```

Wersja **bez autosteru** (mniejsze gospodarstwa): tylko płytka Maszyna, podłączona do tabletu **kablem USB‑C** (port szeregowy, te same ramki co po UDP). Port: USB CDC ACM (Windows: port COM bez sterowników, Android: USB host — zgoda w okienku systemu przy pierwszym podłączeniu), 115200 8N1 (przy CDC prędkość i tak nie ma znaczenia). Navigator wybiera źródło w Oprysk → Ustawienia → Połączenie: Symulator / USB / Płytka Ster (sieć). **Albo Bluetooth (SPP)** — płytka zostaje na opryskiwaczu, z ciągnika idzie tylko zasilanie: Android łączy się ze sparowaną płytką „BT: nazwa — adres”, Windows przez port COM Bluetooth. Te same ramki; przy zerwaniu łącza płytka po 0,5 s zamyka zawory. GPS z tabletu/telefonu albo z odbiornika na Bluetooth — Navigator już to obsługuje. Do kontroli sekcji zalecany odbiornik z dokładnością ≤ 1 m (GPS telefonu ~3–5 m da zakładki i luki na granicy).

## Ramki Navigator ↔ Ster (UDP) / Navigator ↔ Maszyna (USB)

Ramka jak w AgOpenGPS: `0x80 0x81 źródło PGN długość dane CRC`, CRC = młodszy bajt sumy od bajtu źródła do końca danych. Liczby little-endian. PGN 160/161 nie są używane przez AgOpenGPS.

### PGN 160 (0xA0) — polecenie, Navigator → maszyna, co 100 ms (8 bajtów)

| bajt | znaczenie |
|---|---|
| 0 | licznik 0–255, rośnie z każdą ramką |
| 1 | bit0 zawór główny otwarty, bit1 oświetlenie robocze, bit2 zawory Arag 3‑pin (0 = 2‑pin) |
| 2–3 | maska sekcji, bit0 = sekcja 1 … bit12 = sekcja 13; 1 = otwarta |
| 4 | liczba sekcji 1–13 (ustawiana w Navigatorze; płytka uniwersalna ma 13 kanałów) |
| 5 | czas zasilania zaworu 2‑pin po przestawieniu ×100 ms (0 = 3 s) |
| 6–7 | impuls zaworu regulacyjnego w ms (int16): + więcej cieczy, − mniej; wykonywany **raz na licznik** |

Regulacja dawki (PID, uczenie zaworu) liczy się w Navigatorze — płytka tylko wykonuje impulsy.

### PGN 161 (0xA1) — stan, maszyna → Navigator, co 100 ms (20 bajtów; starsze 16 bajtów bez GPS płytki też czytane)

| bajt | znaczenie |
|---|---|
| 0 | licznik ostatnio wykonanego polecenia |
| 1 | bit0 łączność z płytką Maszyna, bit1 zawór główny otwarty, bit2 oświetlenie wł., bit3 zabezpieczenie aktywne |
| 2–5 | licznik impulsów przepływomierza (uint32, przekręca się) |
| 6–9 | czas między dwoma ostatnimi impulsami, µs (uint32; 0 = brak impulsów od 2 s) |
| 10–11 | ciśnienie ×0,01 bar (0xFFFF = brak czujnika) |
| 12–13 | maska błędów wyjść sekcji (przerwa / zwarcie) |
| 14 | błędy: bit0 zawór główny, bit1 zawór regulacyjny, bit2 oświetlenie, bit3 napięcie zasilania |
| 15 | napięcie zasilania ×0,1 V |
| 16–17 | prędkość z modułu GPS na płytce ×0,01 km/h (0xFFFF = brak fixu) — do pracy bez GPS w tablecie |
| 18 | liczba satelitów GPS płytki |
| 19 | fix GPS płytki: 0 brak, 1 GPS, 2 DGPS/SBAS |

Przepływ [l/min] = 60 000 000 / (czas między impulsami µs × impulsy na litr). Impulsy na litr wpisywane w Navigatorze (różne u producentów, np. Arag Wolf 255).

## CAN Ster ↔ Maszyna

250 kbit/s, identyfikatory 11‑bit, 8 bajtów danych. Przewód 4 żyły: 12 V, masa, CAN‑H, CAN‑L (terminatory 120 Ω na obu płytkach, zworka). Moc zaworów osobnym grubym przewodem z ciągnika.

| ID | kierunek | dane |
|---|---|---|
| 0x300 | Ster → Maszyna | bajty 0–5 PGN 160, 6–7 zarezerwowane |
| 0x301 | Ster → Maszyna | bajt 0 licznik, 1–2 impuls zaworu regulacyjnego (int16 ms) |
| 0x380 | Maszyna → Ster | bajty 2–9 PGN 161 (impulsy, czas między impulsami) |
| 0x381 | Maszyna → Ster | bajt 0 licznik, 1 flagi, 2–3 ciśnienie, 4–5 błędy sekcji, 6 błędy, 7 napięcie |
| 0x383 | Maszyna → Ster | bajty 16–19 PGN 161 (GPS płytki) |
| 0x382 | — | rezerwa: zasuwy rozsiewacza |

Płytka Ster składa 0x380 + 0x381 w PGN 161 (bit0 „łączność” = ramki z płytki Maszyna w ostatnich 0,5 s).

## Bezpieczeństwo

- Brak polecenia (PGN 160 / CAN 0x300) przez **0,5 s** → płytka Maszyna zamyka zawór główny i wszystkie sekcje, zawór regulacyjny zostaje w miejscu, flaga „zabezpieczenie”.
- Zawór 2‑pin zasilany tylko przez ustawiony czas po zmianie stanu (zawory Arag mają wyłączniki krańcowe, ale nie trzymamy prądu bez potrzeby).
- Każde wyjście z diagnostyką przerwy i zwarcia → alarm w Navigatorze.
