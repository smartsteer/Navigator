"""Łączy sąsiednie pady tej samej sieci w jednym footprincie krótką ścieżką (np. 3 piny OUT DRV8962,
VM, PGND) — router łączy zwykle tylko jeden z nich. Użycie: python3 tie_pins.py [płytka.kicad_pcb]"""
import sys

import pcbnew

pcb = sys.argv[1] if len(sys.argv) > 1 else "maszyna.kicad_pcb"
b = pcbnew.LoadBoard(pcb)
n = 0
for fp in b.GetFootprints():
    pads = [p for p in fp.Pads() if p.GetNetname() and p.GetAttribute() == pcbnew.PAD_ATTRIB_SMD]
    for i, a in enumerate(pads):
        for c in pads[i + 1:]:
            if a.GetNetCode() != c.GetNetCode() or a.GetLayer() != c.GetLayer():
                continue
            pa, pc = a.GetPosition(), c.GetPosition()
            d = ((pa.x - pc.x) ** 2 + (pa.y - pc.y) ** 2) ** 0.5
            # tylko sąsiednie pady w rzędzie (w osi, odstęp ≤ 1,3 mm)
            if d > 1_300_000 or (pa.x != pc.x and pa.y != pc.y):
                continue
            # żaden inny pad (innej sieci) na drodze
            pts = [pcbnew.VECTOR2I(int(pa.x + (pc.x - pa.x) * k / 10), int(pa.y + (pc.y - pa.y) * k / 10)) for k in range(11)]
            if any(q.GetNetCode() != a.GetNetCode() and any(q.HitTest(v, 150_000) for v in pts) for q in fp.Pads()):
                continue
            w = min(a.GetSize().x, a.GetSize().y, c.GetSize().x, c.GetSize().y)
            t = pcbnew.PCB_TRACK(b)
            t.SetStart(pa)
            t.SetEnd(pc)
            t.SetWidth(max(int(w * 0.8), 200_000))
            t.SetLayer(a.GetLayer() if a.GetLayer() >= 0 else pcbnew.F_Cu)
            t.SetNet(a.GetNet())
            b.Add(t)
            n += 1
b.Save(pcb)
print("połączone pary padów:", n)
