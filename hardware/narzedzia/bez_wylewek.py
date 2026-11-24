"""Usuwa wylewki WYL_* (przed dotrasowaniem; po nim: wylewki.py). Użycie: /usr/bin/python3 bez_wylewek.py plytka.kicad_pcb"""
import sys
import pcbnew
b = pcbnew.LoadBoard(sys.argv[1])
z = [z for z in b.Zones() if z.GetZoneName().startswith("WYL_")]
for x in z:
    b.Remove(x)
b.Save(sys.argv[1])
print("usunięte wylewki:", len(z))
