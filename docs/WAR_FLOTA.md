# Inne maszyny na mapie Navigatora (flota gospodarstwa) — checklista dla WAR

> **Stan (WAR, 13.11.2026): strona WAR gotowa — ostateczny opis w docs/WAR.md 3.10.** Zmiany względem propozycji niżej:
> - **bez klucza floty**: gospodarstwo ma JEDEN kod nawigacji dla wszystkich maszyn (WAR → Maszyny → WAR Sync), więc adres
>   skrzynki floty = `SHA-256("war-flota:" + kod)`, szyfrowanie kluczem z kodu. Paczka `flota` z kluczem i przycisk
>   „Nowy klucz floty” niepotrzebne (zgubiony tablet = nowy kod nawigacji, jak dziś);
> - zapis z `skrzynka_prac` (tak serwer wie, do którego konta należy flota — telefon nic nie musi robić);
> - `od` = `ts` z poprzedniej odpowiedzi (zegar serwera), odczyt z `maszyna` w adresie (limit 3 s na tablet).
>
> **Do poprawy po stronie Navigatora (znalezione przy okazji):** skoro kod jest wspólny, wszystkie Navigatory gospodarstwa
> czytają TĘ SAMĄ skrzynkę `war-nav-zl:` — pierwszy, który potwierdzi odbiór, kasuje paczki (pola, produkty, mapy dawek,
> zlecenia) i drugi Navigator ich nie dostanie. Propozycja: skrzynka każdego Navigatora `war-nav-zl:` + kod + `:` + id maszyny
> (telefon zna id z paczki `urzadzenie`); WAR wyśle wtedy paczki do każdej z nich — do uzgodnienia, nie zrobione.

Cel: każdy Navigator gospodarstwa widzi na mapie **pozycje innych maszyn** (ikonka z kierunkiem, nazwa, narzędzie, prędkość)
i **ich obrobione pasy** na wspólnym polu (sekcje je omijają). Dziś Navigator wysyła pozycję tylko do WAR („Na żywo”),
a pokrycie innych (docs/WAR.md 3.9) jest gotowe po stronie Navigatora, ale WAR go nie rozsyła.

Zasada bez zmian: serwer nie widzi treści (szyfrowanie końcowe), Navigator implementuje protokół sam (GPLv3).

## Pomysł: wspólny klucz floty + skrzynka floty na serwerze

Każdy Navigator ma własny kod nawigacji (własne skrzynki). Pozycje co 5 s nie mogą iść przez telefon (telefon nie działa stale),
więc potrzebna jest **skrzynka floty** na serwerze, którą czytają wszystkie maszyny gospodarstwa:

- **Klucz floty**: 32 losowe bajty generowane raz na gospodarstwo w WAR (telefon / konto), base64.
- **Adres skrzynki floty**: `SHA-256("war-flota:" + klucz_floty_base64)` (hex, małe litery).
- **Szyfrowanie**: AES‑256‑GCM kluczem floty (te same zasady co w docs/WAR.md 1: iv 12 B + szyfrogram + tag 16 B, base64).
- Klucz floty trafia do Navigatora **zaszyfrowany jego kodem**, w paczce do skrzynki Navigatora (np. razem z `pola_war`).

## Checklista — WAR (serwer)

- [ ] Endpoint zapisu: `POST /api/sync/flota` — `{"skrzynka": <adres floty>, "maszyna": <id maszyny>, "rodzaj": "pozycja"|"pokrycie", "dane": <paczka>}`
      → serwer trzyma **tylko ostatnią** paczkę danego rodzaju dla każdej `maszyna` w tej skrzynce (nadpisuje). Pozycja do ~2 kB,
      pokrycie do 600 kB.
- [ ] Endpoint odczytu: `GET /api/sync/flota?skrzynka=<adres floty>&od=<ts>` → `{"maszyny": [{"maszyna", "rodzaj", "dane", "ts"}, …]}` —
      tylko nowsze niż `od` (żeby Navigator co 5 s nie ściągał wszystkiego).
