"""Usuwa wiszące końcówki ścieżek i przelotki (DRC: track_dangling / via_dangling), jeśli to nie rozłącza niczego.
Użycie: /usr/bin/python3 sprzatnij.py plytka.kicad_pcb   (jedno przejście; uruchamiać do skutku)"""
import re, sys, tempfile
import pcbnew
MM, TM = pcbnew.FromMM, pcbnew.ToMM
pcb = sys.argv[1]
b = pcbnew.LoadBoard(pcb)


def rpt():
    with tempfile.NamedTemporaryFile(suffix=".rpt") as t:
        pcbnew.WriteDRCReport(b, t.name, pcbnew.EDA_UNITS_MILLIMETRES, True)
        return open(t.name).read()


s = rpt()
unc0 = int(re.search(r"Found (\d+) unconnected", s).group(1))
rm = []
for blk in re.split(r"\n(?=\[)", s):
    if blk.startswith(("[track_dangling]", "[via_dangling]")):
        m = re.search(r"@\(([-\d.]+) mm, ([-\d.]+) mm\): (Track|Via)[^\[]*\[([^\]]*)\][^\n]*?(?:length ([\d.]+) mm)?\n", blk + "\n")
        if not m:
            continue
        x, y, kind, net, ln = m.groups()
        p = pcbnew.VECTOR2I(MM(float(x)), MM(float(y)))
        cand = [t for t in b.GetTracks() if t.GetNetname() == net and not t.IsLocked() and
                ((kind == "Via" and t.GetClass() == "PCB_VIA" and t.GetPosition() == p) or
                 (kind == "Track" and t.GetClass() == "PCB_TRACK" and ln and abs(TM(t.GetLength()) - float(ln)) < 0.002 and
                  min((t.GetStart() - p).EuclideanNorm(), (t.GetEnd() - p).EuclideanNorm()) < MM(0.5)))]
        if cand and cand[0] not in rm:
            rm.append(cand[0])
for t in rm:
    b.Remove(t)
b.Save(pcb)
print("usunięte wiszące:", len(rm), "| niepołączone przed:", unc0)
