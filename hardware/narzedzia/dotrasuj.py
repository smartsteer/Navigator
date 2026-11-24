"""
Dokończenie ścieżek po Freeroutingu: prosty router labiryntowy (Dijkstra na siatce) dla połączeń, które zostały
niepołączone (lista z DRC KiCad). Warstwy zewnętrzne F.Cu / B.Cu, przelotki tam, gdzie się mieszczą; odstępy z reguł
płytki (klasy połączeń). Nic nie przesuwa i nie usuwa — tylko dokłada ścieżki i przelotki. Po nim: DRC.

Użycie: /usr/bin/python3 dotrasuj.py plytka.kicad_pcb [--siatka 0.05] [--wewn In2.Cu] [--min-szer 0.3]
  --wewn    dodatkowa warstwa sygnałowa (np. In2.Cu na płytce 4-warstwowej, gdzie In2 to płaszczyzna V12)
  --min-szer jeśli połączenie nie wchodzi szerokością klasy, próba węższą (nie mniej niż ta wartość, mm)
"""
import argparse
import heapq
import math
import re
import subprocess
import sys
import tempfile

import numpy as np
import pcbnew
from PIL import Image, ImageDraw
from scipy import ndimage

ap = argparse.ArgumentParser()
ap.add_argument("pcb")
ap.add_argument("--siatka", type=float, default=0.05)
ap.add_argument("--wewn", default=None)
ap.add_argument("--min-szer", type=float, default=0.25)
ap.add_argument("--margines", type=float, default=12.0)
ap.add_argument("--max", type=int, default=1000)
ap.add_argument("--debug", action="store_true")
ap.add_argument("--mala-przelotka", default="0.45/0.2", help="zapasowa przelotka śr./wiercenie (2 warstwy: 0.56/0.3)")
ap.add_argument("--zwolnij", default="", help="REF[,REF…]: usuń ścieżki/przelotki w promieniu 2 mm wokół tych elementów i poprowadź od nowa")
ap.add_argument("--plaszczyzny", default="", help="warstwy wewnętrzne z płaszczyznami (np. In1.Cu,In2.Cu): tylko jako cel przez przelotkę, bez ścieżek")
ap.add_argument("--pomin", default="", help="SIEĆ[,SIEĆ…]: tych sieci nie prowadzić (np. GND — robią ją wylewki i wyspy.py)")
ap.add_argument("--wokol", default="", help="x,y[;x,y…] (mm): usuń ścieżki/przelotki innych sieci bliżej niż 1,2 mm od tych punktów (miejsce dla zablokowanego padu)")
ap.add_argument("--najpierw", default="", help="SIEĆ[,SIEĆ…]: te sieci prowadzić najpierw")
ap.add_argument("--napraw", action="store_true", help="najpierw usuń ścieżki/przelotki z naruszeniami odstępów i krawędzi (DRC)")
a = ap.parse_args()
DEBUG = a.debug

MM = pcbnew.FromMM
TM = pcbnew.ToMM
G = a.siatka
b = pcbnew.LoadBoard(a.pcb)
ds = b.GetDesignSettings()
LAYERS = [pcbnew.F_Cu, pcbnew.B_Cu] + ([b.GetLayerID(a.wewn)] if a.wewn else [])
PLANES = [b.GetLayerID(x) for x in a.plaszczyzny.split(",") if x]
WEWN = [b.GetLayerID(a.wewn)] if a.wewn else []
LAYERS += [l for l in PLANES if l not in LAYERS]
NL = len(LAYERS)
CLR_MIN = max(TM(ds.m_MinClearance), 0.15)
EDGE = max(TM(ds.m_CopperEdgeClearance), 0.3) + 0.05   # zapas na siatkę
HOLE = 0.3   # odstęp otworów 0,25 + zapas na siatkę


