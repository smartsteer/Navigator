"""Krótkie ścieżki z pinów masy układu (QFP/QFN) prosto do jego padu centralnego (EP) — piny GND obok EP,
których router/wylewka nie dosięgły. Użycie: /usr/bin/python3 do_padu_ep.py plytka.kicad_pcb U7 U8 ..."""
import sys
import pcbnew
MM = pcbnew.FromMM
b = pcbnew.LoadBoard(sys.argv[1])
fps = {f.GetReference(): f for f in b.GetFootprints()}
n = 0
for ref in sys.argv[2:]:
    f = fps[ref]
    ep = max(f.Pads(), key=lambda p: p.GetSize().x * p.GetSize().y)
    e = ep.GetBoundingBox()
    c = ep.GetPosition()
    for p in f.Pads():
        if p is ep or p.GetNetCode() != ep.GetNetCode() or p.GetAttribute() != pcbnew.PAD_ATTRIB_SMD:
            continue
        q = p.GetPosition()
        dx, dy = abs(q.x - c.x), abs(q.y - c.y)
        if dx > dy:   # pin po lewej/prawej — ścieżka poziomo do krawędzi EP
            end = pcbnew.VECTOR2I(e.GetLeft() + MM(0.3) if q.x < c.x else e.GetRight() - MM(0.3), q.y)
        else:
            end = pcbnew.VECTOR2I(q.x, e.GetTop() + MM(0.3) if q.y < c.y else e.GetBottom() - MM(0.3))
        t = pcbnew.PCB_TRACK(b)
        t.SetStart(q)
        t.SetEnd(end)
        t.SetWidth(MM(0.25))
        t.SetLayer(pcbnew.F_Cu)
        t.SetNet(ep.GetNet())
        t.SetLocked(True)   # router ma je omijać
        b.Add(t)
        n += 1
b.Save(sys.argv[1])
print("ścieżki do EP:", n)
