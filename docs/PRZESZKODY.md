# Czujnik przeszkód

Zabezpieczenie pod jazdę bez kierowcy. Kamera z pomiarem odległości (np. **Luxonis OAK-D Pro W PoE**, 150°, IP65)
przez mały program pośredni mówi Navigatorowi, co i gdzie jest przed maszyną. **Decyduje Navigator w kabinie** —
nie WAR (internet ma sekundowe opóźnienia i przerwy). WAR dostaje tylko zdarzenie „zatrzymano — przeszkoda”.

```
OAK-D ──PoE──> komputer w kabinie: tools/przeszkody/oak_przeszkody.py ──UDP 17777──> Navigator
                                                                                     │ strefa stopu
                                                                                     ├─ autoster WYŁ. + alarm + czerwony pas na mapie
                                                                                     ├─ PGN 225 STOP ──> moduł bezpieczeństwa (przekaźnik)
                                                                                     └─ „zdarzenie” ──> WAR (gospodarz)
```

**Autoster tylko skręca kołami — sam nie zatrzyma ciągnika.** Do zatrzymania potrzebny jest moduł z przekaźnikiem,
który reaguje na PGN 225 (niżej). Bez niego czujnik wyłącza autoster i alarmuje kierowcę.

## Ustawienia (Navigator → Ustawienia → Nadzór autosteru → Czujnik przeszkód)

- **Strefa stopu**: prostokąt przed maszyną. Szerokość = szersze z (rozstaw kół + 0,5 m) i szerokości narzędzia,
  plus zapas z każdej strony (0,7 m). Długość = droga w czasie reakcji + droga hamowania + 1 m, nie mniej niż minimum:
  `v·t_reakcji + v²/(2·hamowanie) + 1 m` (domyślnie 0,8 s i 2 m/s², minimum 4 m; przy 10 km/h ≈ 5,2 m).
- **Uwaga**: przeszkoda w strefie ×2 — pomarańczowy pas i piknięcie.
- **STOP**: przeszkoda w strefie — autoster się wyłącza (i nie da się go włączyć), alarm, czerwony pas, ramka STOP.
- **Czujnik martwy** (brak danych > 1 s albo `"ok": false`) = STOP. Bez działającej kamery nie wolno prowadzić.
- Wykrycia z pewnością poniżej progu (50%) są pomijane.
- „Próba: przeszkoda 3 m” — sprawdzenie alarmu bez kamery.

## Protokół: program pośredni → Navigator

UDP, domyślnie port **17777** (na wszystkich kartach — program może działać na innym komputerze w kabinie),
~10 razy na sekundę, JSON w UTF-8:

```json
{"v": 1, "zrodlo": "oak-przod", "ok": true, "t": 1794409521.95,
 "przeszkody": [{"z": 6.2, "x": -0.4, "klasa": "osoba", "p": 0.83}]}
```

- `z` — metry przed **przodem maszyny** (≥ 0), `x` — metry w prawo (+) / w lewo (−) od osi maszyny,
- `klasa` — co to jest (osoba, pies, samochód, obiekt…), pokazywane rolnikowi, `p` — pewność 0–1,
- `ok` — czujnik działa (false: zasłonięta/brudna kamera, błąd) → Navigator traktuje jak brak czujnika,
- pusta lista `przeszkody` = czysto (ale paczki muszą przychodzić — cisza = czujnik martwy).

Każda inna kamera albo radar może nadawać to samo.

## PGN 225 — moduł bezpieczeństwa (Navigator → moduły, co 100 ms gdy czujnik włączony)

Ramka AgOpenGPS (0x80 0x81, źródło 0x7F, PGN 0xE1, 8 bajtów danych, CRC):

| bajt | znaczenie |
|---|---|
| 0 | 0 = czysto, 1 = uwaga, 2 = STOP |
| 1–2 | odległość do najbliższej przeszkody w strefie × 10 (dm, LE), 0xFFFF = brak |
| 3 | 1 = czujnik żyje |
| 4–7 | 0 |

Moduł (np. ESP32 z przekaźnikiem na tej samej sieci co płytka AIO) powinien zatrzymać maszynę przy **STOP**
oraz gdy **przez 0,5 s nie dostanie żadnej ramki** (zerwany kabel, zawieszony program) — zasada „brak sygnału = stop”.

## Program pośredni dla OAK-D (`tools/przeszkody/oak_przeszkody.py`)

```
pip install "depthai<3" blobconverter
python3 tools/przeszkody/oak_przeszkody.py --przod 2.5        # kamera na dachu kabiny, 2,5 m za przodem maszyny
python3 tools/przeszkody/oak_przeszkody.py --symulacja        # bez kamery: „osoba” zbliża się co 30 s
```

- sieć MobileNet-SSD na kamerze: ludzie, zwierzęta, pojazdy (z odległością z pomiaru stereo),
- siatka głębi (`--kolumny 8`, `--pas 0.35 0.6`): najbliższy punkt w kolumnach pasa obrazu — łapie słupy, bele, ściany,
  których sieć nie zna. Pas trzeba ustawić tak, żeby nie widział ziemi tuż przed maszyną (inaczej fałszywy STOP).
- **Nie sprawdzone na kamerze.** Przed pracą: próba na placu — osoba przed maszyną, słup, przejazd obok płotu,
  zasłonięta kamera (musi dać STOP), wyjęty kabel (STOP po 1 s).

## WAR

Przy STOP przez przeszkodę Navigator wysyła do skrzynki prac paczkę:

```json
{"typ": "zdarzenie", "v": 1, "id": "ev-tab-7f3k2-…", "rodzaj": "przeszkoda", "tekst": "STOP — osoba 3,4 m przed maszyną",
 "komputer": "tab-7f3k2", "pojazd": "Renault 133.14", "pole": "Za stodołą", "t": "…", "lat": 52.1, "lon": 19.4, "kmh": 6.2}
```

(WAR — alarm u gospodarza; do zrobienia po stronie WAR.)