def drc_pairs():
    with tempfile.NamedTemporaryFile(suffix=".rpt") as t:
        pcbnew.WriteDRCReport(b, t.name, pcbnew.EDA_UNITS_MILLIMETRES, True)
        s = open(t.name).read()
    out = []
    for blk in s.split("[unconnected_items]")[1:]:
        items = re.findall(r"@\(([-\d.]+) mm, ([-\d.]+) mm\): (\w+)[^\[]*\[([^\]]*)\]", blk.split("\n[")[0])
        if len(items) >= 2:
            out.append(items[:2])
    return out


def find_item(x, y, kind, net):
    p = pcbnew.VECTOR2I(MM(float(x)), MM(float(y)))
    best = None
    if kind in ("PTH", "NPTH", "SMD"):
        kind = "Pad"
    if kind == "Pad":
        for pad in b.GetPads():
            if pad.GetNetname() == net and pad.GetPosition() == p:
                return pad
        for pad in b.GetPads():
            if pad.GetNetname() == net and pad.HitTest(p, MM(0.01)):
                return pad
    if kind in ("Track", "Via", "Arc"):
        for t in b.GetTracks():
            if t.GetNetname() == net and t.HitTest(p, MM(0.01)):
                return t
        cand = [t for t in b.GetTracks() if t.GetNetname() == net]
        if cand:   # najbliższa ścieżka tej sieci
            return min(cand, key=lambda t: min((t.GetStart() - p).EuclideanNorm(), (t.GetEnd() - p).EuclideanNorm()))
    if kind == "Zone":
        zs = [z for z in b.Zones() if z.GetNetname() == net and not z.GetIsRuleArea()]
        for z in zs:
            if z.HitTestFilledArea(z.GetLayer(), p):
                return z
        if zs:
            return zs[0]
    return best


def item_layers(it):
    if isinstance(it, pcbnew.PAD):
        return [l for l in LAYERS if it.IsOnLayer(l)]
    if isinstance(it, pcbnew.PCB_VIA):
        return list(LAYERS)
    if isinstance(it, pcbnew.ZONE):
        return [l for l in LAYERS if it.IsOnLayer(l)]
    return [it.GetLayer()] if it.GetLayer() in LAYERS else []


def poly_of(it, layer, infl=0.0):
    ps = pcbnew.SHAPE_POLY_SET()
    if isinstance(it, pcbnew.ZONE):
        fp = it.GetFilledPolysList(layer)
        ps = fp.CloneDropTriangulation() if hasattr(fp, "CloneDropTriangulation") else fp
        if infl > 0:
            ps = pcbnew.SHAPE_POLY_SET(ps)
            ps.Inflate(MM(infl), 8)
        return ps
    it.TransformShapeToPolygon(ps, layer, MM(infl), MM(0.005))
    return ps


def draw_item(W, d, it, L):
    """kształt elementu miedzi na warstwie L (bez odstępu) narysowany w oknie W"""
    if isinstance(it, pcbnew.PCB_VIA):
        c = it.GetPosition()
        r = TM(it.GetWidth()) / 2 / G
        cx, cy = (TM(c.x) - W.x0) / G, (TM(c.y) - W.y0) / G
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=1)
    elif isinstance(it, pcbnew.PCB_TRACK):
        pts = [it.GetStart(), it.GetEnd()]
        if isinstance(it, pcbnew.PCB_ARC):
            pts = [it.GetStart(), it.GetMid(), it.GetEnd()]
        r = TM(it.GetWidth()) / 2 / G
        q = [((TM(p.x) - W.x0) / G, (TM(p.y) - W.y0) / G) for p in pts]
        d.line(q, fill=1, width=max(1, int(round(2 * r))))
        for cx, cy in q:
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=1)
    else:
        W.draw_poly(d, poly_of(it, L))


