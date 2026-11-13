# Wymiana danych z WAR (Wirtualny Asystent Rolnika)

Dwie drogi, obie zostają:

1. **Pliki** — działa bez internetu i bez konta (opis na końcu: KML / ISOXML do importu, raport GeoJSON).
2. **API WAR przez internet** — połączenie kodem nawigacji z telefonu, tak jak robi to WAR Sync przy AgOpenGPS.
   Navigator rozmawia z serwerem WAR tylko przez HTTPS i ten opis. **Żadnego kodu WAR ani WAR Sync tu nie przenosimy**
   (WAR jest zamknięty, Navigator na GPLv3) — protokół jest opisany niżej i implementujemy go od nowa w Kotlinie.

Serwer: `https://www.agro.smartsteer.eu` (ustawialny — Ustawienia → WAR). Wszystkie wywołania: JSON, UTF‑8,
nagłówek `User-Agent: SmartSteer-Navigator/<wersja>`. Bez logowania i bez tokenów — dostęp daje znajomość kodu.

## 1. Kod nawigacji i szyfrowanie

Rolnik przepisuje z telefonu (WAR → Gospodarstwo → Maszyny → WAR Sync) **kod nawigacji**: 16 znaków z alfabetu
`ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (bez I, O, 0, 1), pokazywany w grupach po 4 z myślnikami.

- **Normalizacja**: wielkie litery, usuń wszystko poza A–Z i 0–9 (`ABCD-EFGH-…` → `ABCDEFGH…`). Poprawny = 16 znaków z alfabetu.
- **Klucz**: `PBKDF2-HMAC-SHA256(hasło = kod (UTF‑8), sól = "war-sync-v1", 150 000 iteracji) → 32 bajty`, AES‑256‑GCM.
  Liczyć raz i trzymać w pamięci (150 000 iteracji to ok. 0,1–0,5 s na tablecie).
- **Paczka** (`dane`): `base64( iv[12 losowych bajtów] + szyfrogram + tag[16] )` — tag na końcu, bez AAD.
  Treść przed szyfrowaniem: JSON (UTF‑8).
- **Adresy skrzynek** (hex z SHA‑256 tekstu UTF‑8, małe litery, 64 znaki):

| Skrzynka | Tekst do SHA‑256 | Kto pisze | Kto czyta |
|---|---|---|---|
| prac | `war-sync-id:` + kod | Navigator (i WAR Sync) | telefon WAR |
| Navigatora | `war-nav-zl:` + kod | telefon WAR | **Navigator** |
| Navigatora — własna | `war-nav-zl:` + kod + `:` + id maszyny | telefon WAR (od v246, gdy `urzadzenie` ma `"skrzynka_wlasna": true`) | **ten jeden** Navigator (czyta też wspólną) |
| floty | `war-flota:` + kod | każdy Navigator gospodarstwa (nadpisuje swoje) | każdy Navigator gospodarstwa (3.10) |
| (zleceń WAR Sync) | `war-sync-zl:` + kod | telefon WAR | WAR Sync — **Navigator jej nie czyta** (WAR Sync usuwa z niej wszystko, co odczyta) |

Serwer zna tylko adresy i szyfrogramy — nie zna pól, położenia ani treści.

Test wektora (do testów jednostkowych): kod `ABCD-EFGH-JKLM-NPQR` → adres prac
`SHA-256("war-sync-id:ABCDEFGHJKLMNPQR")`, adres Navigatora `SHA-256("war-nav-zl:ABCDEFGHJKLMNPQR")`.
Zgodność szyfrowania sprawdzić paczką odszyfrowaną w WAR (np. pozycja widoczna na mapie „Na żywo”).

## 2. Wywołania API

Kody odpowiedzi: `200` ok · `400` zły adres/paczka (tekst w `detail`) · `402`/`403` z tekstem `NIEAKTYWNY:` —
kod nie należy do konta WAR z modułem **WAR Sync** (pokazać rolnikowi treść z `detail`) · `429` za dużo (zwolnić, ponowić za minutę).

| Co | Wywołanie | Treść | Odpowiedź |
|---|---|---|---|
| Test kodu | `GET /api/sync/prace?skrzynka=<adres prac>&licz=1` | — | `{"czeka": n, "aktywny": true/false}` |
| Wyślij paczkę (praca, urządzenie, status zlecenia) | `POST /api/sync/praca` | `{"skrzynka": <adres prac>, "id": <id>, "dane": <paczka>, "zrodlo": "Navigator"}` | `{"ok": true}` (to samo `id` drugi raz → `"duplikat": true`) |
| Pozycja na żywo | `POST /api/sync/pozycja` | `{"skrzynka": <adres prac>, "maszyna": <id maszyny>, "dane": <paczka pozycji>}` | `{"ok": true}` |
| Odbierz pola i zlecenia | `GET /api/sync/zlecenia?skrzynka=<adres Navigatora>` | — | `{"zlecenia": [{"id", "dane", "ts"}, …]}` (do 100 naraz, najstarsze pierwsze) |
| Potwierdź odbiór | `POST /api/sync/zlecenia/odebrane` | `{"skrzynka": <adres Navigatora>, "ids": [<id>, …]}` | `{"usuniete": n}` |
| Flota: zapisz pozycję / pokrycie | `POST /api/sync/flota` | `{"skrzynka": <adres floty>, "skrzynka_prac": <adres prac>, "maszyna": <id maszyny>, "rodzaj": "pozycja"\|"pokrycie", "dane": <paczka>}` | `{"ok": true, "ts"}` · `429` za często |
| Flota: odczytaj innych | `GET /api/sync/flota?skrzynka=<adres floty>&maszyna=<id maszyny>&od=<ts>` | — | `{"maszyny": [{"maszyna", "rodzaj", "dane", "ts"}, …], "ts": <do następnego od>}` |

- `id` paczki: 4–64 znaki `[A-Za-z0-9_-]`, unikalne (np. `nav-<maszyna>-<czas ms>`). Ponowienie z tym samym `id` po zerwanym
  połączeniu jest bezpieczne. Paczka po zaszyfrowaniu do 600 kB.
- Skrzynka Navigatora zaczyna działać dopiero, gdy telefon coś do niej wyśle (przypisuje ją wtedy do konta). Wcześniej
  `GET /api/sync/zlecenia` odpowiada `403 NIEAKTYWNY` — w Navigatorze pokazać „Czekam na WAR — otwórz WAR na telefonie”
  i pytać dalej co kilka minut (nie jako błąd kodu, jeśli test kodu dał `aktywny: true`).
- Odbiór: odszyfrować → zapisać u siebie → **dopiero potem** potwierdzić `ids`. Paczka, której nie da się odszyfrować
  (inny kod), zostaje nietknięta (nie potwierdzać).
- Bez internetu: kolejka na dysku, ponawianie co kilka minut (jak raport GeoJSON — zapis lokalny i tak zostaje).
- `id maszyny` (`maszyna`): stały identyfikator tabletu/ciągnika, 4–64 znaki `[A-Za-z0-9_-]`, losowany raz i zapisany w ustawieniach.

## 3. Paczki (JSON przed szyfrowaniem)

Czas zawsze ISO 8601 z strefą (`2026-11-10T08:12:00+01:00` albo `…Z`). Współrzędne WGS84, w GeoJSON `[długość, szerokość]`.

### 3.1 Navigator → WAR: zgłoszenie urządzenia (`urzadzenie`) — do skrzynki prac

Wysłać po wpisaniu kodu, po każdym starcie (najwyżej raz na dobę) i potem co 7 dni. **Dopiero po tej paczce telefon
zaczyna wysyłać pola i zlecenia do skrzynki Navigatora** (zapamiętuje urządzenie na 60 dni).

```json
{"typ": "urzadzenie", "v": 1, "program": "navigator", "maszyna": "tab-7f3k2", "nazwa": "Renault 133.14",
 "wersja": "0.1.30", "t": "2026-11-10T08:00:00+01:00"}
