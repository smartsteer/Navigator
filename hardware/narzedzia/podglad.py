"""Podgląd fragmentu płytki (PNG): F.Cu czerwony, B.Cu niebieski, pady szare, przelotki żółte, niepołączone z DRC — zielone krzyże.
Użycie: /usr/bin/python3 podglad.py plytka.kicad_pcb x0 y0 x1 y1 wyjscie.png [px_na_mm]"""
import sys
import pcbnew
from PIL import Image, ImageDraw
pcb, x0, y0, x1, y1, out = sys.argv[1], *map(float, sys.argv[2:6]), sys.argv[6]
k = float(sys.argv[7]) if len(sys.argv) > 7 else 30
TM = pcbnew.ToMM
b = pcbnew.LoadBoard(pcb)
im = Image.new("RGB", (int((x1 - x0) * k), int((y1 - y0) * k)), (10, 10, 20))
d = ImageDraw.Draw(im, "RGBA")
P = lambda v: ((TM(v.x) - x0) * k, (TM(v.y) - y0) * k)
for lay, col in ((pcbnew.B_Cu, (60, 90, 255, 170)), (pcbnew.F_Cu, (230, 50, 50, 170))):
    for t in b.GetTracks():
        if t.GetClass() == "PCB_TRACK" and t.GetLayer() == lay:
            w = TM(t.GetWidth()) * k
            a, c = P(t.GetStart()), P(t.GetEnd())
            d.line([a, c], fill=col, width=max(1, int(w)))
            for q in (a, c):
                d.ellipse([q[0] - w / 2, q[1] - w / 2, q[0] + w / 2, q[1] + w / 2], fill=col)
for p in b.GetPads():
    ps = pcbnew.SHAPE_POLY_SET()
    p.TransformShapeToPolygon(ps, pcbnew.F_Cu if p.IsOnLayer(pcbnew.F_Cu) else pcbnew.B_Cu, 0, pcbnew.FromMM(0.01))
    for i in range(ps.OutlineCount()):
        o = ps.Outline(i)
        pts = [P(o.CPoint(j)) for j in range(o.PointCount())]
        if len(pts) > 2:
            d.polygon(pts, fill=(170, 170, 170, 200))
    c = P(p.GetPosition())
    if p.GetNumber():
        d.text(c, p.GetNumber(), fill=(0, 0, 0))
for t in b.GetTracks():
    if t.GetClass() == "PCB_VIA":
        c, r = P(t.GetPosition()), TM(t.GetWidth()) * k / 2
        d.ellipse([c[0] - r, c[1] - r, c[0] + r, c[1] + r], fill=(240, 200, 0, 220))
for f in b.GetFootprints():
    c = P(f.GetPosition())
    d.text((c[0], c[1] - 12), f.GetReference(), fill=(255, 255, 255))
im.save(out)