class Win:
    def __init__(s, x0, y0, x1, y1):
        s.x0, s.y0 = x0, y0
        s.nx, s.ny = int((x1 - x0) / G) + 1, int((y1 - y0) / G) + 1

    def img(s):
        im = Image.new("1", (s.nx, s.ny), 0)
        return im, ImageDraw.Draw(im)

    def draw_poly(s, d, ps):
        for i in range(ps.OutlineCount()):
            o = ps.Outline(i)
            pts = [((TM(o.CPoint(k).x) - s.x0) / G, (TM(o.CPoint(k).y) - s.y0) / G) for k in range(o.PointCount())]
            if len(pts) >= 3:
                d.polygon(pts, fill=1)
            for h in range(ps.HoleCount(i)):
                ho = ps.Hole(i, h)
                hp = [((TM(ho.CPoint(k).x) - s.x0) / G, (TM(ho.CPoint(k).y) - s.y0) / G) for k in range(ho.PointCount())]
                if len(hp) >= 3:
                    d.polygon(hp, fill=0)


import fnmatch, json, os
_pro = json.load(open(os.path.splitext(a.pcb)[0] + ".kicad_pro"))["net_settings"]
_cls = {c["name"]: c for c in _pro["classes"]}


def net_clear(net):
    c = _cls["Default"]
    for pat in _pro.get("netclass_patterns", []):
        if fnmatch.fnmatchcase(net, pat["pattern"]):
            c = _cls[pat["netclass"]]
            break
    return max(c["clearance"], CLR_MIN), c["track_width"], c["via_diameter"], c["via_drill"]


def all_items():
    # wylewki: przeszkodą są tylko strefy mocy (MOC_*); masa i płaszczyzny wypełnią się na nowo wokół nowych ścieżek
    its = list(b.GetPads()) + list(b.GetTracks()) + [z for z in b.Zones() if not z.GetIsRuleArea() and z.GetZoneName().startswith("MOC_")]
    return its