```

### 3.2 WAR → Navigator: pola gospodarstwa (`pola_war`) — ze skrzynki Navigatora

Telefon wysyła całą listę pól z granicami, gdy coś się zmieni (dzielona na części po 40 pól; wszystkie części mają ten sam `skrot`).
Navigator składa części o tym samym `skrot` i dopiero komplet (`czesc` 1…`czesci`) zastępuje listę pól z WAR.

```json
{"typ": "pola_war", "v": 1, "skrot": "a1b2c3d4e5f60718", "czesc": 1, "czesci": 1, "t": "…",
 "pola": [{"id": "p1", "nazwa": "Za stodołą", "ha": 6.0, "uprawa": "Pszenica ozima",
           "granica": [[19.468, 52.176], [19.471, 52.176], [19.471, 52.1787], [19.468, 52.1787], [19.468, 52.176]],
           "dziury": [], "czesci": 1, "srodek": {"lat": 52.1773, "lon": 19.4695}}]}
```

- `granica`: pierścień zewnętrzny (zamknięty), działki ewidencyjne pola już scalone w jeden obrys; `dziury`: pierścienie wewnętrzne.
- `czesci` > 1: pole w WAR ma kilka rozłącznych kawałków — przyszedł największy (pokazać „pole ma N części”).
- `id` pola WAR zapamiętać przy polu w Navigatorze (`war_id`) — wraca w raporcie pracy, po nim WAR dopasuje wpis.
- Pola w Navigatorze z WAR: oznaczone „z WAR”, granica tylko do odczytu (własne zmiany = kopia pola), uwrocie i linie — Navigatora.

### 3.3 WAR → Navigator: zlecenie (`zlecenie`) — ze skrzynki Navigatora

```json
{"typ": "zlecenie", "v": 1, "id": "zl1abc", "status": "nowe", "rodzaj": "oprysk", "rodzaj_nazwa": "Oprysk",
 "produkt": "Axial 50 EC", "dawka": 0.9, "jedn": "l/ha", "ciecz": 200, "termin": "2026-11-12", "dla": "Marek", "od": "Michał",
 "uwagi": "od strony lasu", "pole": {"id": "p1", "nazwa": "Za stodołą", "ha": 6.0, "uprawa": "Pszenica ozima",
 "granica": [[…]], "dziury": [], "czesci": 1, "srodek": {"lat": …, "lon": …}}}
