"""Płaszczyzny na warstwach wewnętrznych (płytka 4-warstwowa): cała powierzchnia jednej sieci na warstwę.
Użycie: /usr/bin/python3 plaszczyzny.py plytka.kicad_pcb In1.Cu:GND In2.Cu:V3V3"""
import sys
import pcbnew
MM = pcbnew.FromMM
b = pcbnew.LoadBoard(sys.argv[1])
bb = b.GetBoardEdgesBoundingBox()
x0, y0, x1, y1 = (pcbnew.ToMM(v) for v in (bb.GetLeft(), bb.GetTop(), bb.GetRight(), bb.GetBottom()))
old = [z for z in b.Zones() if z.GetZoneName().startswith("PL_")]
for z in old:
    b.Remove(z)
for arg in sys.argv[2:]:
    lay, net = arg.split(":")
    z = pcbnew.ZONE(b)
    z.SetLayer(b.GetLayerID(lay))
    z.SetNetCode(b.FindNet(net).GetNetCode())
    z.SetZoneName(f"PL_{net}")
    z.SetLocalClearance(MM(0.25))
    z.SetMinThickness(MM(0.2))
    z.SetPadConnection(pcbnew.ZONE_CONNECTION_THT_THERMAL)
    z.SetIslandRemovalMode(pcbnew.ISLAND_REMOVAL_MODE_ALWAYS)
    o = z.Outline()
    o.NewOutline()
    for x, y in ((x0 - 1, y0 - 1), (x1 + 1, y0 - 1), (x1 + 1, y1 + 1), (x0 - 1, y1 + 1)):
        o.Append(MM(x), MM(y))
    b.Add(z)
pcbnew.ZONE_FILLER(b).Fill(b.Zones())
b.Save(sys.argv[1])
print("płaszczyzny:", sys.argv[2:])
