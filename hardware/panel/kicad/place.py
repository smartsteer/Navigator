"""
Panel oprysku (etap 22b): obrys płytki pod obudowę, wycięcia, rozmieszczenie elementów.
Położenia z ../obudowa/README.md (x od lewej, y od krawędzi przy operatorze) → KiCad: X = x, Y = 140 − y
(operator na dole rysunku, widok od strony elementów = od płyty czołowej).
Uruchomienie: /usr/bin/python3 place.py   (zmienia panel.kicad_pcb)
"""
import math

import pcbnew
from shapely.geometry import Point, Polygon, box
from shapely.ops import unary_union

MM = pcbnew.FromMM
B = pcbnew.LoadBoard("panel.kicad_pcb")
B.SetCopperLayerCount(4)   # 4 warstwy: In1 masa, In2 3,3 V (2026-11-24)
D = 140.0


def P(x, y):
    """punkt z układu obudowy → płytka (mm)"""
    return x, D - y


# ---------------------------------------------------------------- obrys (wnętrze obudowy − 4 mm, minus słupki śrub pokrywy)
def rrect(x0, y0, x1, y1, r):   # r: [lewy‑przód, prawy‑przód, prawy‑tył, lewy‑tył] w układzie obudowy
    pts = []
    corners = [((x0 + r[0], y0 + r[0]), r[0], 180, 270), ((x1 - r[1], y0 + r[1]), r[1], 270, 360),
               ((x1 - r[2], y1 - r[2]), r[2], 0, 90), ((x0 + r[3], y1 - r[3]), r[3], 90, 180)]
    for (cx, cy), rr, a0, a1 in corners:
        for k in range(13):
            a = math.radians(a0 + (a1 - a0) * k / 12)
            pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return Polygon(pts)


outline = rrect(4, 4, 296, 136, [14, 36, 36, 14])
SRUBY = [(40, 7.5), (120, 7.5), (200, 7.5), (258, 7.5), (120, 132.5), (200, 132.5), (258, 132.5), (7.5, 40), (7.5, 110),
         (292.5, 70)]
outline = outline.difference(unary_union([Point(p).buffer(5.5, 24) for p in SRUBY]))
# wycięcia pod korpusy elementów panelowych (głębsze niż 19 mm pod płytą)
holes = [Point(262, 108).buffer(13, 48),            # START/STOP Ø22 (korpus ~Ø24)
         box(205 - 11, 100 - 8, 205 + 11, 100 + 8)]  # włącznik kołyskowy 19,2 × 12,9 (z luzem)


def edge_poly(poly):
    c = list(poly.exterior.coords)
    for (x1, y1), (x2, y2) in zip(c, c[1:]):
        s = pcbnew.PCB_SHAPE(B)
        s.SetShape(pcbnew.SHAPE_T_SEGMENT)
        s.SetStart(pcbnew.VECTOR2I(MM(x1), MM(D - y1)))
        s.SetEnd(pcbnew.VECTOR2I(MM(x2), MM(D - y2)))
        s.SetLayer(pcbnew.Edge_Cuts)
        s.SetWidth(MM(0.1))
        B.Add(s)


for d in list(B.GetDrawings()):
    if d.GetLayer() == pcbnew.Edge_Cuts:
        B.Remove(d)
edge_poly(outline)
for h in holes:
    edge_poly(h)

fp = {f.GetReference(): f for f in B.GetFootprints()}
by_val = lambda pre: sorted([r for r, f in fp.items() if f.GetValue().startswith(pre)],
                            key=lambda r: int("".join(c for c in r if c.isdigit())))


def put(ref, x, y, rot=0, lock=True):
    f = fp[ref]
    X, Y = P(x, y)
    f.SetOrientationDegrees(rot)
    f.SetPosition(pcbnew.VECTOR2I(MM(X), MM(Y)))
    f.SetLocked(lock)


# ---------------------------------------------------------------- elementy panelowe (pozycje z obudowy)
secs = [r for r in by_val("SEKCJA")]
secs.sort(key=lambda r: int(fp[r].GetValue().split()[1]))
for k, r in enumerate(secs):
    put(r, 22 + 17 * k, 30, 0)
put(by_val("AUTO")[0], 150, 100, 0)
put(by_val("SWIATLO")[0], 175, 100, 0)
leds = [r for r in by_val("RGB 5mm WA S") if "STAN" not in fp[r].GetValue()]
leds.sort(key=lambda r: int(fp[r].GetValue().split("S")[-1]))
for k, r in enumerate(leds):
    put(r, 22 + 17 * k, 48, 0)
