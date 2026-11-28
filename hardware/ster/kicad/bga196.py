"""Generator obudowy MAPBGA-196 12 × 12 mm, raster 0,8 mm (MIMXRT1062DVL6A) → smartsteer_ster.pretty. GPLv3.
Kulki A–P bez I i O, kolumny 1–14, A1 w lewym górnym rogu (widok z góry). Pola NSMD Ø0,40 mm (kulka 0,5 mm,
zalecenie NXP dla 0,8 mm), maska +0,05 mm. Uruchomienie: python3 bga196.py"""
import os

RZEDY = "ABCDEFGHJKLMNP"
P, D, BOK = 0.8, 0.40, 12.0
linie = ['(footprint "NXP_MAPBGA-196_12x12mm_P0.8mm" (version 20221018) (generator smartsteer)',
         '  (layer "F.Cu") (attr smd)',
         '  (descr "NXP MAPBGA-196 12x12 mm, 14x14, raster 0,8 mm (SOT1529-1 / 98ASA00939D), pola NSMD 0,40 mm")',
         '  (fp_text reference "REF**" (at 0 -7.6) (layer "F.SilkS") (effects (font (size 1 1) (thickness 0.15))))',
         '  (fp_text value "MAPBGA-196" (at 0 7.6) (layer "F.Fab") (effects (font (size 1 1) (thickness 0.15))))']
h = BOK / 2
for war, x1, y1, x2, y2, w in (("F.Fab", -h + 1, -h, h, h, 0.1), ("F.CrtYd", -h - 0.5, -h - 0.5, h + 0.5, h + 0.5, 0.05)):
    for a, b in (((x1, y1), (x2, y1)), ((x2, y1), (x2, y2)), ((x2, y2), (-h if war == "F.Fab" else x1, y2)),
                 ((-h if war == "F.Fab" else x1, y2), (-h if war == "F.Fab" else x1, -h + 1 if war == "F.Fab" else y1))):
        linie.append(f'  (fp_line (start {a[0]} {a[1]}) (end {b[0]} {b[1]}) (stroke (width {w}) (type solid)) (layer "{war}"))')
linie.append(f'  (fp_line (start {-h} {-h + 1}) (end {-h + 1} {-h}) (stroke (width 0.1) (type solid)) (layer "F.Fab"))')
s = h + 0.15   # sitodruk: narożniki + znacznik A1
for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
    linie.append(f'  (fp_line (start {sx * s} {sy * s}) (end {sx * (s - 1)} {sy * s}) (stroke (width 0.12) (type solid)) (layer "F.SilkS"))')
    linie.append(f'  (fp_line (start {sx * s} {sy * s}) (end {sx * s} {sy * (s - 1)}) (stroke (width 0.12) (type solid)) (layer "F.SilkS"))')
linie.append(f'  (fp_circle (center {-s - 0.4} {-s - 0.4}) (end {-s - 0.25} {-s - 0.4}) (stroke (width 0.2) (type solid)) (fill solid) (layer "F.SilkS"))')
for i, r in enumerate(RZEDY):
    for k in range(14):
        x, y = round((k - 6.5) * P, 3), round((i - 6.5) * P, 3)
        linie.append(f'  (pad "{r}{k + 1}" smd circle (at {x} {y}) (size {D} {D}) (layers "F.Cu" "F.Paste" "F.Mask") '
                     f'(solder_mask_margin 0.05))')
linie.append(")")
TU = os.path.dirname(os.path.abspath(__file__))
open(os.path.join(TU, "smartsteer_ster.pretty", "NXP_MAPBGA-196_12x12mm_P0.8mm.kicad_mod"), "w").write("\n".join(linie) + "\n")
print("OK: 196 pól")