def route1(pair, m, grid):
    global G
    G = grid
    (xa, ya, ka, net), (xb, yb, kb, net2) = pair
    A, B = find_item(xa, ya, ka, net), find_item(xb, yb, kb, net2)
    if A is None or B is None:
        return "brak elementu"
    plane = [z for z in b.Zones() if z.GetZoneName() == f"PL_{net}"]
    if plane:   # sieć z płaszczyzną: każdy kawałek łączymy przelotką z płaszczyzną
        if isinstance(A, pcbnew.ZONE) or (not isinstance(A, pcbnew.PAD) and isinstance(B, pcbnew.PAD)):
            A, ka, xa, ya = B, kb, xb, yb   # start zawsze od padu (przelotka/ścieżka zwykle już jest w płaszczyźnie)
        B, kb = plane[0], "Zone"
    if ka == "Zone":
        xa, ya = xb, yb
    if kb == "Zone":
        xb, yb = xa, ya
    clr, w0, vd, vdr = net_clear(net)
    sv = tuple(map(float, a.mala_przelotka.split("/")))
    VIAS = [(vd, vdr)] + ([sv] if sv[0] < vd else [])
    clr += 0.03   # zapas na zaokrąglenia siatki
    ax, ay, bx, by = float(xa), float(ya), float(xb), float(yb)
    ebb = b.GetBoardEdgesBoundingBox()
    x0, y0 = max(min(ax, bx) - m, TM(ebb.GetLeft())), max(min(ay, by) - m, TM(ebb.GetTop()))
    x1, y1 = min(max(ax, bx) + m, TM(ebb.GetRight())), min(max(ay, by) + m, TM(ebb.GetBottom()))
    W = Win(x0, y0, x1, y1)
    wbox = pcbnew.BOX2I(pcbnew.VECTOR2I(MM(x0 - 3), MM(y0 - 3)), pcbnew.VECTOR2I(MM(x1 - x0 + 6), MM(y1 - y0 + 6)))
    raw = []      # surowa miedź innych sieci na warstwę
    rawx = {}     # (warstwa, dodatkowy odstęp) → maska
    clr0 = net_clear(net)[0]
    same = []     # miedź tej sieci (przejezdna)
    holes_im, holes_d = W.img()
    keep_im, keep_d = W.img()
    for L in LAYERS:
        raw.append(W.img())
        same.append(W.img())
    for it in all_items():
        if not it.GetBoundingBox().Intersects(wbox):
            continue
        mine = it.GetNetname() == net and it.GetNetCode() > 0
        for li, L in enumerate(LAYERS):
            if not it.IsOnLayer(L):
                continue
            if isinstance(it, pcbnew.ZONE) and L != it.GetLayer() and not it.IsOnLayer(L):
                continue
            if mine:
                draw_item(W, same[li][1], it, L)
            else:   # osobna maska dla miedzi sieci z większym odstępem klasy (KiCad bierze większy z dwóch)
                oc = net_clear(it.GetNetname())[0] if it.GetNetCode() > 0 else CLR_MIN
                key = (li, round(max(oc - clr0, 0), 3))
                if key not in rawx:
                    rawx[key] = W.img()
                draw_item(W, rawx[key][1], it, L)
                draw_item(W, raw[li][1], it, L)
        if isinstance(it, (pcbnew.PAD, pcbnew.PCB_VIA)):
            dr = TM(it.GetDrillSize().x) if isinstance(it, pcbnew.PAD) else TM(it.GetDrillValue())
            if dr > 0:
                c = it.GetPosition()
                r = (dr / 2 + HOLE + vdr / 2) / G
                cx, cy = (TM(c.x) - x0) / G, (TM(c.y) - y0) / G
                holes_d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=1)
    rule_areas = [z for z in b.Zones()] + [z for f in b.GetFootprints() for z in f.Zones()]
    for z in rule_areas:
        if z.GetIsRuleArea() and z.GetBoundingBox().Intersects(wbox):
            ps = pcbnew.SHAPE_POLY_SET(z.Outline())
            W.draw_poly(keep_d, ps)
    # wnętrze płytki
    edge_im, edge_d = W.img()
    outl = pcbnew.SHAPE_POLY_SET()
    b.GetBoardPolygonOutlines(outl)
    W.draw_poly(edge_d, outl)
    inside = np.array(edge_im, dtype=bool)
    rawA = [np.array(r[0], dtype=bool) for r in raw]
    sameA = [np.array(s[0], dtype=bool) for s in same]
    holesA = np.array(holes_im, dtype=bool)
    keepA = np.array(keep_im, dtype=bool)

    def disk(r):
        r = int(math.ceil(r / G))
        yy, xx = np.ogrid[-r:r + 1, -r:r + 1]
        return xx * xx + yy * yy <= r * r

    # cele: kształt A (start) i B (meta)
    def region(it):
        if isinstance(it, pcbnew.ZONE):   # meta: wszystkie wylewki tej sieci na wszystkich warstwach
            out = []
            for li, L in enumerate(LAYERS):
                im, d = W.img()
                for z in b.Zones():
                    if z.GetNetname() == it.GetNetname() and not z.GetIsRuleArea() and z.GetLayer() == L:
                        ps = poly_of(z, L)
                        if L in PLANES and ps.OutlineCount() > 1:   # płaszczyzna: tylko główny (największy) kawałek
                            k = max(range(ps.OutlineCount()), key=lambda i: abs(ps.Outline(i).Area()))
                            one = pcbnew.SHAPE_POLY_SET()
                            one.AddOutline(ps.Outline(k))
                            for h in range(ps.HoleCount(k)):
                                one.AddHole(ps.Hole(k, h))
                            ps = one
                        W.draw_poly(d, ps)
                out.append(np.array(im, dtype=bool))
            return out
        out = []
        for li, L in enumerate(LAYERS):
            im, d = W.img()
            if it.IsOnLayer(L) or (isinstance(it, pcbnew.ZONE) and it.GetLayer() == L):
                draw_item(W, d, it, L)
            out.append(np.array(im, dtype=bool))
        return out

    RA, RB = region(A), region(B)
    if not any(r.any() for r in RA) or not any(r.any() for r in RB):
        return "element poza warstwami routera"
    widths = [w0] + [w for w in (0.4, 0.3, 0.25, 0.2) if w < w0 and w >= a.min_szer]
    for w in widths:
        inner = ndimage.binary_erosion(inside, disk(EDGE + w / 2))
        free = [inner & ~ndimage.binary_dilation(rawA[li], disk(clr + w / 2)) & ~keepA for li in range(NL)]
        for (li, extra), (im_, _d) in rawx.items():
            if extra > 0:
                free[li] &= ~ndimage.binary_dilation(np.array(im_, dtype=bool), disk(clr + extra + w / 2))
        for vd, vdr in VIAS:
          vfree = ndimage.binary_erosion(inside, disk(EDGE + vd / 2)) & ~holesA & ~keepA
          for li in range(NL):
            vfree &= ~ndimage.binary_dilation(rawA[li], disk(clr + vd / 2))
          for (li, extra), (im_, _d) in rawx.items():
            if extra > 0:
                vfree &= ~ndimage.binary_dilation(np.array(im_, dtype=bool), disk(clr + extra + vd / 2))
          # we wnętrzu padów startu/mety wolno (pad tej samej sieci); płaszczyzny: tylko cel
          for li in range(NL):
            if LAYERS[li] in PLANES and LAYERS[li] not in WEWN:
                free[li] = RB[li] & vfree
            free[li] |= RA[li] | RB[li]
          if DEBUG:
            for li in range(NL):
                im = np.zeros((W.ny, W.nx, 3), np.uint8)
                im[..., 0] = rawA[li] * 120
                im[..., 1] = free[li] * 160
                im[..., 2] = (RA[li] | RB[li]) * 255
                Image.fromarray(im).save(f"/tmp/claude-0/dbg_{li}.png")
            print("   dbg: inside", inside.mean(), "free", [f.mean() for f in free], "RA", [r.sum() for r in RA], "RB", [r.sum() for r in RB], "RA&free", [(RA[i] & free[i]).sum() for i in range(NL)])
          path = dijkstra(free, vfree, RA, RB, W)
          if path:
            commit(path, W, net, w, vd, vdr)
            return f"OK {w:.2f} mm, przelotka {vd}/{vdr}, okno {m} mm, siatka {grid}"
    return "nie przeszło"