put(by_val("RGB 5mm WA STAN")[0], 226, 122, 0)
put(by_val("PT19")[0], 212, 122, 0)
put(by_val("TMAG5111")[0], 240, 62, 0)
put(by_val("SM351LT")[0], 240, 71, 0)
mh = sorted([r for r in fp if r.startswith("H")], key=lambda r: int(r[1:]))
for r, (x, y) in zip(mh, [(20, 20), (149.5, 20), (272, 24), (20, 57), (112, 120), (272, 116)]):
    put(r, x, y)

# ---------------------------------------------------------------- układy
put(by_val("ESP32")[0] if by_val("ESP32") else [r for r, f in fp.items() if "ESP32" in f.GetValue()][0], 160, 125.75, 0)
put(by_val("IS31FL3236A")[0], 85, 64, 0)
put(by_val("IS31FL3236A")[1], 200, 64, 0)
exps = by_val("TCA9534")
for r, (x, y) in zip(exps, [(47, 58), (115, 58), (170, 58), (150, 82)]):
    put(r, x, y, 0)
put([r for r, f in fp.items() if f.GetValue() == "TPS62177DQC"][0], 222, 86, 0)
put(by_val("BATERIA")[0], 186, 80, 0)
put(by_val("WLACZNIK")[0], 205, 86, 0)
put(by_val("START/STOP")[0], 262, 90, 0)
put(by_val("E-PAPIER")[0], 120, 104, 90)
put(by_val("PROG")[0], 128, 128, 90)

# ---------------------------------------------------------------- drobne elementy: obok układu, z którym dzielą sieć
locked = {r for r, f in fp.items() if f.IsLocked()}
POWER = {"GND", "V3V3", "VIN", ""}


def bbox(f):
    b = f.GetCourtyard(pcbnew.F_CrtYd).BBox() if f.GetCourtyard(pcbnew.F_CrtYd).OutlineCount() else f.GetBoundingBox(False, False)
    return box(pcbnew.ToMM(b.GetLeft()), pcbnew.ToMM(b.GetTop()), pcbnew.ToMM(b.GetRight()), pcbnew.ToMM(b.GetBottom()))


board = Polygon([(x, D - y) for x, y in outline.exterior.coords]).buffer(-1.0)
forbid = [Polygon([(x, D - y) for x, y in h.exterior.coords]).buffer(1.0) for h in holes]
# antena ESP32 i ściany kieszeni rollera (1 mm nad płytką)
esp = fp[[r for r, f in fp.items() if "ESP32" in f.GetValue()][0]]
ex, ey = pcbnew.ToMM(esp.GetPosition().x), pcbnew.ToMM(esp.GetPosition().y)
forbid.append(box(ex - 23, ey - 30, ex + 23, ey - 5))
rx0, ry0 = P(240 - 10, 62 + 20)
forbid.append(box(rx0, ry0, rx0 + 20, ry0 + 40).difference(box(rx0 + 2.5, ry0 + 2.5, rx0 + 17.5, ry0 + 37.5)))
occupied = [bbox(fp[r]).buffer(0.4) for r in locked] + forbid


def free(shape):
    return board.contains(shape) and not any(shape.intersects(o) for o in occupied)


owner = {}
for r, f in fp.items():
    if r in locked:
        continue
    nets = {p.GetNetname() for p in f.Pads()} - POWER
    best = None
    for o in locked:
        if o.startswith(("H", "SW")):
            continue
        if nets & {p.GetNetname() for p in fp[o].Pads()}:
            best = o
            break
    if best is None:  # kondensatory odsprzęgające: najbliższy układ z tym samym zasilaniem — wg kolejności numerów
        best = [o for o in locked if o.startswith("U")][sum(map(ord, r)) % len([o for o in locked if o.startswith("U")])]
    owner[r] = best

for r, o in sorted(owner.items(), key=lambda kv: kv[1]):
    f = fp[r]
    cx, cy = pcbnew.ToMM(fp[o].GetPosition().x), pcbnew.ToMM(fp[o].GetPosition().y)
    placed = False
    for rad in [x * 0.5 for x in range(4, 120)]:
        for k in range(max(8, int(rad * 3))):
            a = 2 * math.pi * k / max(8, int(rad * 3))
            for rot in (0, 90):
                f.SetOrientationDegrees(rot)
                f.SetPosition(pcbnew.VECTOR2I(MM(cx + rad * math.cos(a)), MM(cy + rad * math.sin(a))))
                s = bbox(f).buffer(0.25)
                if free(s):
                    occupied.append(s)
                    placed = True
                    break
            if placed:
                break
        if placed:
            break
    if not placed:
        print("nie zmieściło się:", r)

B.Save("panel.kicad_pcb")
print("elementów:", len(fp))
