"""
Płytka Ster (etap 15c): obrys 110 × 80 mm, 4 warstwy (In1 masa, In2 3,3 V), rozmieszczenie. Licencja GPLv3.
Dół: AMPSEAL 23 (wiązka AIO), na prawo od niego zasilanie 12 V i stopień mocy silnika (blisko pinów 5/6/18/22).
Góra: RJ45, USB-C procesora, USB-C GPS, SMA anteny GNSS. Środek-lewo: i.MXRT1062 + flash + bootloader + PHY,
prawo-góra: ZED-F9P przy SMA. Drobne elementy obok „swoich” układów (jak maszyna-lite/kicad/place.py).
Uruchomienie: /usr/bin/python3 place.py   (zmienia ster.kicad_pcb)
"""
import collections
import math

import pcbnew
from shapely.geometry import box

MM = pcbnew.FromMM
W, H = 110.0, 80.0
B = pcbnew.LoadBoard("ster.kicad_pcb")
B.SetCopperLayerCount(4)
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
val = lambda pre: sorted([r for r, f in fp.items() if f.GetValue().startswith(pre)],
                         key=lambda r: int("".join(c for c in r if c.isdigit())))


def put(ref, x, y, rot=0, spod=False):
    f = fp[ref]
    if spod and not f.IsFlipped():
        f.Flip(f.GetPosition(), False)
    f.SetOrientationDegrees(rot)
    f.SetPosition(pcbnew.VECTOR2I(MM(x), MM(y)))
    f.SetLocked(True)


# ---------------------------------------------------------------- złącza na krawędziach
put("J1", 18, H - 13.55, 0)              # AMPSEAL: „PCB Edge” obudowy na y = 13,55 → krawędź dolna
put(val("HR911105A")[0], 15, 16.5, 180)  # RJ45 front przy krawędzi górnej
put(val("USB-C procesor")[0], 30, 3.1, 180)
put(val("USB-C GPS")[0], 78, 3.1, 180)
put(val("SMA")[0], 100, 2.9, 90)          # SMA krawędziowe, wyjście w górę
put(val("Radio RTK")[0], 106, 47, 90)
put(val("IMU TM171")[0], 106, 60, 90)
put(val("Serial2")[0], 74, 36, 0)
# ---------------------------------------------------------------- procesor i otoczenie
put(val("MIMXRT1062")[0], 44, 26, 0)
put(val("W25Q64")[0], 44, 40, 0)
put(val("PJRC MKL02")[0], 56, 37, 0)
put(val("DP83825")[0], 22, 26, 0)
put(val("24 MHz")[0], 54, 20, 0)
put(val("32.768")[0], 54, 31, 0)
# ---------------------------------------------------------------- GNSS
put(val("ZED-F9P")[0], 94, 22, 0)
# ---------------------------------------------------------------- zasilanie i silnik (prawy dół)
# mostek silnika (Q5–Q8) i DRV8701E nad AMPSEAL (piny 5/6/18), wejście 12 V (idealna dioda Q1/Q2 + LM74700, TVS),
# przetwornica 12→5 V i kondensatory 1000 µF przy dolnej krawędzi
put("C3", 67, 73, 0)
put("C2", 81, 73, 0)
put(val("DRV8701E")[0], 66, 51, 0)
put(val("LM74700")[0], 78, 42, 0)
put(val("SMCJ24A")[0], 95, 44, 0)
put(val("LMR33620")[0], 75, 52, 0)
put(val("10uH")[0], 77, 61, 0)
bk = val("BUK7J1R4")      # Q1, Q2 = idealna dioda; Q5–Q8 = mostek H
for r, (x, y) in zip(bk, [(86, 46), (86, 55), (50, 47), (58, 47), (50, 56), (58, 56)]):
    put(r, x, y, 90)
put(val("AOD4185")[0], 26, 57, 0)
holes = sorted([r for r in fp if r.startswith("H")], key=lambda r: int(r[1:]))
for r, (x, y) in zip(holes, [(4.5, 30), (60, 4.5), (105.5, 75.5), (105.5, 36.5)]):
    put(r, x, y)

# ---------------------------------------------------------------- drobne elementy
locked = {r for r, f in fp.items() if f.IsLocked()}
POWER = {"GND", "V3V3_STER", "V5", "V12", "VIN_12V", "V3V3_GNSS", ""}


def bbox(f):
    c = f.GetCourtyard(pcbnew.F_CrtYd)
    b = c.BBox() if c.OutlineCount() else f.GetBoundingBox(False, False)
    return box(pcbnew.ToMM(b.GetLeft()), pcbnew.ToMM(b.GetTop()), pcbnew.ToMM(b.GetRight()), pcbnew.ToMM(b.GetBottom()))


board = box(0.8, 0.8, W - 0.8, H - 0.8)
occupied = [bbox(fp[r]).buffer(0.4) for r in locked]
mcu = fp[val("MIMXRT1062")[0]]
mx, my = pcbnew.ToMM(mcu.GetPosition().x), pcbnew.ToMM(mcu.GetPosition().y)
occupied.append(box(mx - 8, my - 8, mx + 8, my + 8))   # wyprowadzenie BGA (fanout) — bez drobnych elementów na górze


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
    if cands:
        cands.sort(key=lambda o: (not o.startswith("U"), len(pins_of[o])))
        owner[r] = cands[0]
        continue
    pw = pins_of[r] & (POWER - {"GND", ""})
    cands = [o for o in ics if o.startswith("U") and pw & pins_of[o]]
    o = min(cands, key=lambda o: load[o]) if cands else ics[0]
    load[o] += 1
    owner[r] = o

nie = []
for r, o in sorted(owner.items(), key=lambda kv: kv[1]):
    f = fp[r]
    cx, cy = pcbnew.ToMM(fp[o].GetPosition().x), pcbnew.ToMM(fp[o].GetPosition().y)
    done = False
    for rad in [x * 0.5 for x in range(4, 200)]:
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
        nie.append(r)
B.Save("ster.kicad_pcb")
print("elementów:", len(fp), "nie zmieściło się:", nie)
