"""
Płytka Maszyna Lite (etap 16Lb): obrys 170 × 105 mm, 2 warstwy, rozmieszczenie.
Listwy wyjść W1–W8 na górnej krawędzi, W9–W15 + LAMPY na dolnej, zasilanie / czujniki / CAN / USB na lewej,
ESP32 anteną do prawej krawędzi (bez miedzi pod anteną). Każdy DRV8870 tuż za swoją listwą, drobne elementy obok.
Uruchomienie: /usr/bin/python3 place.py   (zmienia lite.kicad_pcb)
"""
import collections
import math

import pcbnew
from shapely.geometry import box

MM = pcbnew.FromMM
W, H = 170.0, 105.0
B = pcbnew.LoadBoard("lite.kicad_pcb")
B.SetCopperLayerCount(4)   # 4 warstwy: In1 masa, In2 +12 V wyjść (2026-11-24)
for d in list(B.GetDrawings()):
    if d.GetLayer() == pcbnew.Edge_Cuts:
        B.Remove(d)
R0 = 3.0
for x1, y1, x2, y2 in ((R0, 0, W - R0, 0), (W, R0, W, H - R0), (W - R0, H, R0, H), (0, H - R0, 0, R0)):
    s = pcbnew.PCB_SHAPE(B)
    s.SetShape(pcbnew.SHAPE_T_SEGMENT)
    s.SetStart(pcbnew.VECTOR2I(MM(x1), MM(y1)))
    s.SetEnd(pcbnew.VECTOR2I(MM(x2), MM(y2)))
    s.SetLayer(pcbnew.Edge_Cuts)
    s.SetWidth(MM(0.1))
    B.Add(s)
for cx, cy, a in ((R0, R0, 180), (W - R0, R0, 270), (W - R0, H - R0, 0), (R0, H - R0, 90)):
    s = pcbnew.PCB_SHAPE(B)
    s.SetShape(pcbnew.SHAPE_T_ARC)
    s.SetCenter(pcbnew.VECTOR2I(MM(cx), MM(cy)))
    s.SetStart(pcbnew.VECTOR2I(MM(cx + R0 * math.cos(math.radians(a))), MM(cy + R0 * math.sin(math.radians(a)))))
    s.SetArcAngleAndEnd(pcbnew.EDA_ANGLE(90, pcbnew.DEGREES_T))
    s.SetLayer(pcbnew.Edge_Cuts)
    s.SetWidth(MM(0.1))
    B.Add(s)

fp = {f.GetReference(): f for f in B.GetFootprints()}
val = lambda pre: sorted([r for r, f in fp.items() if f.GetValue().startswith(pre)], key=lambda r: int("".join(c for c in r if c.isdigit())))


def put(ref, x, y, rot=0):
    f = fp[ref]
    f.SetOrientationDegrees(rot)
    f.SetPosition(pcbnew.VECTOR2I(MM(x), MM(y)))
    f.SetLocked(True)


# ---------------------------------------------------------------- listwy wyjść i mostki
wy = {int(fp[r].GetValue().split()[0][1:]): r for r in val("W")}
drv = val("DRV8870")          # U… w kolejności tworzenia = kolejność wyjść
P = 16.6
for k in range(1, 9):          # góra: korpus listwy w stronę krawędzi (obrót 180°)
    x = 26 + (k - 1) * P
    put(wy[k], x + 10.5, 9.2, 180)
    put(drv[k - 1], x + 5.25, 20.5, 90)
for k in range(9, 16):          # dół
    x = 26 + (k - 9) * P
    put(wy[k], x, H - 9.2, 0)
    put(drv[k - 1], x + 5.25, H - 20.5, 90)
put(val("LAMPY")[0], 26 + 7 * P, H - 9.2, 0)

