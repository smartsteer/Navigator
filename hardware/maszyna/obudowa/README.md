# Obudowa płytki Maszyna — druk 3D (etap 16c)

Pliki: `obudowa.scad` (źródło, parametryczne), `spod.stl`, `pokrywa.stl`.
Eksport po zmianie wymiarów: `openscad -D 'czesc="spod"' -o spod.stl obudowa.scad` (i `czesc="pokrywa"`).

- Wnętrze na płytkę 160 × 110 mm (z odstępem 8 mm od ścianek), wysokość wnętrza 42 + 12 mm, ścianka 3 mm.
- Ściana przednia: wycięcia na gniazda **AMPSEAL 35** i **AMPSEAL 23** z otworami na śruby kołnierza
  (wymiary wycięć `amp35`, `amp23` — **zmierzyć z rysunku TE przed drukiem**).
- Ściana boczna: USB‑C (z gumową zaślepką), gniazdo SMA anteny GPS, okienko LED, membrana odpowietrzająca M12 (IP67).
- Uszczelka: sznur silikonowy Ø2 mm w rowku spodu, pokrywa ma pióro dociskowe; 4 śruby M4 w wtopionych gwintach.
- Płytka na 4 słupkach z wtopionymi gwintami M3.
- Dwa ucha z otworami Ø8,5 mm do przykręcenia do ramy opryskiwacza (M8).

Druk: **ASA** (odporny na UV i chemię — zalecany na zewnątrz), ewentualnie PETG; 4 obrysy, wypełnienie ≥ 40 %,
pokrywa drukowana napisem do stołu. Obudowa z tworzywa przepuszcza Bluetooth — nie malować farbą metaliczną.

Do kupienia: 4 × wtopiony gwint M4 (Ø5,6 otwór), 4 × M3 (Ø4,2), 4 × śruba M4 × 16, 4 × M3 × 6, ~0,6 m sznura silikonowego Ø2 mm,
membrana odpowietrzająca M12 × 1,5, zaślepka USB‑C, gniazdo SMA na panel + pigtail u.FL.
