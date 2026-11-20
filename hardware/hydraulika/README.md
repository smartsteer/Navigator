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

## Funkcje — 12 dźwigni dwukierunkowych

Przykładowe przypisanie (wymienne paski z opisem — każdy ustawia pod swój opryskiwacz):
składanie L1, L2, P1, P2 (belka 2‑stopniowa), wysokość, pochylenie, blokada wahań, dyszel skrętny, znacznik L, znacznik P,
funkcja 11, funkcja 12. Przy każdej dźwigni dioda — świeci, gdy funkcja pracuje.

**Wyjścia płytki Maszyna:** jeden mostek DRV8962 ma dwa półmostki — każdy zasila jedną cewkę (A albo B) jednej funkcji
(cewki do wspólnej masy). 16 mostków = **do 16 funkcji dwukierunkowych (32 cewki)**, 2 A na cewkę; panel ma 12 dźwigni,
4 mostki zapasu (np. zawór główny hydrauliki, funkcje stałe). Przypisanie dźwignia → mostek ustawiane raz przez USB.

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
