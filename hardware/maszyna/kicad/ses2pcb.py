"""Import wyniku Freeroutingu (Specctra SES) do maszyna.kicad_pcb bez GUI KiCad (w KiCad 7 python
pcbnew.ImportSpecctraSES działa tylko w oknie). Użycie: python3 ses2pcb.py maszyna.ses [płytka.kicad_pcb]
Usuwa stare ścieżki (poza zablokowanymi), dodaje ścieżki i przelotki z SES, wypełnia strefy."""
import re
import sys

import pcbnew


def tokens(s):
    return re.findall(r'"[^"]*"|\(|\)|[^\s()]+', s)


def parse(tok, i=0):
    out = []
    while i < len(tok):
        t = tok[i]
        if t == "(":
            sub, i = parse(tok, i + 1)
            out.append(sub)
        elif t == ")":
            return out, i + 1
        else:
            out.append(t.strip('"'))
            i += 1
    return out, i


def find(node, name):
    return [n for n in node if isinstance(n, list) and n and n[0] == name]


def main(ses, pcb):
    tree, _ = parse(tokens(open(ses, encoding="utf-8").read()))
    root = tree[0]
    routes = find(root, "routes")[0]
    res = find(routes, "resolution")[0]
    scale = {"um": 1000, "mm": 1_000_000, "mil": 25400}[res[1]] // int(res[2])  # → nm
    b = pcbnew.LoadBoard(pcb)
    for t in list(b.GetTracks()):
        if not t.IsLocked():
            b.Remove(t)
    vias = {}
    for ps in find(find(routes, "library_out")[0], "padstack") if find(routes, "library_out") else []:
        circ = [s for s in find(ps, "shape") if isinstance(s[1], list) and s[1][0] == "circle"]
        d = int(circ[0][1][2]) * scale if circ else 600_000
        m = re.search(r"_(\d+):(\d+)_um", ps[1])
        vias[ps[1]] = (d, int(m.group(2)) * 1000 if m else d // 2)
    nt = 0
    nv = 0
    for net in find(find(routes, "network_out")[0], "net"):
        ni = b.FindNet(net[1])
        for w in find(net, "wire"):
            p = find(w, "path")[0]
            layer = b.GetLayerID(p[1])
            width = int(p[2]) * scale
            pts = [(int(p[k]) * scale, -int(p[k + 1]) * scale) for k in range(3, len(p) - 1, 2)]
            for a, c in zip(pts, pts[1:]):
                t = pcbnew.PCB_TRACK(b)
                t.SetStart(pcbnew.VECTOR2I(*a))
                t.SetEnd(pcbnew.VECTOR2I(*c))
                t.SetWidth(width)
                t.SetLayer(layer)
                t.SetNet(ni)
                b.Add(t)
                nt += 1
        for v in find(net, "via"):
            d, drill = vias.get(v[1], (600_000, 300_000))
            x = pcbnew.PCB_VIA(b)
            x.SetPosition(pcbnew.VECTOR2I(int(v[2]) * scale, -int(v[3]) * scale))
            x.SetViaType(pcbnew.VIATYPE_THROUGH)
            x.SetWidth(d)
            x.SetDrill(drill)
            x.SetNet(ni)
            b.Add(x)
            nv += 1
    pcbnew.ZONE_FILLER(b).Fill(b.Zones())
    b.Save(pcb)
    print(f"ścieżki: {nt}, przelotki: {nv}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "maszyna.kicad_pcb")