def route(pair):
    for m, grid in ((2.5, 0.025), (8, 0.05), (a.margines, 0.05)):
        r = route1(pair, m, grid)
        if r != "nie przeszło":
            return r
    return r


def dijkstra(free, vfree, RA, RB, W):
    ny, nx = W.ny, W.nx
    INF = 1e18
    dist = np.full((NL, ny, nx), INF)
    prev = {}
    h = []
    tgt = np.stack(RB)
    tys, txs = np.nonzero(np.any(tgt, axis=0))
    tcx, tcy = txs.mean(), tys.mean()
    for li in range(NL):
        ys, xs = np.nonzero(RA[li] & free[li])
        if len(xs) > 400:
            sel = np.linspace(0, len(xs) - 1, 400).astype(int)
            ys, xs = ys[sel], xs[sel]
        for y, x in zip(ys, xs):
            dist[li, y, x] = 0
            heapq.heappush(h, (math.hypot(x - tcx, y - tcy) * 0.9, 0.0, li, y, x))
    VIA = 25.0
    steps = [(1, 0, 1), (-1, 0, 1), (0, 1, 1), (0, -1, 1), (1, 1, 1.4142), (1, -1, 1.4142), (-1, 1, 1.4142), (-1, -1, 1.4142)]
    n = 0
    while h:
        f, d, li, y, x = heapq.heappop(h)
        if d > dist[li, y, x]:
            continue
        n += 1
        if n > 3_000_000:
            return None
        if tgt[li, y, x]:
            path = [(li, y, x)]
            k = (li, y, x)
            while k in prev:
                k = prev[k]
                path.append(k)
            return path[::-1]
        for dx, dy, c in steps:
            X, Y = x + dx, y + dy
            if 0 <= X < nx and 0 <= Y < ny and free[li][Y, X]:
                if dx and dy and not (free[li][y, X] and free[li][Y, x]):
                    continue
                nd = d + c
                if nd < dist[li, Y, X]:
                    dist[li, Y, X] = nd
                    prev[(li, Y, X)] = (li, y, x)
                    heapq.heappush(h, (nd + math.hypot(X - tcx, Y - tcy) * 0.9, nd, li, Y, X))
        if vfree[y, x]:
            for L2 in range(NL):
                if L2 != li and free[L2][y, x]:
                    nd = d + VIA
                    if nd < dist[L2, y, x]:
                        dist[L2, y, x] = nd
                        prev[(L2, y, x)] = (li, y, x)
                        heapq.heappush(h, (nd + math.hypot(x - tcx, y - tcy) * 0.9, nd, L2, y, x))
    return None


