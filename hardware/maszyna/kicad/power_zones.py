"""Wylewki mocy (20–30 A) na F.Cu i B.Cu + przelotki zszywające do płaszczyzn — po routingu.
Strefa sieci = prostokąt wokół padów elementów mocy tej sieci (+ margines); pady łączone pełną miedzią;
wylewka omija cudze ścieżki i pady (odstęp z klasy). Przelotki 0,8/0,4 mm co 2,5 mm tylko tam, gdzie nic nie koliduje.
Użycie: python3 power_zones.py [płytka.kicad_pcb]"""
import sys

import pcbnew

MM = 1_000_000
pcb = sys.argv[1] if len(sys.argv) > 1 else "maszyna.kicad_pcb"
b = pcbnew.LoadBoard(pcb)

# sieć → elementy mocy, których pady wyznaczają obszar (bez małych elementów sygnałowych)
ZONES = {
    "VIN_RAW": ["X1", "Q1", "Q2", "C1"],
    "V12": ["Q1", "Q2", "C2", "C3", "C99", "Q5", "Q7", "D1"],
    "LAMPA_OUT": ["U8", "J2"],
    "SILNIK_A": ["Q5", "Q6", "X3", "D38"],
    "SILNIK_B": ["Q7", "Q8", "X4", "D39"],
    "SILNIK_BOCZNIK": ["Q6", "Q8", "R119"],
}
STITCH = {"VIN_RAW": None, "V12": pcbnew.In2_Cu, "SILNIK_A": None, "SILNIK_B": None, "LAMPA_OUT": None}

for z in list(b.Zones()):
    if z.GetZoneName().startswith("MOC_"):
        b.Remove(z)

fps = {fp.GetReference(): fp for fp in b.Footprints()}
for prio, (net, refs) in enumerate(ZONES.items()):
    pts = []
    for r in refs:
        for p in fps[r].Pads():
            if p.GetNetname() == net:
                bb = p.GetBoundingBox()
                pts += [(bb.GetLeft(), bb.GetTop()), (bb.GetRight(), bb.GetBottom())]
    if not pts:
        continue
    m = int(1.5 * MM)
    x0, y0 = min(p[0] for p in pts) - m, min(p[1] for p in pts) - m
    x1, y1 = max(p[0] for p in pts) + m, max(p[1] for p in pts) + m
    for layer in (pcbnew.F_Cu, pcbnew.B_Cu):
        z = pcbnew.ZONE(b)
        z.SetZoneName(f"MOC_{net}")
        z.SetLayer(layer)
        z.SetNet(b.FindNet(net))
        z.SetAssignedPriority(10 + prio)
        z.SetPadConnection(pcbnew.ZONE_CONNECTION_FULL)
        z.SetMinThickness(int(0.3 * MM))
        z.SetLocalClearance(int(0.3 * MM))
        z.SetIslandRemovalMode(pcbnew.ISLAND_REMOVAL_MODE_ALWAYS)
        o = z.Outline()
        o.NewOutline()
        for x, y in ((x0, y0), (x1, y0), (x1, y1), (x0, y1)):
            o.Append(x, y)
        b.Add(z)

pcbnew.ZONE_FILLER(b).Fill(b.Zones())

# przelotki zszywające: wewnątrz wypełnionej wylewki sieci na obu warstwach, bez kolizji
CL = int(0.3 * MM)
VIA_D, VIA_DR = int(0.8 * MM), int(0.4 * MM)
items = [t for t in b.GetTracks()] + [p for p in b.GetPads()]
added = 0
for net in ("VIN_RAW", "V12", "SILNIK_A", "SILNIK_B", "LAMPA_OUT", "SILNIK_BOCZNIK"):
    zs = [z for z in b.Zones() if z.GetZoneName() == f"MOC_{net}"]
    if len(zs) != 2:
        continue
    code = b.FindNet(net).GetNetCode()
    bb = zs[0].GetBoundingBox()
    step = int(2.5 * MM)
    y = bb.GetTop() + step
    while y < bb.GetBottom():
        x = bb.GetLeft() + step
        while x < bb.GetRight():
            v = pcbnew.VECTOR2I(x, y)
            r = VIA_D // 2
            ok = all(z.GetFilledPolysList(z.GetLayer()).Contains(v, -1, r + CL) for z in zs)
            if ok:
                for it in items:
                    hole = it.Type() == pcbnew.PCB_VIA_T or (it.Type() == pcbnew.PCB_PAD_T and it.GetDrillSize().x > 0)
                    if hole and it.HitTest(v, r + int(0.6 * MM)):  # odstęp otworów
                        ok = False
                        break
                    if it.GetNetCode() != code and it.HitTest(v, r + CL):
                        ok = False
                        break
            if ok:
                via = pcbnew.PCB_VIA(b)
                via.SetPosition(v)
                via.SetWidth(VIA_D)
                via.SetDrill(VIA_DR)
                via.SetNet(b.FindNet(net))
                b.Add(via)
                items.append(via)
                added += 1
            x += step
        y += step
pcbnew.ZONE_FILLER(b).Fill(b.Zones())
b.Save(pcb)
print("wylewki:", len(ZONES) * 2, "przelotki zszywające:", added)
