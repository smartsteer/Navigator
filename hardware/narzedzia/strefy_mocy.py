"""Wylewki dla przewodów prądowych (np. wejście 12 V): prostokąty jednej sieci na F.Cu i B.Cu, pady pełną miedzią.
Użycie: /usr/bin/python3 strefy_mocy.py plytka.kicad_pcb SIEC:x0,y0,x1,y1 [...]   (wcześniejsze argumenty = wyższy priorytet)"""
import sys
import pcbnew
MM = pcbnew.FromMM
b = pcbnew.LoadBoard(sys.argv[1])
for z in [z for z in b.Zones() if z.GetZoneName().startswith("MOC_")]:
    b.Remove(z)
args = sys.argv[2:]
for k, arg in enumerate(args):
    net, r = arg.split(":")
    x0, y0, x1, y1 = map(float, r.split(","))
    for lay in (pcbnew.F_Cu, pcbnew.B_Cu):
        z = pcbnew.ZONE(b)
        z.SetLayer(lay)
        z.SetNetCode(b.FindNet(net).GetNetCode())
        z.SetZoneName(f"MOC_{net}")
        z.SetAssignedPriority(20 + len(args) - k)
        z.SetLocalClearance(MM(0.3))
        z.SetMinThickness(MM(0.3))
        z.SetPadConnection(pcbnew.ZONE_CONNECTION_FULL)
        z.SetIslandRemovalMode(pcbnew.ISLAND_REMOVAL_MODE_ALWAYS)
        o = z.Outline()
        o.NewOutline()
        for x, y in ((x0, y0), (x1, y0), (x1, y1), (x0, y1)):
            o.Append(MM(x), MM(y))
        b.Add(z)
pcbnew.ZONE_FILLER(b).Fill(b.Zones())
b.Save(sys.argv[1])
print("strefy mocy:", args)
