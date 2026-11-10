# Samouczek: łączenie SmartSteer Navigator z płytką AIO (Windows)

Ten sam samouczek jest w aplikacji: ekran **Połączenia → Samouczek łączenia** (z automatycznym sprawdzaniem kroków ✓).

1. **Płytka i kabel.** Płytka AIO zasilona z ciągnika (12 V), kabel sieciowy z płytki do komputera
   (albo do routera w kabinie, do którego podłączony jest komputer).
2. **Adres komputera: 192.168.5.10.** Ustawienia → Sieć i Internet → Ethernet → „Przypisanie adresu IP” → Edytuj → Ręczne → IPv4:
   adres `192.168.5.10`, długość prefiksu `24` (maska 255.255.255.0), brama i DNS puste.
   Każdy ciągnik ma własną sieć, więc ten sam adres może wpisać każdy. Drugi komputer w tej samej kabinie: `192.168.5.11`.
   Moduły płytki mają adresy powyżej 100 (np. moduł skrętu `.126`).
3. **AgIO zamknięte, tryb SPRZĘT.** AgIO i AgOpenGPS zajmują port 9999 — muszą być wyłączone.
   W aplikacji: Połączenia → **SPRZĘT**.
4. **Zapora Windows.** Przy pierwszym uruchomieniu zaznacz **prywatne i publiczne**.
   Później: Zabezpieczenia Windows → Zapora i ochrona sieci → Zezwalaj aplikacji przez zaporę → SmartSteer Navigator (obie kolumny).
5. **GPS.** W zakładce „Co przychodzi” lecą zdania `$GNGGA`/`$GNVTG` albo `$PAOGI`.
   Odbiornik podłączony kablem USB prosto do komputera (bez płytki) nie jest jeszcze obsługiwany.
6. **RTK FIX.** Poprawki z radia/bazy albo przez internet: Ustawienia → NTRIP.
7. **Moduł skrętu.** Na ekranie widać kąt kół. Ustawienia płytki: Ustawienia → Płytka → „Wyślij do płytki”.
8. **Test silnika na postoju.** Ustawienia → Moduł skrętu → „Włącz test”: ▶ = koła w prawo.
   Odwrotnie → Płytka → „Odwróć kierunek silnika”. Kąt rośnie odwrotnie → „Odwróć czujnik kąta kół”. Koła prosto → „Wyzeruj”.
9. **Wymiary.** Ustawienia → Ciągnik i Maszyna. Potem: Pola → nowe pole, Mapa → A i B, autoster na wolnej prędkości.

## Płytka w innej sieci niż 192.168.5.x

Połączenia → **Szukaj modułów** pokazuje adres każdego modułu. Jeśli płytka była przestawiona w AgIO na inną sieć:
- albo **„Ustaw aplikację na sieć modułów”** (i komputerowi nadaj adres z tej sieci, np. `192.168.1.10`),
- albo wpisz `192.168.5` i **„Przestaw moduły na 192.168.5.x”**, potem wyłącz i włącz płytkę.