```

- `rodzaj`: `siew`, `uprawa`, `oprysk`, `nawoz`, `dolistny`, `inne`. `status`: `nowe`, `zrobione`, `anulowane`.
  To samo `id` może przyjść ponownie po zmianie w WAR — nadpisać; `anulowane` → zamknąć u siebie.
- Na Mapie: „Nowe zlecenie: Oprysk — Za stodołą” → **Przyjmij** (otwiera / zakłada pole z granicą zlecenia) albo **Odrzuć**.

### 3.4 Navigator → WAR: status zlecenia (`zlecenie_status`) — do skrzynki prac

```json
{"typ": "zlecenie_status", "v": 1, "zlecenie_id": "zl1abc", "stan": "przyjete", "kto": "Marek", "komputer": "tab-7f3k2",
 "t": "2026-11-10T08:15:00.000Z"}
```
`stan`: `przyjete`, `odrzucone`, `zrobione` (jak WAR Sync). WAR pokazuje go przy zleceniu na telefonie.

### 3.5 Navigator → WAR: praca w polu (bez `typ`) — do skrzynki prac

Wysłać przy zamknięciu pola (razem z raportem GeoJSON), gdy obrobiono co najmniej 0,05 ha. Telefon robi z tego wpis w karcie
pola (siew, uprawa) albo prosi o uzupełnienie (oprysk, nawożenie — produkt i dawka). Rodzaj zabiegu WAR przypisuje do nazwy
narzędzia (rolnik raz wybiera na telefonie: „Siewnik 3 m” = siew itd.), więc nazwa narzędzia ma być stała dla tej maszyny.

```json
{"v": 1, "id": "nav-tab-7f3k2-1762760400000", "zrodlo": "SmartSteer Navigator", "war_sync": "0.1.30", "komputer": "tab-7f3k2",
 "pole": {"nazwa": "Za stodołą", "folder": "Za stodołą", "war_id": "p1"},
 "narzedzie": {"nazwa": "Siewnik 3 m", "szerokosc": 3.0}, "pojazd": "Renault 133.14",
 "start": "2026-11-10T08:12:00+01:00", "koniec": "2026-11-10T10:40:00+01:00",
 "ha": 5.82, "ha_przejazdow": 6.10, "ha_pola": 6.0,
 "punkt": {"lat": 52.1773, "lon": 19.4695},
 "granica": {"type": "Polygon", "coordinates": [[[19.468, 52.176], …]]},
 "zlecenie": "zl1abc", "kto": "Marek",
 "pokrycie": {"lat0": 52.1773, "lon0": 19.4695, "e0": -105.0, "n0": -150.0, "k": 2.0, "w": 106, "h": 151, "b": "<base64>"}}
