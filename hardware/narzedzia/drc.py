"""DRC bez GUI (KiCad 7): /usr/bin/python3 drc.py plytka.kicad_pcb [raport.txt] → liczba błędów wg rodzaju."""
import collections, re, sys
import pcbnew
b = pcbnew.LoadBoard(sys.argv[1])
out = sys.argv[2] if len(sys.argv) > 2 else "/tmp/drc.rpt"
pcbnew.WriteDRCReport(b, out, pcbnew.EDA_UNITS_MILLIMETRES, True)
s = open(out).read()
print(*re.findall(r"\*\* Found .*", s), sep="\n")
print(dict(collections.Counter(re.findall(r"^\[(\w+)\]", s, re.M))))
