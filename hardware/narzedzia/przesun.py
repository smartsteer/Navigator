"""Przesuwa elementy (REF) do najbliższego miejsca bez kolizji obrysów (courtyard) z innymi elementami i krawędzią;
ścieżki do nich trzeba potem poprawić (dotrasuj.py --napraw). Użycie:
  /usr/bin/python3 przesun.py plytka.kicad_pcb REF[@x,y] ...   (@x,y — punkt startowy szukania, mm)"""
import math, sys
import pcbnew
from shapely.geometry import box
MM, TM = pcbnew.FromMM, pcbnew.ToMM
b = pcbnew.LoadBoard(sys.argv[1])
fps = {f.GetReference(): f for f in b.GetFootprints()}
def bb(f):
    c = f.GetCourtyard(pcbnew.F_CrtYd)
    r = c.BBox() if c.OutlineCount() else f.GetBoundingBox(False, False)
    return box(TM(r.GetLeft()), TM(r.GetTop()), TM(r.GetRight()), TM(r.GetBottom()))
e = b.GetBoardEdgesBoundingBox()
board = box(TM(e.GetLeft()) + 0.5, TM(e.GetTop()) + 0.5, TM(e.GetRight()) - 0.5, TM(e.GetBottom()) - 0.5)
args = [a.split("@") for a in sys.argv[2:]]
moving = {a[0] for a in args}
for a in args:
    ref = a[0]
    f = fps[ref]
    sx, sy = (map(float, a[1].split(","))) if len(a) > 1 else (TM(f.GetPosition().x), TM(f.GetPosition().y))
    others = [bb(g).buffer(0.3) for r, g in fps.items() if r != ref and (r not in moving or r in done)] if 'done' in dir() else [bb(g).buffer(0.3) for r, g in fps.items() if r not in moving]
    ok = False
    for rad in [x * 0.25 for x in range(0, 200)]:
        n = max(1, int(rad * 6))
        for k in range(n):
            x, y = sx + rad * math.cos(2 * math.pi * k / n), sy + rad * math.sin(2 * math.pi * k / n)
            f.SetPosition(pcbnew.VECTOR2I(MM(x), MM(y)))
            s = bb(f)
            if board.contains(s) and not any(s.intersects(o) for o in others):
                ok = True
                break
        if ok:
            break
    done = locals().get("done", set()) | {ref}
    print(ref, "→", (round(x, 2), round(y, 2)) if ok else "BRAK MIEJSCA")
b.Save(sys.argv[1])