- [ ] Wygasanie: pozycje starsze niż 15 min nie są zwracane; pokrycie trzymać do 7 dni.
- [ ] Limity: zapis pozycji najczęściej co 2 s na maszynę, pokrycia co 30 s; odczyt co 3 s na skrzynkę (inaczej 429).
- [ ] Dostęp jak dziś: tylko konta z modułem WAR Sync (`402/403 NIEAKTYWNY:`); skrzynka floty przypisana do konta przy pierwszym zapisie z telefonu.

## Checklista — WAR (telefon / konto gospodarstwa)

- [ ] Generowanie klucza floty raz na gospodarstwo (przechowywany w koncie, zaszyfrowany jak inne sekrety konta).
- [ ] Przekazanie klucza do każdego Navigatora: nowa paczka do skrzynki Navigatora (szyfrowana kodem tej maszyny):
      `{"typ": "flota", "v": 1, "klucz": "<base64 32 B>", "t": "…"}` — wysyłana po paczce `urzadzenie` i po zmianie klucza.
- [ ] Przycisk „Nowy klucz floty” (np. po zgubieniu tabletu) → nowy klucz do wszystkich aktywnych maszyn.
- [ ] Mapa „Na żywo” w WAR może dalej korzystać z obecnej `POST /api/sync/pozycja` (bez zmian).
- [ ] Opcjonalnie: telefon też czyta skrzynkę floty (pokrycie wszystkich maszyn na mapie pola w WAR).

## Paczki (JSON przed szyfrowaniem kluczem floty)

```json
{"typ": "pozycja_floty", "v": 1, "komputer": "tab-7f3k2", "pojazd": "Renault 133.14", "narzedzie": "Opryskiwacz 21 m",
 "szer": 21.0, "lat": 52.1773124, "lon": 19.4695011, "kurs": 87.5, "kmh": 8.4, "fix": 4,
 "pole": {"war_id": "p1", "nazwa": "Za stodołą"}, "pracuje": true, "kto": "Marek", "t": "2026-11-13T08:30:05+01:00"}
```

```json
{"typ": "pokrycie_floty", "v": 1, "komputer": "tab-7f3k2", "pojazd": "Renault 133.14",
 "pole": {"war_id": "p1"}, "t": "…", "pokrycie": {"lat0": …, "lon0": …, "e0": …, "n0": …, "k": 2.0, "w": …, "h": …, "b": "<base64>"}}
```

(`pokrycie` jak w docs/WAR.md 3.5 — całe pokrycie tej maszyny na tym polu.) Paczki z własnym `komputer` Navigator pomija.

## Checklista — Navigator (zrobię po stronie Navigatora)

- [x] ~~Odbiór paczki `flota` (klucz)~~ — niepotrzebne: wspólny kod gospodarstwa ze skrzynki Navigatora, zapis klucza w ustawieniach WAR.
- [x] Wysyłka `pozycja_floty` co 5 s w pracy / 30 s na postoju (ten sam wyłącznik co „Pokazuj na mapie WAR”).
- [x] Wysyłka `pokrycie_floty` co minutę, gdy pole ma `war_id` i przybyło pokrycia (zamiast propozycji 3.9).
- [x] Odczyt skrzynki floty co 3–5 s: ikonki innych maszyn na Mapie (kolor, kierunek, nazwa, prędkość, „pracuje”),
      znikają po 5 min ciszy; pokrycie innych na niebiesko + omijanie przez sekcje (logika już jest).
- [x] Testy: szyfrowanie kluczem floty, adres skrzynki floty (wektor testowy do uzgodnienia z WAR), pomijanie własnych paczek.

## Do uzgodnienia między sesjami

- Nazwy endpointów i pól (powyżej to propozycja) — po stronie WAR wpisać ostateczne do docs/WAR.md (nowy punkt 3.10).
- Wektor testowy: przykładowy klucz floty → adres skrzynki i jedna zaszyfrowana paczka do sprawdzenia w obu programach.