```

- `ha` — obrobione bez zakładek (mapa pokrycia), `ha_przejazdow` — suma pasów sekcji (z zakładkami), `ha_pola` — z granicy.
- `pole.war_id` — gdy pole przyszło z WAR (najpewniejsze dopasowanie); bez niego WAR szuka po `punkt` i nazwie.
- `zlecenie` — id zlecenia, jeśli praca była z przyjętego zlecenia (WAR odhaczy zlecenie jako zrobione).
- `pokrycie` (zalecane): mapa obrobionych komórek — po niej WAR rozdziela pracę na kilka pól (np. oprysk przez trzy działki naraz).
- `oprysk` (opcjonalne, od 2026-11-12): gdy pracował komputer opryskiwacza Navigatora — `{"ciecz": "RSM 28 %", "ciecz_l": 300.0, "dawka_lha": 150.0, "dawka_zadana_lha": 150, "dawka_kgha": 192.0, "dysza": "03 niebieska", "rodzaj_dyszy": "Płaskostrumieniowa"}` (`dawka_kgha` tylko dla RSM). WAR może z tego uzupełnić ilość cieczy w ewidencji zabiegu; nieznane pole można zignorować. To samo pole trafia do raportu GeoJSON (`properties.oprysk`).
  Układ jak `LocalPlane` w AgOpenGPS: punkt odniesienia `lat0/lon0`; północ = (lat − lat0) · mLat(lat0), wschód = (lon − lon0) · mLon(lat);
  `mLat(φ) = 111132.92 − 559.82·cos 2φ + 1.175·cos 4φ − 0.0023·cos 6φ`, `mLon(φ) = 111412.84·cos φ − 93.5·cos 3φ + 0.118·cos 5φ` (m/°).
  Siatka: lewy dolny róg `e0, n0` (m), bok komórki `k` (m, ok. 2), `w × h` komórek; bit nr `y·w + x` (bajt `>>3`, bit `&7`, LSB pierwszy)
  = komórka obrobiona.

**`zabieg`** (tylko WAR Navigator, opcjonalnie) — odpowiedź na pytanie „Co robiłeś na polu?” przy zamknięciu pola
(Ustawienia → WAR, domyślnie włączone; bez odpowiedzi przez 12 h praca idzie bez `zabieg`):

```json
"zabieg": {"rodzaj": "oprysk", "produkt": "Mospilan 20 SP", "nr": "R-12/2020", "dawka": 0.2, "jedn": "kg/ha"}
```

`rodzaj`: oprysk, nawoz, dolistny, siew, uprawa, inne; przy siewie `produkt` = odmiana i `uprawa`; przy mapie dawek
z WAR `zmienna_dawka: true` (`dawka` = strefa typowa). WAR wypełnia tym wpis do ewidencji zabiegów albo karty pola
(rolnik zatwierdza na telefonie); rodzaj z `zabieg` wystarcza, gdy narzędzie nie ma jeszcze przypisanego rodzaju.

### 3.6 Navigator → WAR: pozycja na żywo

Co 5 s w pracy, co 30 s na postoju, tylko przy żywym GPS (i gdy rolnik nie wyłączył „Pokazuj na mapie WAR”):

```json
{"typ": "pozycja", "v": 1, "lat": 52.1773124, "lon": 19.4695011, "kurs": 87.5, "kmh": 8.4, "fix": 4, "sat": 18,
 "narzedzie": "Siewnik 3 m", "szer": 3.0, "pole": "Za stodołą", "pracuje": true, "kto": "Marek", "komputer": "tab-7f3k2",
 "t": "2026-11-10T08:30:05Z"}