def commit(path, W, net, w, vd, vdr):
    ni = b.FindNet(net)
    pt = lambda y, x: pcbnew.VECTOR2I(MM(float(W.x0 + x * G)), MM(float(W.y0 + y * G)))
    seg_start = path[0]
    prev = path[0]
    pdir = None
    pts = []
    for cur in path[1:]:
        if cur[0] != prev[0]:   # przelotka
            if prev != seg_start:
                pts.append((seg_start, prev))
            v = pcbnew.PCB_VIA(b)
            v.SetPosition(pt(cur[1], cur[2]))
            v.SetWidth(MM(vd))
            v.SetDrill(MM(vdr))
            v.SetNet(ni)
            b.Add(v)
            seg_start, prev, pdir = cur, cur, None
            continue
        dr = (cur[1] - prev[1], cur[2] - prev[2])
        if pdir is not None and dr != pdir:
            pts.append((seg_start, prev))
            seg_start = prev
        pdir = dr
        prev = cur
    if prev != seg_start:
        pts.append((seg_start, prev))
    for s, e in pts:
        t = pcbnew.PCB_TRACK(b)
        t.SetStart(pt(s[1], s[2]))
        t.SetEnd(pt(e[1], e[2]))
        t.SetWidth(MM(w))
        t.SetLayer(LAYERS[s[0]])
        t.SetNet(ni)
        b.Add(t)


