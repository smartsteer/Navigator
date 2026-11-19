# Pliki produkcyjne JLCPCB — WERSJA ROBOCZA, NIE ZAMAWIAĆ

Wygenerowane `kicad/fab.py` z `kicad/maszyna.kicad_pcb` (2026-11-19). Płytka ma jeszcze **87 niepołączonych padów**
(lista: DRC w KiCad → „Niepołączone”) — dopiero po ich dokończeniu i przeglądzie elektronika pliki nadają się do zamówienia.

- `maszyna_gerber_jlcpcb.zip` — Gerber 4 warstwy + wiercenia (Excellon, PTH/NPTH osobno).
- `maszyna_bom_jlcpcb.csv` — kolumna **LCSC Part #** pusta: uzupełnić numerami z katalogu JLCPCB (po MPN z `../bom.csv`).
- `maszyna_cpl_jlcpcb.csv` — rozmieszczenie; obroty sprawdzić w podglądzie JLCPCB (biblioteki KiCad i JLCPCB różnią się dla części obudów).
- Zamówienie: 4 warstwy, **miedź zewnętrzna 2 oz**, wewnętrzna 1 oz (zalecane — płaszczyzna V12 niesie prąd lamp), grubość 1,6 mm.
