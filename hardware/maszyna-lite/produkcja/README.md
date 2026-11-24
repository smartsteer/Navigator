# Płytka Maszyna Lite — pliki do zamówienia w JLCPCB

Wygenerowane `../../narzedzia/fab_jlcpcb.py` z `../kicad/lite.kicad_pcb` (2026-11-24). Stan płytki: **wszystkie połączenia
poprowadzone, DRC bez błędów elektrycznych** (zostają uwagi o sitodruku i 4 odstępy otworów wewnątrz footprintu USB‑C z biblioteki KiCad).

- `lite_gerber_jlcpcb.zip` — Gerber 4 warstwy + wiercenia.
- `lite_bom_jlcpcb.csv` — lista części z kolumną **MPN** (i LCSC tam, gdzie numer był pewny).
- `lite_cpl_jlcpcb.csv` — rozmieszczenie (obroty sprawdzić w podglądzie JLCPCB).

**Parametry zamówienia:** 4 warstwy, 1,6 mm, miedź zewnętrzna 1 oz (zalecane 2 oz przy wielu zaworach 2‑pin), 170 × 105 mm,
otwory od 0,2 mm, odstęp/ścieżka 0,15/0,2 mm. Stos: F.Cu sygnały, **In1 masa**, **In2 +12 V wyjść** (za bezpiecznikiem 10 A), B.Cu sygnały.
Wejście 12 V (listwa ZAS → P‑MOSFET → bezpiecznik) na wylewkach F.Cu/B.Cu.

**Zmiana względem 16La:** 4 warstwy zamiast 2 — na 2 warstwach nie dało się doprowadzić masy do wszystkich padów
(15 mostków + ESP32 + multiplekser); płaszczyzna masy pomaga też przy zakłóceniach w ciągniku. Koszt płytki rośnie o kilkanaście–kilkadziesiąt zł/szt. przy 5 szt.

Kanały multipleksera prądów: wyjście 11 (SEKCJA11) jest na kanale 15, kanał 10 wolny — tabela `MUX_CH` w `../kicad/lite.py`
(firmware Lite, etap 16Lc, musi ją mieć).

**Przed zamówieniem** — lista „Do sprawdzenia” w `../README.md` i `../kicad/README.md`.
