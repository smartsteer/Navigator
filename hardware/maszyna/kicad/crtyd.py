"""Sprawdzenie nakładania się obrysów courtyard (jak DRC „Courtyards overlap”) i wyjścia poza płytkę."""
import pcbnew, itertools
b = pcbnew.LoadBoard("maszyna.kicad_pcb")
polys = []
for f in b.GetFootprints():
    f.BuildCourtyardCaches()
    for layer in (pcbnew.F_CrtYd, pcbnew.B_CrtYd):
        c = f.GetCourtyard(layer)
        if c.OutlineCount():
            polys.append((f.GetReference(), layer, c))
bad = []
for (ra, la, ca), (rb, lb, cb) in itertools.combinations(polys, 2):
    if la != lb:
        continue
    if not ca.BBox().Intersects(cb.BBox()):
        continue
    x = pcbnew.SHAPE_POLY_SET(ca)
    x.BooleanIntersection(cb, pcbnew.SHAPE_POLY_SET.PM_FAST)
    if x.OutlineCount() and x.Area() > 1e4:   # > 0,01 mm²
        bad.append((ra, rb, round(x.Area() / 1e12, 2)))
print("kolizje courtyard:", len(bad))
for r in bad[:40]:
    print(" ", r)
