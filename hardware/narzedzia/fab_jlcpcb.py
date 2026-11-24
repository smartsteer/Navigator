"""Pliki produkcyjne JLCPCB z płytki KiCad 7 (bez GUI): Gerber + wiercenia (zip), BOM (z kolumną MPN) i CPL.
Użycie: /usr/bin/python3 fab_jlcpcb.py plytka.kicad_pcb katalog_wyjsciowy przedrostek [mpn.csv]
mpn.csv (opcjonalnie, ;-separowany): Wartość;MPN;LCSC — dopisuje numery do BOM po wartości elementu."""
import csv
import os
import shutil
import subprocess
import sys
import zipfile
from collections import defaultdict

import pcbnew

pcb, out, pre = sys.argv[1:4]
mpn = {}
if len(sys.argv) > 4:
    for row in csv.reader(open(sys.argv[4], encoding="utf-8"), delimiter=";"):
        if row and not row[0].startswith("#") and len(row) >= 2:
            mpn[row[0]] = (row[1], row[2] if len(row) > 2 else "")
os.makedirs(out, exist_ok=True)
g = os.path.join(out, "gerber")
shutil.rmtree(g, ignore_errors=True)
os.makedirs(g)
b = pcbnew.LoadBoard(pcb)
cu = ",".join(b.GetLayerName(l) for l in b.GetEnabledLayers().CuStack())
layers = cu + ",F.Paste,B.Paste,F.SilkS,B.SilkS,F.Mask,B.Mask,Edge.Cuts"
subprocess.run(["kicad-cli", "pcb", "export", "gerbers", "-o", g + "/", "-l", layers, "--subtract-soldermask", "--no-x2", pcb],
               check=True, capture_output=True)
subprocess.run(["kicad-cli", "pcb", "export", "drill", "-o", g + "/", "--format", "excellon", "--drill-origin", "absolute",
                "--excellon-units", "mm", "--excellon-separate-th", "--generate-map", "--map-format", "gerberx2", pcb],
               check=True, capture_output=True)
with zipfile.ZipFile(os.path.join(out, f"{pre}_gerber_jlcpcb.zip"), "w", zipfile.ZIP_DEFLATED) as z:
    for f in sorted(os.listdir(g)):
        z.write(os.path.join(g, f), f)
n = len(os.listdir(g))
shutil.rmtree(g)
groups = defaultdict(list)
cpl = []
for fp in b.Footprints():
    ref = fp.GetReference()
    if ref.startswith("H") or fp.GetAttributes() & pcbnew.FP_EXCLUDE_FROM_BOM:
        continue
    fpn = fp.GetFPIDAsString().split(":")[-1]
    groups[(fp.GetValue(), fpn)].append(ref)
    pos = fp.GetPosition()
    cpl.append([ref, f"{pos.x / 1e6:.3f}mm", f"{-pos.y / 1e6:.3f}mm", "Bottom" if fp.IsFlipped() else "Top",
                f"{fp.GetOrientationDegrees() % 360:.0f}"])
key = lambda r: (r.rstrip("0123456789"), int("0" + "".join(c for c in r if c.isdigit())))
with open(os.path.join(out, f"{pre}_bom_jlcpcb.csv"), "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["Comment", "Designator", "Footprint", "MPN", "LCSC Part #"])
    for (val, fpn), refs in sorted(groups.items()):
        m, l = mpn.get(val, ("", ""))
        w.writerow([val, ",".join(sorted(refs, key=key)), fpn, m, l])
with open(os.path.join(out, f"{pre}_cpl_jlcpcb.csv"), "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
    w.writerows(sorted(cpl, key=lambda r: key(r[0])))
print("gotowe:", n, "plików Gerber/wierceń →", out)