```
`fix`: jakość GGA (1 GPS, 2 DGPS, 4 RTK FIX, 5 RTK FLOAT). `pracuje` = autoster albo sekcje włączone. WAR pokazuje maszynę
na mapie „Na żywo” (Gospodarstwo → Maszyny) pod nazwą `komputer`.

### 3.7 WAR → Navigator: produkty gospodarstwa (`produkty_war`) — ze skrzynki Navigatora

Podpowiedzi do pytania „Co robiłeś?”: środki (ewidencja z 2 lat i magazyn), nawozy, odmiany — najpierw ostatnio używane,
do 200 w każdej liście. Wysyłane, gdy się zmienią.

```json
{"typ": "produkty_war", "v": 1, "skrot": "…", "t": "…",
 "srodki": [{"n": "Mospilan 20 SP", "nr": "R-12/2020", "d": 0.2, "j": "kg/ha", "r": "sor"}],
 "nawozy": [{"n": "Saletra amonowa 34", "d": 150, "j": "kg/ha", "r": "nawoz"}],
 "odmiany": [{"n": "Adrenalina", "u": "pszenica ozima", "d": 180, "j": "kg/ha"}]}
```

`n` nazwa, `nr` nr zezwolenia, `d` ostatnia dawka, `j` jednostka, `r` rodzaj (sor, dolistny, mikro, nawoz), `u` uprawa.

### 3.8 WAR → Navigator: mapa zmiennych dawek (`mapa_dawek`) — ze skrzynki Navigatora

Telefon: pole → NDVI → Strefy → dawki dla trzech stref → **„Wyślij do nawigacji (WAR Navigator)”**. Strefy ze zdjęcia
Sentinel-2 jako siatka oczek ~10 m (ta sama co w pliku ISO-XML dla terminali ISOBUS):

```json
{"typ": "mapa_dawek", "v": 1, "id": "md-p1", "pole": {"war_id": "p1", "nazwa": "Za stodołą"}, "nazwa": "Za stodołą",
 "produkt": "Saletra amonowa 34", "rodzaj": "nawoz_kg", "rodzaj_nazwa": "Nawóz", "data": "2026-05-06", "t": "…",
 "jednostka": "kg/ha", "strefy": {"1": 115, "2": 100, "3": 85},
 "siatka": {"min_n": 52.0, "min_e": 22.0, "dn": 0.0001, "de": 0.00015, "kol": 120, "rz": 90,
            "wiersze": ["0000222211…", "…"]},
 "ha_strefy": {"slabe": 2.1, "srednie": 6.3, "mocne": 1.4}, "razem": 1015.5}
