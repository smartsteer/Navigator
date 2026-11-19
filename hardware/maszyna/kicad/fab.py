"""Pliki produkcyjne JLCPCB z maszyna.kicad_pcb (KiCad 7, bez GUI): Gerber + wiercenia (zip), BOM i CPL dla montażu.
Użycie: python3 fab.py  → katalog ../produkcja"""
import csv
import os
import shutil
import subprocess
import zipfile
from collections import defaultdict

import pcbnew

here = os.path.dirname(os.path.abspath(__file__))
pcb = os.path.join(here, "maszyna.kicad_pcb")
out = os.path.join(here, "..", "produkcja")
g = os.path.join(out, "gerber")
shutil.rmtree(out, ignore_errors=True)
os.makedirs(g)

layers = "F.Cu,In1.Cu,In2.Cu,B.Cu,F.Paste,B.Paste,F.SilkS,B.SilkS,F.Mask,B.Mask,Edge.Cuts"
subprocess.run(["kicad-cli", "pcb", "export", "gerbers", "-o", g + "/", "-l", layers, "--subtract-soldermask",
                "--no-x2", pcb], check=True, capture_output=True)
subprocess.run(["kicad-cli", "pcb", "export", "drill", "-o", g + "/", "--format", "excellon", "--drill-origin", "absolute",
                "--excellon-units", "mm", "--excellon-separate-th", "--generate-map", "--map-format", "gerberx2", pcb],
               check=True, capture_output=True)
with zipfile.ZipFile(os.path.join(out, "maszyna_gerber_jlcpcb.zip"), "w", zipfile.ZIP_DEFLATED) as z:
    for f in sorted(os.listdir(g)):
        z.write(os.path.join(g, f), f)
n_files = len(os.listdir(g))
shutil.rmtree(g)  # w repozytorium tylko zip

# BOM i CPL (format JLCPCB); elementy bez montażu (otwory, zaciski M4, złącza THT) też są na liście — do odznaczenia
b = pcbnew.LoadBoard(pcb)
groups = defaultdict(list)
cpl = []
for fp in b.Footprints():
    ref = fp.GetReference()
    if ref.startswith(("H", "X")) or fp.GetAttributes() & pcbnew.FP_EXCLUDE_FROM_BOM:
        continue
    fpn = fp.GetFPIDAsString().split(":")[-1]
    groups[(fp.GetValue(), fpn)].append(ref)
    pos = fp.GetPosition()
    cpl.append([ref, f"{pos.x / 1e6:.3f}mm", f"{-pos.y / 1e6:.3f}mm", "Bottom" if fp.IsFlipped() else "Top",
                f"{fp.GetOrientationDegrees() % 360:.0f}"])
with open(os.path.join(out, "maszyna_bom_jlcpcb.csv"), "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["Comment", "Designator", "Footprint", "LCSC Part #"])
    for (val, fpn), refs in sorted(groups.items()):
        w.writerow([val, ",".join(sorted(refs, key=lambda r: (r.rstrip("0123456789"), int("0" + r.lstrip("ABCDEFGHIJKLMNOPQRSTUVWXYZ_"))))), fpn, ""])
with open(os.path.join(out, "maszyna_cpl_jlcpcb.csv"), "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
    w.writerows(sorted(cpl))
print("gotowe:", sorted(os.listdir(out)), n_files, "plików Gerber/wierceń")