def napraw():
    with tempfile.NamedTemporaryFile(suffix=".rpt") as t:
        pcbnew.WriteDRCReport(b, t.name, pcbnew.EDA_UNITS_MILLIMETRES, True)
        s = open(t.name).read()
    bad = []
    for blk in re.split(r"\n(?=\[)", s):
        if blk.startswith(("[clearance]", "[copper_edge_clearance]", "[hole_clearance]", "[tracks_crossing]", "[shorting_items]",
                           "[hole_near_hole]", "[items_not_allowed]", "[track_dangling]", "[via_dangling]", "[via_diameter]", "[drill_out_of_range]", "[annular_width]")):
            for x, y, kind, net, ln in re.findall(r"@\(([-\d.]+) mm, ([-\d.]+) mm\): (Track|Via)[^\[]*\[([^\]]*)\][^\n]*?(?:length ([\d.]+) mm)?\n", blk + "\n"):
                it = find_item(x, y, kind, net)
                if kind == "Track" and ln:   # wybierz odcinek o tej długości (raport podaje długość)
                    p_ = pcbnew.VECTOR2I(MM(float(x)), MM(float(y)))
                    c_ = [t for t in b.GetTracks() if t.GetNetname() == net and t.GetClass() == "PCB_TRACK" and abs(TM(t.GetLength()) - float(ln)) < 0.002]
                    if c_:
                        it = min(c_, key=lambda t: min((t.GetStart() - p_).EuclideanNorm(), (t.GetEnd() - p_).EuclideanNorm()))
                if it is not None and not it.IsLocked() and it not in bad:
                    bad.append(it)
    if a.zwolnij:
        fps = {f.GetReference(): f for f in b.GetFootprints()}
        for ref in a.zwolnij.split(","):
            c = fps[ref].GetCourtyard(pcbnew.F_Cu if not fps[ref].IsFlipped() else pcbnew.B_Cu)
            c = fps[ref].GetCourtyard(pcbnew.F_CrtYd)
            bb = c.BBox() if c.OutlineCount() else fps[ref].GetBoundingBox(False, False)
            bb.Inflate(MM(1))
            for t in b.GetTracks():
                if not t.IsLocked() and t.GetBoundingBox().Intersects(bb) and t not in bad:
                    bad.append(t)
    for xy in [q for q in a.wokol.split(";") if q]:
        x_, y_ = map(float, xy.split(","))
        c_ = pcbnew.VECTOR2I(MM(x_), MM(y_))
        own = {p.GetNetCode() for p in b.GetPads() if p.HitTest(c_, MM(0.05))}
        for t in b.GetTracks():
            if t.IsLocked() or t.GetNetCode() in own or t in bad:
                continue
            if t.GetClass() == "PCB_VIA":
                dd = (t.GetPosition() - c_).EuclideanNorm()
            else:
                s_, e_ = t.GetStart(), t.GetEnd()
                vx, vy = e_.x - s_.x, e_.y - s_.y
                L2 = vx * vx + vy * vy
                u = max(0.0, min(1.0, ((c_.x - s_.x) * vx + (c_.y - s_.y) * vy) / L2)) if L2 else 0.0
                dd = math.hypot(s_.x + u * vx - c_.x, s_.y + u * vy - c_.y)
            if dd < MM(1.2):
                bad.append(t)
    for it in bad:
        b.Remove(it)
    print("usunięte (naruszenia / zwolnione miejsce):", len(bad))


if a.napraw or a.zwolnij or a.wokol:
    napraw()
    b.Save(a.pcb)
    # dalej w nowym procesie (KiCad 7 po usunięciu elementów w tym samym procesie potrafi się wysypać)
    import os
    args, skip = [], False
    for x in sys.argv:
        if skip:
            skip = False
            continue
        if x == "--zwolnij":
            skip = True
            continue
        if x == "--wokol":
            skip = True
            continue
        if x != "--napraw" and not x.startswith(("--zwolnij=", "--wokol=")):
            args.append(x)
    os.execv(sys.executable, [sys.executable] + args)
done = set()
for it in range(6):
    if any(True for _ in b.Zones()):
        pcbnew.ZONE_FILLER(b).Fill(b.Zones())
    pairs = drc_pairs()
    first = set(a.najpierw.split(",")) if a.najpierw else set()
    skip = set(a.pomin.split(",")) if a.pomin else set()
    pairs = [p for p in pairs if p[0][3] not in skip]
    pairs.sort(key=lambda p: (p[0][3] not in first, math.hypot(float(p[0][0]) - float(p[1][0]), float(p[0][1]) - float(p[1][1]))))
    print(f"runda {it + 1}: niepołączone {len(pairs)}", flush=True)
    if not pairs:
        break
    ok = 0
    for p in pairs[: a.max]:
        key = (p[0][:2], p[1][:2])
        if key in done:
            continue
        done.add(key)
        r = route(p)
        print(f"  {p[0][3]:<20} {p[0][2]} ({p[0][0]},{p[0][1]}) → {p[1][2]}: {r}", flush=True)
        if r.startswith("OK"):
            ok += 1
    if not ok:
        break
for z in b.Zones():
    pass
pcbnew.ZONE_FILLER(b).Fill(b.Zones())
b.Save(a.pcb)
print("niepołączone po:", len(drc_pairs()))
