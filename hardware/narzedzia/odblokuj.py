"""Odblokowuje ścieżki i przelotki (oprócz przelotek o podanej średnicy, np. EP 0.6 zablokowanych celowo).
Użycie: /usr/bin/python3 odblokuj.py plytka.kicad_pcb"""
import sys
import pcbnew
b = pcbnew.LoadBoard(sys.argv[1])
n = 0
for t in b.GetTracks():
    if t.IsLocked() and t.GetClass() == "PCB_TRACK":
        t.SetLocked(False)
        n += 1
b.Save(sys.argv[1])
print("odblokowane ścieżki:", n)