```

- `wiersze` — od południa do północy, w wierszu od zachodu; znak = kod strefy oczka: `0` poza polem, `1` słabsza,
  `2` typowa, `3` mocniejsza. `strefy` — dawka dla kodu w `jednostka` (kg/ha, l/ha, szt./m²).
- Oczko wiersza `r`, kolumny `c`: szerokość `min_n + r·dn … min_n + (r+1)·dn`, długość `min_e + c·de … min_e + (c+1)·de` (WGS84).
- Navigator zapisuje ostatnią mapę dla `pole.war_id` (nowsza `t` wygrywa) i wczytuje ją do pola o tym `war_id`:
  od razu, gdy pole jest otwarte, inaczej przy otwarciu. Mapy z pliku (GeoJSON/KML) nie nadpisuje — wtedy przycisk
  „Wczytaj mapę z WAR” w Pola → Mapa dawek. Poza oczkami (brzeg pola) — dawka typowa.

### 3.9 Wspólna praca kilku maszyn (PROPOZYCJA — po stronie WAR do zrobienia)

> **Nieaktualne — zastąpione przez 3.10 (skrzynka floty).** WAR nie przekazuje paczek `pokrycie_na_zywo`.

Navigator (opcja „Wspólna praca kilku maszyn na polu” w Ustawienia → WAR, domyślnie wyłączona) co minutę, gdy otwarte
jest pole z `war_id` i przybyło pokrycia, wysyła do skrzynki prac:

```json
{"typ": "pokrycie_na_zywo", "v": 1, "id": "pk-<komputer>-<ms>", "komputer": "tab-7f3k2", "pojazd": "Renault 133.14",
 "pole": {"war_id": "p1", "nazwa": "Za stodołą"}, "t": "2026-11-11T10:20:00+01:00",
 "pokrycie": {"lat0": …, "lon0": …, "e0": …, "n0": …, "k": 2.0, "w": …, "h": …, "b": "<base64>"}}
```

(`pokrycie` jak w 3.5 — całe dotychczasowe pokrycie tej maszyny na polu). WAR przekazuje najnowszą taką paczkę każdej maszyny
pozostałym Navigatorom gospodarstwa (do ich skrzynek), jako:

```json
{"typ": "pokrycie_innych", "v": 1, "komputer": "tab-7f3k2", "pojazd": "Renault 133.14", "pole": {"war_id": "p1"}, "pokrycie": {…}}
```

Navigator z otwartym polem o tym `war_id` rysuje pracę innej maszyny na niebiesko, a sekcje omijają ją jak własne pokrycie.
Paczki z własnym `komputer` są pomijane.

### 3.10 Flota: pozycje i pokrycie innych maszyn (`pozycja_floty`, `pokrycie_floty`) — skrzynka floty

Po stronie WAR **gotowe** (zastępuje 3.9). Wszystkie maszyny gospodarstwa mają ten sam kod nawigacji, więc **nie ma osobnego
klucza floty**: skrzynka floty = `SHA-256("war-flota:" + kod)`, szyfrowanie tym samym kluczem co reszta (1). Telefon nic
nie rozsyła — Navigatory wymieniają się przez serwer same.

- **Zapis** `POST /api/sync/flota` z `skrzynka_prac` (adres prac tego samego kodu): serwer sprawdza, że kod należy do konta
  z modułem WAR Sync, i przy pierwszym zapisie przypisuje skrzynkę floty do tego konta (wcześniej odczyt → `403 NIEAKTYWNY`).
- Serwer trzyma **tylko ostatnią** paczkę każdego `rodzaj` dla każdej `maszyna` (nadpisuje). `pozycja` do 4 000 znaków,
  najczęściej co 2 s; `pokrycie` do 600 kB, najczęściej co 30 s (częściej → `429`, nie błąd — następna paczka przejdzie).
- **Odczyt** `GET /api/sync/flota`: tylko paczki nowsze niż `od`; jako `od` podawać **`ts` z poprzedniej odpowiedzi** (zegar
  serwera, np. `2026-11-13T08:30:05.123+00:00` — zakodować w adresie, `+` → `%2B`). Pierwszy odczyt bez `od` — wszystko.
  Pozycje starsze niż 15 min i pokrycia starsze niż 7 dni nie wracają. Najczęściej co 3 s na maszynę (`maszyna` w adresie
  rozróżnia kilka tabletów za jednym hotspotem), inaczej `429`. Zalecane: co 5 s w pracy, co 30 s na postoju.
- Własne paczki (ta sama `maszyna`) też wracają — pomijać.
- Odpowiedź zawiera też `lokalizatory` (gdy konto ma WAR Track): `[{"id": "trk-…", "nazwa", "lat", "lon", "kmh", "kurs", "zaplon", "ts"}]` —
  maszyny bez Navigatora, jawnie (jak na mapie w WAR), za każdym razem wszystkie z ostatnich 15 min.
- Zlecenie (`zlecenie`) może mieć `"przyjal": {"komputer", "kto", "t"}` — przyjęte na innej maszynie (Navigator albo WAR Sync);
  pozostałe go nie pokazują, żeby nikt nie zrobił tej samej pracy drugi raz.
- `kolor` (opcjonalnie, „#RRGGBB”, w `pozycja_floty` i `pokrycie_floty`): kolor śladu maszyny z Navigatora (Ustawienia →
  Pojazd → Kolor śladu) — inne Navigatory rysują nim jej trójkąt i obrobione pasy. Brak = domyślny niebieski.

```json
{"typ": "pozycja_floty", "v": 1, "komputer": "tab-7f3k2", "pojazd": "Renault 133.14", "narzedzie": "Opryskiwacz 21 m",
 "szer": 21.0, "lat": 52.1773124, "lon": 19.4695011, "kurs": 87.5, "kmh": 8.4, "fix": 4,
 "pole": {"war_id": "p1", "nazwa": "Za stodołą"}, "pracuje": true, "kto": "Marek", "t": "2026-11-13T08:30:05+01:00"}
