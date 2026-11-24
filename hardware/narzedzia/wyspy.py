"""
Łączenie „wysp” wylewki masy: kawałki wylewki GND (z padami), które nie mają połączenia z resztą masy.
Liczy połączenia geometrycznie (wyspy F.Cu / B.Cu, przelotki, pady przewlekane, ścieżki GND), a każdą odciętą
grupę łączy z główną masą routerem z dotrasuj.py (ścieżka + przelotka). Po nim: wylewki.py (ponowne wylanie).
Użycie: /usr/bin/python3 wyspy.py plytka.kicad_pcb [--mala-przelotka 0.56/0.3]
"""
import os
import sys

from shapely.geometry import Point, Polygon, LineString
from shapely.ops import unary_union

here = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[1:]
sys.argv = ["dotrasuj.py"] + args
exec(open(os.path.join(here, "dotrasuj.py")).read().split("def napraw():")[0])

NET = "GND"


def shp(ps):
    out = []
    for i in range(ps.OutlineCount()):
        o = ps.Outline(i)
        ext = [(TM(o.CPoint(k).x), TM(o.CPoint(k).y)) for k in range(o.PointCount())]
        holes = []
        for h in range(ps.HoleCount(i)):
            ho = ps.Hole(i, h)
            holes.append([(TM(ho.CPoint(k).x), TM(ho.CPoint(k).y)) for k in range(ho.PointCount())])
        if len(ext) >= 3:
            out.append(Polygon(ext, holes).buffer(0))
    return out


def components():
    nodes = []   # (warstwa, kształt)
    for z in b.Zones():
        if z.GetNetname() == NET and not z.GetIsRuleArea() and z.GetLayer() in (pcbnew.F_Cu, pcbnew.B_Cu):
            for p in shp(z.GetFilledPolysList(z.GetLayer())):
                nodes.append((z.GetLayer(), p))
    links = []   # elementy łączące: (zbiór warstw, kształt)
    for t in b.GetTracks():
        if t.GetNetname() != NET:
            continue
        if t.GetClass() == "PCB_VIA":
            c = t.GetPosition()
            links.append(({pcbnew.F_Cu, pcbnew.B_Cu}, Point(TM(c.x), TM(c.y)).buffer(TM(t.GetWidth()) / 2)))
        else:
            a_, c_ = t.GetStart(), t.GetEnd()
            links.append(({t.GetLayer()}, LineString([(TM(a_.x), TM(a_.y)), (TM(c_.x), TM(c_.y))]).buffer(TM(t.GetWidth()) / 2)))
    for p in b.GetPads():
        if p.GetNetname() != NET:
            continue
        lays = {l for l in (pcbnew.F_Cu, pcbnew.B_Cu) if p.IsOnLayer(l)}
        ps = pcbnew.SHAPE_POLY_SET()
        p.TransformShapeToPolygon(ps, list(lays)[0], 0, MM(0.01))
        for s in shp(ps):
            links.append((lays, s))
    n = len(nodes) + len(links)
    par = list(range(n))

    def f(x):
        while par[x] != x:
            par[x] = par[par[x]]
            x = par[x]
        return x
    allsh = [({l}, s) for l, s in nodes] + links
    from shapely.strtree import STRtree
    tree = STRtree([s for _, s in allsh])
    for i, (li, si) in enumerate(allsh):
        for j in tree.query(si):
            j = int(j)
            if j <= i:
                continue
            lj, sj = allsh[j]
            if li & lj and si.intersects(sj):
                par[f(i)] = f(j)
    comp = {}
    for i in range(n):
        comp.setdefault(f(i), []).append(i)
    return allsh, list(comp.values())


