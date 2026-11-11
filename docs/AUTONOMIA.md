# Autonomiczny ciągnik — Zetor 12045 (plan, punkt wyjścia dla osobnej sesji)

Analogowy Zetor 12045 (mechaniczna skrzynia, mechaniczna pompa wtryskowa) ma jeździć sam pod WAR Navigatorem.
Musi działać: **kierowanie, podnośnik, sprzęgło, hamulce, gaz, biegi (z ich zmianą), odpalanie i gaszenie**.
Kamery i czujnik przeszkód obsługują razem Navigator (decyzje, w kabinie) i WAR (podgląd, alarmy u gospodarza).

## Co już jest

- Navigator: prowadzenie (AB, krzywe, kontur), auto-zawroty, sekwencja uwrocia (podnieś/opuść w zadanej odległości),
  sekcje, mapy dawek, integracja z WAR.
- **Czujnik przeszkód** (docs/PRZESZKODY.md): strefa stopu wg prędkości i szerokości, STOP = autoster wyłączony +
  blokada + alarm + czerwony pas, martwy czujnik = STOP, **PGN 225** dla modułu bezpieczeństwa, zdarzenie „przeszkoda”
  do WAR (czerwona karta u gospodarza). Program dla OAK-D (`tools/przeszkody/oak_przeszkody.py`, tryb `--symulacja`).
- AgOpenGPS / płytka AIO obsługuje tylko **kierowanie** (PGN 254/253) i **podnośnik** (moduł maszyny, góra/dół).
  Reszta — nasze moduły.

## Architektura

```
Navigator ── sieć 192.168.5.x ──┬── płytka AIO: kierowanie (jest)
                                ├── moduł maszyny: podnośnik góra/dół (jest w AgOpenGPS)
                                ├── moduł jazdy (nowy, ESP32): gaz, sprzęgło, hamulec, biegi, rozrusznik
                                └── moduł bezpieczeństwa (nowy, ESP32): elektrozawór stopu ETR, grzybki, pilot, watchdog
OAK-D (przód) ──> program pośredni ──UDP 17777──> Navigator
```

| Funkcja | Wykonanie | Bez prądu / bez sygnału |
|---|---|---|
| Kierowanie | silnik na kierownicy albo zawór elektrohydrauliczny | luz |
| Podnośnik | siłownik na dźwigni albo zawór elektrohydrauliczny (moduł maszyny AOG) | bez zmian |
| Gaszenie | elektrozawór stopu **ETR** na pompie wtryskowej | **silnik stop** |
| Odpalanie | przekaźnik rozrusznika + zapłonu | — |
| Gaz | siłownik / serwo na ręcznej dźwigni gazu, pętla po prędkości GPS | wolne obroty |
| Sprzęgło | mocny siłownik liniowy 12 V z czujnikiem położenia, sprężyna | **wciśnięte** |
| Hamulec | siłownik na oba pedały spięte | **hamuje** |
| Biegi | robot X-Y: 2 siłowniki z czujnikami na głównej dźwigni (+1 na reduktor), czujniki Halla „bieg wszedł” / „luz”, kalibracja przez ręczne wrzucenie biegów | luz |

Zmiana biegu (moduł jazdy, na rozkaz Navigatora): gaz na wolne → sprzęgło → (bez synchronizatora: stop) → luz
(potwierdzony) → kanał → bieg (potwierdzony; nie wchodzi → luz, sprzęgło na chwilę, druga próba; znowu nie → STOP
i alarm) → sprzęgło z wyczuciem (pętla po prędkości) → gaz.

## Kolejność (od najmniej ryzykownej)

1. Moduł bezpieczeństwa + elektrozawór stopu ETR + grzybki + pilot radiowy; reakcja na PGN 225 i na brak ramki 0,5 s.
2. Gaz i hamulec — stała prędkość z kierowcą w kabinie.
3. Sprzęgło — ruszanie i stawanie.
4. Biegi — robot na dźwigni, najpierw na postoju.
5. Podnośnik na uwrociu (AOG + Navigator już mają logikę).
6. Czujnik przeszkód z przodu (Navigator gotowy), potem z tyłu (jazda wstecz).
7. Tryb „bez kierowcy” w Navigatorze: tylko przy żywym czujniku i module bezpieczeństwa; zamknięte pole, człowiek z pilotem.

## Do zrobienia w Navigatorze

- protokół modułu jazdy (nowe PGN-y jak 224/225: zadana prędkość / gaz / sprzęgło / hamulec / bieg; odpowiedź: położenia,
  bieg potwierdzony, błędy),
- regulator prędkości, ruszanie, stawanie, sekwencja uwrocia z prędkością i biegiem,
- bieg roboczy i na uwrocie w profilu narzędzia,
- ekran stanu modułów i kalibracji (biegi, sprzęgło, hamulec).

## Dane potrzebne od rolnika (pomiary na Zetorze)

- skok i siła na pedale sprzęgła i hamulca (waga łazienkowa),
- jak się gasi silnik (kluczyk / cięgno stop), zdjęcie pompy wtryskowej z dźwignią stopu,
- skrzynia: ile dźwigni (główna, reduktor, rewers, multiplikator?), układ biegów, które z synchronizatorem,
  siła i skok dźwigni, miejsce na siłowniki (kabina czy cięgna pod spodem),
- dźwignia podnośnika: zdjęcie, siła, czy jest wolne wyjście hydrauliczne pod zawór.

Bezpieczeństwo: odpowiedzialność za ludzi wokół maszyny jest rolnika; długo jeździ się z człowiekiem w kabinie
albo przy polu z pilotem awaryjnym.
