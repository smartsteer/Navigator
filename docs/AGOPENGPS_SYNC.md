# Zmiany z AgOpenGPS — dziennik cotygodniowego sprawdzania

Zaplanowane zadanie „Navigator – zmiany z AgOpenGPS” (poniedziałki rano) sprawdza nowe zmiany w
github.com/AgOpenGPS-Official/AgOpenGPS w plikach, z których przenosiliśmy kod, i:
- bezpieczne poprawki (obliczenia, NMEA, protokół płytek, NTRIP, import pól) przenosi samo, z testem;
- zmiany w sterowaniu, sekcjach, zawrotach i nadzorze tylko opisuje niżej — wchodzą po zgodzie właściciela.

**Ostatnie sprawdzenie: 2026-11-16**

## Czeka na decyzję

(nic)

Żeby zatwierdzić pozycję, dopisz przy niej „ZGODA” (albo napisz do Claude’a) — wejdzie w następnym przebiegu.

## Historia

- 2026-11-13 — uruchomienie zadania.
- 2026-11-16 — sprawdzono 4 zmiany (13–16.11). Przeniesiono: nic. Czeka: nic. Pominięto:
  - GPS_Out (osobny program na Windows do wysyłania pozycji NMEA na zewnątrz; PR #1254, commit 2e367b0) — nie mamy go w Navigatorze;
  - poprawka testu „radio fallback” w AgLibrary.Ntrip.Tests (commit 6b609b1) — zmiana tylko w teście, nie w kodzie NTRIP;
  - 2 commity scalające (2634365, e9e5635) — bez własnej treści.
  - Uwaga: w tym przebiegu nie dało się otworzyć stron commitów (WebFetch odmówił); ocena na podstawie opisów commitów i ścieżek zmienionych plików (wyszukiwarka kodu GitHub).