allsh, comps = components()
comps.sort(key=lambda c: -sum(allsh[i][1].area for i in c))
main = comps[0]
lonely = [c for c in comps[1:] if any(isinstance(allsh[i][1], Polygon) for i in c)]
print("grupy masy:", len(comps), "odcięte (z miedzią):", len(lonely))
ok = 0
failed = []
for c in lonely:
    geo = unary_union([allsh[i][1] for i in c])
    x0_, y0_, x1_, y1_ = geo.bounds
    done_ = False
    for m, grid in ((3, 0.025), (8, 0.05)):
        globals()["G"] = grid
        W = Win(x0_ - m, y0_ - m, x1_ + m, y1_ + m)
        mainsh = [(allsh[i][0], allsh[i][1]) for i in main if allsh[i][1].intersects(Polygon([(W.x0, W.y0), (W.x0 + W.nx * G, W.y0), (W.x0 + W.nx * G, W.y0 + W.ny * G), (W.x0, W.y0 + W.ny * G)]))]
        if not mainsh:
            continue

        def rast(items):
            out = []
            for L in LAYERS:
                im, d = W.img()
                for lays, s in items:
                    if L in lays:
                        for g in getattr(s, "geoms", [s]):
                            pts = [((x - W.x0) / G, (y - W.y0) / G) for x, y in g.exterior.coords]
                            if len(pts) >= 3:
                                d.polygon(pts, fill=1)
                out.append(np.array(im, dtype=bool))
            return out
        RA = rast([(allsh[i][0], allsh[i][1]) for i in c])
        RB = rast(mainsh)
        RB = [RB[li] & ~ndimage.binary_dilation(RA[li], iterations=3) for li in range(NL)]   # bez styku na skutek rasteryzacji
        # przeszkody: miedź innych sieci (jak w dotrasuj.route1)
        raw = [W.img() for _ in LAYERS]
        holes_im, holes_d = W.img()
        wbox = pcbnew.BOX2I(pcbnew.VECTOR2I(MM(W.x0 - 3), MM(W.y0 - 3)), pcbnew.VECTOR2I(MM(W.nx * G + 6), MM(W.ny * G + 6)))
        clr, w0, vd, vdr = net_clear(NET)
        clr += 0.03
        for it in all_items():
            if not it.GetBoundingBox().Intersects(wbox):
                continue
            if it.GetNetname() == NET:
                continue
            for li, L in enumerate(LAYERS):
                if it.IsOnLayer(L):
                    draw_item(W, raw[li][1], it, L)
            if isinstance(it, (pcbnew.PAD, pcbnew.PCB_VIA)):
                dr = TM(it.GetDrillSize().x) if isinstance(it, pcbnew.PAD) else TM(it.GetDrillValue())
                if dr > 0:
                    q = it.GetPosition()
                    r = (dr / 2 + HOLE + vdr / 2) / G
                    cx, cy = (TM(q.x) - W.x0) / G, (TM(q.y) - W.y0) / G
                    holes_d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=1)
        rawA = [np.array(r_[0], dtype=bool) for r_ in raw]
        edge_im, edge_d = W.img()
        outl = pcbnew.SHAPE_POLY_SET()
        b.GetBoardPolygonOutlines(outl)
        W.draw_poly(edge_d, outl)
        inside = np.array(edge_im, dtype=bool)

        def disk(r):
            r = int(math.ceil(r / G))
            yy, xx = np.ogrid[-r:r + 1, -r:r + 1]
            return xx * xx + yy * yy <= r * r
        sv = tuple(map(float, a.mala_przelotka.split("/")))
        for w in (0.4, 0.3, 0.25):
            inner = ndimage.binary_erosion(inside, disk(EDGE + w / 2))
            free = [inner & ~ndimage.binary_dilation(rawA[li], disk(clr + w / 2)) for li in range(NL)]
            for vd_, vdr_ in [(vd, vdr), sv]:
                vfree = inner & ~np.array(holes_im, dtype=bool)
                for li in range(NL):
                    vfree &= ~ndimage.binary_dilation(rawA[li], disk(clr + vd_ / 2))
                fr = [free[li] | RA[li] | RB[li] for li in range(NL)]
                path = dijkstra(fr, vfree, RA, RB, W)
                if path:
                    commit(path, W, NET, w, vd_, vdr_)
                    done_ = True
                    break
            if done_:
                break
        if done_:
            break
    ok += done_
    print(f"  grupa przy ({x0_:.1f},{y0_:.1f}): {'połączona' if done_ else 'NIE'}")
    if not done_:
        failed.append(geo)
b.Save(a.pcb)
if failed and os.environ.get("PRZEBIJ"):
    # korytarz od odciętej grupy do najbliższej głównej masy: usuń ścieżki innych sieci na B.Cu, które go przecinają
    # (potem: wyspy.py jeszcze raz → dotrasuj.py poprowadzi usunięte sygnały inną drogą)
    from shapely.ops import nearest_points
    mainB = unary_union([allsh[i][1] for i in main if pcbnew.B_Cu in allsh[i][0]])
    rm = []
    for geo in failed:
        p1, p2 = nearest_points(geo, mainB)
        cor = LineString([p1, p2]).buffer(0.7)
        for t in b.GetTracks():
            if t.GetClass() == "PCB_TRACK" and t.GetLayer() == pcbnew.B_Cu and t.GetNetname() != NET and not t.IsLocked():
                s_, e_ = t.GetStart(), t.GetEnd()
                if LineString([(TM(s_.x), TM(s_.y)), (TM(e_.x), TM(e_.y))]).buffer(TM(t.GetWidth()) / 2).intersects(cor) and t not in rm:
                    rm.append(t)
    for t in rm:
        b.Remove(t)
    b.Save(a.pcb)
    print("przebite korytarze — usunięte ścieżki B.Cu:", len(rm))
print("połączone grupy:", ok, "z", len(lonely))
