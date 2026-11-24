"""Przelotki termiczne w padzie centralnym (EP) układów: siatka przelotek 0,6/0,3 mm co 1,2 mm do masy na drugiej stronie.
Użycie: /usr/bin/python3 przelotki_ep.py plytka.kicad_pcb U7 U8 ..."""
import sys

import pcbnew

MM = pcbnew.FromMM
b = pcbnew.LoadBoard(sys.argv[1])
fps = {f.GetReference(): f for f in b.GetFootprints()}
n = 0
for ref in sys.argv[2:]:
    f = fps[ref]
    ep = max(f.Pads(), key=lambda p: p.GetSize().x * p.GetSize().y)
    c, s = ep.GetPosition(), ep.GetBoundingBox()
    w, h = pcbnew.ToMM(s.GetWidth()), pcbnew.ToMM(s.GetHeight())
    nx, ny = max(1, int((w - 0.6) / 1.2) + 1), max(1, int((h - 0.6) / 1.2) + 1)
    for i in range(nx):
        for j in range(ny):
            v = pcbnew.PCB_VIA(b)
            v.SetPosition(pcbnew.VECTOR2I(c.x + MM((i - (nx - 1) / 2) * 1.2), c.y + MM((j - (ny - 1) / 2) * 1.2)))
            v.SetWidth(MM(0.6))
            v.SetDrill(MM(0.3))
            v.SetNet(ep.GetNet())
            v.SetLocked(True)
            b.Add(v)
            n += 1
b.Save(sys.argv[1])
print("przelotki EP:", n)
