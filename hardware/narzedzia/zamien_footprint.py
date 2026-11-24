"""Zamiana footprintu na płytce w miejscu (te same numery padów): ścieżki zostają. 
Użycie: /usr/bin/python3 zamien_footprint.py plytka.kicad_pcb REF katalog.pretty nazwa"""
import sys
import pcbnew
pcb, ref, lib, name = sys.argv[1:5]
b = pcbnew.LoadBoard(pcb)
old = [f for f in b.GetFootprints() if f.GetReference() == ref][0]
new = pcbnew.FootprintLoad(lib, name)
new.SetParent(b)
new.SetReference(ref)
new.SetValue(old.GetValue())
new.SetPosition(old.GetPosition())
new.SetOrientation(old.GetOrientation())
nets = {p.GetNumber(): p.GetNet() for p in old.Pads()}
for p in new.Pads():
    if p.GetNumber() in nets:
        p.SetNet(nets[p.GetNumber()])
new.SetLocked(old.IsLocked())
b.Remove(old)
b.Add(new)
b.Save(pcb)
print("zamieniono", ref, "→", name)