# ---------------------------------------------------------------- lewa krawędź: zasilanie, czujniki, CAN, USB
put(val("ZAS")[0], 10.6, 7, 270)     # obrót 270°: korpus (strona wtyku) w stronę lewej krawędzi
put(val("CZUJ")[0], 9.2, 26, 270)
put(val("CAN")[0], 9.2, 52, 270)
put(val("USB-C")[0], 4.5, 88, 270)
# ---------------------------------------------------------------- środek
put(val("ESP32")[0], W - 16.2, 52.5, -90)
put(val("AOD4185")[0], 22, 33, 0)
put(val("10A mini")[0], 24, 45, 0)
put(val("LMR33610")[0], 40, 40, 0)
put(val("10uH")[0], 52, 40, 0)
put(val("AMS1117")[0], 64, 40, 0)
for i, r in enumerate(val("100u 35V")):
    put(r, 30 + i * 12, 55.5, 0)
regs = val("74HC595")
for i, r in enumerate(regs):
    put(r, 62 + i * 15, 60, 0)
put(val("CD74HC4067")[0], 120, 46, 0)
put(val("TJA1051")[0], 24, 66, 0)
put(val("CH340C")[0], 22, 80, 0)
put(val("74LVC1G17")[0], 24, 22, 0)
holes = sorted([r for r in fp if r.startswith("H")], key=lambda r: int(r[1:]))
for r, (x, y) in zip(holes, [(17, 13), (W - 4, 6), (4, H - 30), (W - 4, H - 6)]):
    put(r, x, y)

# ---------------------------------------------------------------- drobne elementy
locked = {r for r, f in fp.items() if f.IsLocked()}
POWER = {"GND", "V3V3", "V5", "V12", "V12_WYJSCIA", ""}


def bbox(f):
    c = f.GetCourtyard(pcbnew.F_CrtYd)
    b = c.BBox() if c.OutlineCount() else f.GetBoundingBox(False, False)
    return box(pcbnew.ToMM(b.GetLeft()), pcbnew.ToMM(b.GetTop()), pcbnew.ToMM(b.GetRight()), pcbnew.ToMM(b.GetBottom()))


board = box(0.8, 0.8, W - 0.8, H - 0.8)
esp = fp[val("ESP32")[0]]
ex, ey = pcbnew.ToMM(esp.GetPosition().x), pcbnew.ToMM(esp.GetPosition().y)
occupied = [bbox(fp[r]).buffer(0.4) for r in locked] + [box(ex + 6, ey - 14, W, ey + 14)]   # antena


def free(s):
    return board.contains(s) and not any(s.intersects(o) for o in occupied)


ics = [r for r in locked if r.startswith(("U", "Q", "J"))]
pins_of = {r: {p.GetNetname() for p in fp[r].Pads()} for r in fp}
load = collections.Counter()
owner = {}
for r in sorted(fp, key=lambda r: (r[0], int("".join(c for c in r if c.isdigit()) or 0))):
    if r in locked:
        continue
    sig = pins_of[r] - POWER
    cands = [o for o in ics if sig & pins_of[o]]
    if cands:   # najpierw układ scalony, potem złącze
        cands.sort(key=lambda o: (not o.startswith("U"), len(pins_of[o])))
        owner[r] = cands[0]
        continue
    pw = pins_of[r] & (POWER - {"GND", ""})
    cands = [o for o in ics if o.startswith("U") and pw & pins_of[o]]
    o = min(cands, key=lambda o: load[o]) if cands else ics[0]
    load[o] += 1
    owner[r] = o

for r, o in sorted(owner.items(), key=lambda kv: kv[1]):
    f = fp[r]
    cx, cy = pcbnew.ToMM(fp[o].GetPosition().x), pcbnew.ToMM(fp[o].GetPosition().y)
    done = False
    for rad in [x * 0.5 for x in range(4, 160)]:
        n = max(8, int(rad * 3))
        for k in range(n):
            a = 2 * math.pi * k / n
            for rot in (0, 90):
                f.SetOrientationDegrees(rot)
                f.SetPosition(pcbnew.VECTOR2I(MM(cx + rad * math.cos(a)), MM(cy + rad * math.sin(a))))
                s = bbox(f).buffer(0.2)
                if free(s):
                    occupied.append(s)
                    done = True
                    break
            if done:
                break
        if done:
            break
    if not done:
        print("nie zmieściło się:", r)
B.Save("lite.kicad_pcb")
print("elementów:", len(fp))
