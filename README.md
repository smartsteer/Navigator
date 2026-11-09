# SmartSteer Navigator

Aplikacja do prowadzenia równoległego i autosterowania ciągnika, zgodna z płytkami AgOpenGPS (AIO).
Jeden kod w Kotlinie daje dwie wersje:

- **Android** — tablet w kabinie (APK),
- **Windows** — komputer / tablet z Windows (instalator `.msi` / `.exe`).

## Jak to działa

Aplikacja liczy geometrię prowadzenia (linia AB, krzywe, pure pursuit) i co ok. 100 ms wysyła
zadany kąt skrętu do modułu na płytce. Regulację silnika (PID, PWM) i odczyt czujnika kąta kół
robi oryginalny firmware AgOpenGPS na płytce — nie jest zmieniany.

Komunikacja: UDP w sieci `192.168.5.x`, aplikacja nasłuchuje na porcie **9999**, wysyła na **8888**.
Ramka: `0x80 0x81 źródło PGN długość dane… CRC`.

| PGN | Kierunek | Treść |
|-----|----------|-------|
| 254 | aplikacja → moduł skrętu | prędkość, autoster wł./wył., kąt zadany, odległość od linii, sekcje |
| 253 | moduł skrętu → aplikacja | kąt rzeczywisty, kurs/przechył IMU, przełączniki, PWM |
| 252 / 251 | aplikacja → moduł skrętu | ustawienia i konfiguracja sterowania |
| 239 | aplikacja → moduł maszyny | sekcje, podnośnik, zawrót |

### Podłączenie tabletu z Androidem

Przejściówka USB‑C → Ethernet (najlepiej hub z ładowaniem PD) albo Wi‑Fi przez mały router
w kabinie. Router powinien rozdawać adresy (DHCP) w sieci `192.168.5.x`.

## Struktura

```
composeApp/
  src/commonMain/   wspólny kod: logika (core/) i ekrany (Compose Multiplatform)
  src/androidMain/  start aplikacji na Androidzie
  src/desktopMain/  start programu na Windowsie
  src/commonTest/   testy
```

## Budowanie

- Android: `./gradlew :composeApp:assembleDebug`
- Windows (na Windowsie): `./gradlew :composeApp:packageMsi`
- Uruchomienie na komputerze bez instalacji: `./gradlew :composeApp:run`
- Testy: `./gradlew :composeApp:desktopTest`

GitHub Actions budują APK i instalator Windows przy każdym pushu — pliki są w zakładce
**Actions → ostatni przebieg → Artifacts**.

## Plan

1. Rdzeń: odczyt NMEA, linia AB, odchyłka, symulator jazdy
2. Autosterowanie: pure pursuit, PGN 254/253, ekran diagnostyki
3. Sekcje i mapa pokrycia, zawroty, granice pól
4. Integracja z WAR (mapy aplikacyjne, ewidencja zabiegów)

## Licencja

Kod własny SmartSteer, wszelkie prawa zastrzeżone. Projekt nie zawiera kodu AgOpenGPS —
korzysta jedynie z publicznego protokołu komunikacji z płytkami.
