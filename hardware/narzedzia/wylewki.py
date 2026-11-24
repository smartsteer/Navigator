"""Wylewki miedzi po routingu (płytki 2-warstwowe): masa na F.Cu i B.Cu na całym obrysie + opcjonalne strefy innych sieci
(prostokąty, wyższy priorytet), przelotki zszywające masę. Użycie:
  /usr/bin/python3 wylewki.py plytka.kicad_pcb [SIEC:F.Cu:x0,y0,x1,y1 ...]"""
import sys

import pcbnew

MM = pcbnew.FromMM
pcb = sys.argv[1]
b = pcbnew.LoadBoard(pcb)
ZSZ = MM(0.62)   # średnica przelotek zszywających (po niej poznajemy je przy ponownym uruchomieniu)
_old = [t for t in b.GetTracks() if t.GetClass() == "PCB_VIA" and t.GetWidth() == ZSZ]
_bb = b.GetBoardEdgesBoundingBox()   # przed usuwaniem stref (KiCad 7 + SWIG)
x0, y0, x1, y1 = (pcbnew.ToMM(v) for v in (_bb.GetLeft(), _bb.GetTop(), _bb.GetRight(), _bb.GetBottom()))
for z in list(b.Zones()):
    if z.GetZoneName().startswith("WYL_"):
        b.Remove(z)
for v in _old:
    b.Remove(v)


def zone(net, layer, pts, prio, name):
    z = pcbnew.ZONE(b)
    z.SetLayer(layer)
    z.SetNetCode(b.FindNet(net).GetNetCode())
    z.SetZoneName(name)
    z.SetAssignedPriority(prio)
    z.SetLocalClearance(MM(0.2))
    z.SetMinThickness(MM(0.2))
    z.SetPadConnection(pcbnew.ZONE_CONNECTION_THT_THERMAL)   # SMD pełną miedzią, przewlekane z odciążeniem
    z.SetThermalReliefGap(MM(0.3))
    z.SetThermalReliefSpokeWidth(MM(0.5))
    z.SetIslandRemovalMode(pcbnew.ISLAND_REMOVAL_MODE_ALWAYS)
    o = z.Outline()
    o.NewOutline()
    for x, y in pts:
        o.Append(MM(x), MM(y))
    b.Add(z)


frame = [(x0 - 1, y0 - 1), (x1 + 1, y0 - 1), (x1 + 1, y1 + 1), (x0 - 1, y1 + 1)]
for lay, nm in ((pcbnew.F_Cu, "F"), (pcbnew.B_Cu, "B")):
    zone("GND", lay, frame, 0, f"WYL_GND_{nm}")
for k, arg in enumerate(sys.argv[2:]):
    net, lay, r = arg.split(":")
    a, c, d, e = map(float, r.split(","))
    zone(net, b.GetLayerID(lay), [(a, c), (d, c), (d, e), (a, e)], 5 + k, f"WYL_{net}_{k}")
pcbnew.ZONE_FILLER(b).Fill(b.Zones())

# przelotki zszywające masę (siatka co 4 mm, tylko tam, gdzie obie wylewki masy mieszczą całą przelotkę z zapasem)
import math
zf = [z for z in b.Zones() if z.GetZoneName() == "WYL_GND_F"][0]
zb = [z for z in b.Zones() if z.GetZoneName() == "WYL_GND_B"][0]
ff, fb = zf.GetFilledPolysList(pcbnew.F_Cu), zb.GetFilledPolysList(pcbnew.B_Cu)
holes = [(p.GetPosition(), pcbnew.ToMM(p.GetDrillSize().x)) for p in b.GetPads() if p.GetDrillSize().x > 0]
holes += [(t.GetPosition(), pcbnew.ToMM(t.GetDrillValue())) for t in b.GetTracks() if t.GetClass() == "PCB_VIA"]
n = 0
r = 0.31 + 0.1
y = y0 + 2
while y < y1 - 1:
    x = x0 + 2
    while x < x1 - 1:
        pts = [pcbnew.VECTOR2I(MM(x + r * math.cos(a * math.pi / 4)), MM(y + r * math.sin(a * math.pi / 4))) for a in range(8)]
        pts.append(pcbnew.VECTOR2I(MM(x), MM(y)))
        if all(ff.Contains(q) and fb.Contains(q) for q in pts):
            c = pcbnew.VECTOR2I(MM(x), MM(y))
            if all((c - hp).EuclideanNorm() > MM(0.15 + 0.3 + d / 2 + 0.25) for hp, d in holes):
                v = pcbnew.PCB_VIA(b)
                v.SetPosition(c)
                v.SetWidth(ZSZ)
                v.SetDrill(MM(0.3))
                v.SetNet(b.FindNet("GND"))
                b.Add(v)
                holes.append((c, 0.3))
                n += 1
        x += 6
    y += 6
pcbnew.ZONE_FILLER(b).Fill(b.Zones())


# każda wyspa wylewki (osobny kawałek miedzi) dostaje przelotkę do wylewki na drugiej stronie, jeśli tylko się mieści
def islands_stitch(fa, fother):
    global n
    for i in range(fa.OutlineCount()):
        o = fa.Outline(i)
        bx = o.BBox()
        isl = pcbnew.SHAPE_POLY_SET()
        isl.AddOutline(o)
        ok = 0
        yy = pcbnew.ToMM(bx.GetTop()) + 0.4
        while yy < pcbnew.ToMM(bx.GetBottom()) and ok < 2:
            xx = pcbnew.ToMM(bx.GetLeft()) + 0.4
            while xx < pcbnew.ToMM(bx.GetRight()) and ok < 2:
                pts = [pcbnew.VECTOR2I(MM(xx + r * math.cos(a * math.pi / 4)), MM(yy + r * math.sin(a * math.pi / 4))) for a in range(8)]
                pts.append(pcbnew.VECTOR2I(MM(xx), MM(yy)))
                c = pts[-1]
                if all(isl.Contains(q) and fother.Contains(q) for q in pts) and \
                        all((c - hp).EuclideanNorm() > MM(0.15 + 0.3 + d / 2 + 0.25) for hp, d in holes):
                    v = pcbnew.PCB_VIA(b)
                    v.SetPosition(c)
                    v.SetWidth(ZSZ)
                    v.SetDrill(MM(0.3))
                    v.SetNet(b.FindNet("GND"))
                    b.Add(v)
                    holes.append((c, 0.3))
                    n += 1
                    ok += 1
                    xx += 3
                xx += 0.5
            yy += 0.5


zf = [z for z in b.Zones() if z.GetZoneName() == "WYL_GND_F"][0]
zb = [z for z in b.Zones() if z.GetZoneName() == "WYL_GND_B"][0]
islands_stitch(zf.GetFilledPolysList(pcbnew.F_Cu), zb.GetFilledPolysList(pcbnew.B_Cu))
islands_stitch(zb.GetFilledPolysList(pcbnew.B_Cu), zf.GetFilledPolysList(pcbnew.F_Cu))
pcbnew.ZONE_FILLER(b).Fill(b.Zones())
print("przelotki zszywające:", n)
b.Save(pcb)
print("wylewki:", [z.GetZoneName() for z in b.Zones() if z.GetZoneName().startswith("WYL_")])
