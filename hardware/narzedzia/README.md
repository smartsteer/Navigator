# Narzędzia do płytek (KiCad 7, bez GUI)

Skrypty uruchamiane systemowym Pythonem z modułem `pcbnew` (`/usr/bin/python3`, pakiet `kicad` 7.0).
Potrzebne też: `skidl`, `kinet2pcb`, `shapely`, `numpy`, `scipy`, `pillow`; Freerouting 1.9 (`java -jar freerouting-1.9.0.jar`, w `xvfb-run`).

| Skrypt | Co robi |
|---|---|
| `plaszczyzny.py` | płaszczyzny sieci na warstwach wewnętrznych (np. `In1.Cu:GND In2.Cu:V3V3`) |
| `dsn_bez_plaszczyzn.py`, `dsn_bez_sieci.py` | przygotowanie DSN dla Freeroutingu: bez wypełnionych stref, bez sieci robionych płaszczyznami |
| `dotrasuj.py` | własny router labiryntowy (siatka 0,025–0,05 mm) — dokańcza połączenia z listy „niepołączone” DRC, przelotki do płaszczyzn (`--plaszczyzny`), naprawia naruszenia (`--napraw`), robi miejsce (`--zwolnij REF`, `--wokol x,y`) |
| `sprzatnij.py` | usuwa wiszące końcówki ścieżek/przelotek (uruchamiać do skutku) |
| `strefy_mocy.py` | wylewki dla przewodów prądowych (prostokąty sieci na F.Cu i B.Cu) |
| `wylewki.py`, `wyspy.py` | masa jako wylewki na płytce 2‑warstwowej + łączenie odciętych wysp (wersje 2‑warstwowe; obecnie płytki są 4‑warstwowe) |
| `przelotki_ep.py`, `do_padu_ep.py`, `tie_pins.py` | przelotki w padzie centralnym, krótkie ścieżki z pinów masy do EP, łączenie sąsiednich padów tej samej sieci |
| `przesun.py`, `zamien_footprint.py` | przesunięcie elementu w wolne miejsce, zamiana footprintu bez ruszania ścieżek |
| `ses_dodaj.py`, `odblokuj.py` | import SES bez kasowania istniejących ścieżek |
| `drc.py` | DRC z podsumowaniem rodzajów błędów |
| `podglad.py` | PNG fragmentu płytki (F.Cu czerwony, B.Cu niebieski) |
| `fab_jlcpcb.py` | Gerber (zip), BOM z MPN/LCSC, CPL dla JLCPCB |
| `netinfo.py` | sieci z netlisty (kontrola schematu) |
