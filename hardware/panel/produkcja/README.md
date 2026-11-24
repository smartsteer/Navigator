# Panel oprysku — pliki do zamówienia w JLCPCB

Wygenerowane `../../narzedzia/fab_jlcpcb.py` z `../kicad/panel.kicad_pcb` (2026-11-24). Stan płytki: **wszystkie połączenia
poprowadzone, DRC bez błędów elektrycznych** (zostają tylko uwagi o sitodruku i jedno nachodzenie obrysu słupka H1 na dźwignię 1 — patrz niżej).

- `panel_gerber_jlcpcb.zip` — Gerber 4 warstwy + wiercenia.
- `panel_bom_jlcpcb.csv` — lista części z kolumną **MPN** (i LCSC tam, gdzie numer był pewny); resztę JLCPCB dopasuje po MPN — sprawdzić podgląd.
- `panel_cpl_jlcpcb.csv` — rozmieszczenie (obroty sprawdzić w podglądzie JLCPCB).

**Parametry zamówienia:** 4 warstwy, 1,6 mm, miedź 1 oz, wymiar ok. 292 × 132 mm, najmniejsze otwory 0,2 mm (przelotki 0,45/0,2),
odstęp/ścieżka 0,15/0,2 mm. Stos: F.Cu sygnały, In1 masa, In2 3,3 V, B.Cu sygnały.

**Montaż w JLCPCB:** elementy SMD z wierzchu. Dźwignie, diody 5 mm i złącza JST są przewlekane — można je zlecić (THT)
albo przylutować samemu; dźwigni IP67 zwykle nie ma w magazynie JLCPCB.

**Przed zamówieniem** — lista „Do sprawdzenia” w `../kicad/README.md` (rozstaw nóżek dźwigni, kolejność nóżek diod RGB,
pierścień START/STOP 3 V, R_EXT). Słupek H1 (20, 20) dotyka obrysu dźwigni sekcji 1 — zmierzyć korpus wybranej dźwigni.