```

```json
{"typ": "pokrycie_floty", "v": 1, "komputer": "tab-7f3k2", "pojazd": "Renault 133.14", "pole": {"war_id": "p1"}, "t": "…",
 "pokrycie": {"lat0": …, "lon0": …, "e0": …, "n0": …, "k": 2.0, "w": …, "h": …, "b": "<base64>"}}
```

Wektor testowy (kod `ABCD-EFGH-JKLM-NPQR`):
- adres floty: `5c9804e7df32aa3c7bc85063c111ba510b553a6618f3f064537a10758e7fa19e`
- iv = bajty 0…11, treść `{"typ":"pozycja_floty","v":1,"komputer":"tab-test","lat":52.1,"lon":19.4}` →
  `AAECAwQFBgcICQoL8EqYwnX6Gwx1p4X0JyfGTHtWu1+ODHhYZ0xivEQOkHSxVDuhOlFoHkZ9L14XeFDXFQ1C7xTyTTZJfT1/Hg/TvutWNxqU4ISRyFlAlLZ1fYwgOJ4a1DEiTe4=`

## 4. Pliki (bez internetu)

### Z WAR do Navigatora: pola, granice, linie

Folder importu: `Dokumenty\SmartSteer\Import` (ekran Pola → „Otwórz folder importu”).

- **KML** (`*.kml`): pierwszy wielokąt = granica pola, linia z 2 punktów = linia AB, dłuższa linia = krzywa.
  Nazwa pola = nazwa dokumentu KML (albo nazwa granicy, albo nazwa pliku). W WAR: karta pola → „🚜 Do AgOpenGPS”
  (działki pola są w nim scalone w jeden obrys).
- **ISOXML** (`TASKDATA.XML` albo cały folder `TASKDATA`): każde pole `PFD` → granica z `PLN` typu 1,
  linie prowadzenia z `LSG` typu 5 (2 punkty = AB, więcej = krzywa). Nazwa pola z atrybutu `C`.

Ekran Pola → lista plików → „Wczytaj”. Pole trafia na listę zapisanych pól (przy tej samej nazwie dostaje „(2)”).

### Z Navigatora do WAR: raport zabiegu

Folder raportów: `Dokumenty\SmartSteer\Raporty`. Raport zapisuje się sam przy zamykaniu pola (gdy coś obrobiono)
albo przyciskiem „Zapisz raport teraz”. Plik: `<pole> <rrrr-mm-dd ggmm>.geojson`.

GeoJSON (`FeatureCollection`, współrzędne WGS84 `[długość, szerokość]`):
- `properties`: `program`, `pole`, `start`, `koniec`, `obrobiono_ha` (bez liczenia zakładek), `powierzchnia_pola_ha`,
  `profil` (profil pojazdu), `szerokosc_m`, `sekcje`;
- `features`: `Polygon` z `"rodzaj": "granica"` i wielokąty `"rodzaj": "pokrycie"` (obrobione pasy sekcji).
