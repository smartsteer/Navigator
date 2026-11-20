# Hydraulika belki — osobny panel + płytka Maszyna w trybie „Hydraulika” (etap 23)

Wizualizacja panelu: [wizualizacja_panel.png](wizualizacja_panel.png). Licencja GPLv3.

Decyzja właściciela (2026-11-20): hydraulika sterowana **tylko panelem i płytką**, bez Navigatora;
płytka wykonawcza ta sama co w oprysku (płytka Maszyna); panel z własną płytką pod swoją obudowę.

## Z czego się składa

| Część | Co to | Różnica względem oprysku |
|---|---|---|
| Płytka wykonawcza | **ta sama płytka Maszyna** (hardware/maszyna) | firmware w trybie „Hydraulika” (zworka albo ustawienie z USB) |
| Panel | **własna płytka** pod obudowę hydrauliki (ten sam schemat bloków i firmware co panel oprysku) | dźwignie i przyciski wlutowane dokładnie pod otwory — włóż, skręć, działa |
| Łączność | BLE panel ↔ płytka bezpośrednio | bez Navigatora i bez tabletu |

Płytka Maszyna ma 16 wyjść po 2 A (pełne mostki, diagnostyka) + mostek 15 A — wystarcza na typowe cewki zaworów
hydrauliki 12 V (zwykle 1,5–2 A). **Do sprawdzenia w konkretnym opryskiwaczu:** prąd cewek; większe — na mostek 15 A
albo przez przekaźnik.

## Funkcje (pierwsza wersja)

| Dźwignia (chwilowa, samopowrót do środka) | Wyjścia |
|---|---|
| Składanie lewe: wewnątrz / zewnątrz | 2 |
| Składanie prawe: wewnątrz / zewnątrz | 2 |
| Wysokość belki: góra / dół | 2 |
| Pochylenie: lewo / prawo | 2 |
| Wahania: blokada / wolne (stała, nie chwilowa) | 1–2 |
| Zawór główny hydrauliki (otwierany przy każdym ruchu) | 1 |

Razem ~10–11 wyjść z 16 — zapas na znacznik pianowy, rozkładanie znaczników, mieszadło hydrauliczne itp.
Przypisanie wyjść i czasy (np. opóźnienie zaworu głównego) ustawiane w firmware płytki — opis w etapie 23b.

## Bezpieczeństwo (najważniejsze)

- Ruch **tylko dopóki trzymasz** dźwignię; puszczenie = zawór zamknięty.
- Najpierw przycisk **HYDR. AKTYWNA** (świeci żółto; gaśnie sam po 60 s bez ruchu) — przypadkowe trącenie dźwigni nic nie robi.
- Panel nadaje stan dźwigni co 100 ms; płytka **zamyka wszystkie zawory hydrauliki, gdy przez 0,3 s nie dostanie ramki**
  (utrata zasięgu, baterie, zawieszenie) albo po STOP.
- Włącznik zasilania I/O na panelu (jak w panelu oprysku).
- Dwa kierunki jednej funkcji nigdy naraz (blokada w firmware płytki i panelu).
- Po włączeniu zasilania płytka startuje z zaworami zamkniętymi; hydraulika nieaktywna do naciśnięcia HYDR. AKTYWNA.

## Plan

- **23a** — ta specyfikacja i wizualizacja.
- **23b** — firmware płytki Maszyna: tryb „Hydraulika” (BLE z panelem, chwilowe wyjścia, zabezpieczenie 0,3 s, przypisanie wyjść).
- **23c** — firmware panelu w wersji hydraulika + płyta czołowa obudowy (wspólna z 22c).
