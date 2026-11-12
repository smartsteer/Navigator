"""
Rozmieszczenie płytki Maszyna (etap 16b, część 2): obrys 165 × 120 mm, 4 warstwy (F.Cu sygnały, In1 masa,
In2 +12 V, B.Cu sygnały), duże elementy w strefach, drobne elementy obok „swojego” układu, klasy ścieżek.
Uruchomienie: python3 place.py  (zmienia maszyna.kicad_pcb)
"""
import math
import pcbnew

MM = pcbnew.FromMM
W, H = 165.0, 120.0
B = pcbnew.LoadBoard("maszyna.kicad_pcb")

# ---------------------------------------------------------------- warstwy i obrys
B.SetCopperLayerCount(4)
for x1, y1, x2, y2 in ((0, 0, W, 0), (W, 0, W, H), (W, H, 0, H), (0, H, 0, 0)):
    s = pcbnew.PCB_SHAPE(B)
    s.SetShape(pcbnew.SHAPE_T_SEGMENT)
    s.SetStart(pcbnew.VECTOR2I(MM(x1), MM(y1)))
    s.SetEnd(pcbnew.VECTOR2I(MM(x2), MM(y2)))
    s.SetLayer(pcbnew.Edge_Cuts)
    s.SetWidth(MM(0.1))
    B.Add(s)

fp = {f.GetReference(): f for f in B.GetFootprints()}


def put(ref, x, y, rot=0, back=False):
    f = fp[ref]
    if back and not f.IsFlipped():
        f.Flip(f.GetPosition(), False)
    f.SetOrientationDegrees(rot)
    f.SetPosition(pcbnew.VECTOR2I(MM(x), MM(y)))
    f.SetLocked(True)


def ref_of(value_prefix):
    return sorted([r for r, f in fp.items() if f.GetValue().startswith(value_prefix)], key=lambda r: int("".join(c for c in r if c.isdigit())))


# ---------------------------------------------------------------- duże elementy
put([r for r, f in fp.items() if f.GetValue().startswith("AMPSEAL 35")][0], 56, 36, 180)
put([r for r, f in fp.items() if f.GetValue().startswith("AMPSEAL 23")][0], 110, 36, 180)
put([r for r, f in fp.items() if f.GetValue().startswith("ESP32")][0], 148, 20, -90)
put([r for r, f in fp.items() if f.GetValue().startswith("Quectel")][0], 140, 52)
# U.FL na linii pinu 11 (RF_IN) modułu GPS — ścieżka 50 Ω prosto, bez owijania (przegląd 2026-11-12)
put([r for r, f in fp.items() if f.GetValue().startswith("U.FL")][0], 156, 55.3)
put([r for r, f in fp.items() if f.GetValue() == "USB-C"][0], 160, 76, 90)
put([r for r, f in fp.items() if f.GetValue() == "CH340C"][0], 145, 76)
put([r for r, f in fp.items() if f.GetValue().startswith("TJA1051")][0], 140, 93)
put([r for r, f in fp.items() if f.GetValue().startswith("ADS1115")][0], 152, 101)
put([r for r, f in fp.items() if f.GetValue().startswith("74LVC1G17")][0], 128, 104)
# sterowniki zaworów: rząd odsunięty od H1 (+2,5 mm) i od kostek (+3,5 mm) — miejsce na rząd diod TVS pod złączami
for i, r in enumerate(ref_of("DRV8962")):
    put(r, 13.5 + i * 13.5, 63.5)
regs = ref_of("74HC595") + ref_of("74HC165")
for i, r in enumerate(regs):
    put(r, 12 + i * 13, 88)
# blok wejściowy tuż pod pinami zasilania kostki B (J2 1–6) i klucz lamp pod pinami 7–8 — krótka droga prądu 20–30 A
put([r for r, f in fp.items() if f.GetValue().startswith("BTS500")][0], 89, 47, 0)
put([r for r, f in fp.items() if f.GetValue().startswith("BUK7J")][0], 105, 43, 0)
put([r for r, f in fp.items() if f.GetValue().startswith("LM74700")][0], 113, 42)
put([r for r, f in fp.items() if f.GetValue() == "SMDJ26A"][0], 109, 51, 0)
put([r for r, f in fp.items() if f.GetValue() == "10A mini"][0], 35, 50.2, 0)   # bezpiecznik zaworów 3-pin przy pinach J1 27–30
for i, r in enumerate(sorted(r for r, f in fp.items() if f.GetValue().startswith("220u"))):
    put(r, 80 + i * 14, 111)
put([r for r, f in fp.items() if f.GetValue().startswith("LMR33610")][0], 30, 104)
put([r for r, f in fp.items() if f.GetValue() == "10uH 2A"][0], 42, 104)
put([r for r, f in fp.items() if f.GetValue().startswith("AMS1117")][0], 56, 104)
# tor RF GPS: U10 pin 11 → L2 (bias) → J7 pin 1 w jednej linii
u10 = [f for f in fp.values() if f.GetValue().startswith("Quectel")][0]
p11 = [p for p in u10.Pads() if p.GetNumber() == "11"][0].GetPosition()
j7 = [f for f in fp.values() if f.GetValue().startswith("U.FL")][0]
for rot in (0, 90, 180, 270):
    j7.SetOrientationDegrees(rot)
    j7.SetPosition(pcbnew.VECTOR2I(MM(155), p11.y))
    q = [p for p in j7.Pads() if p.GetNumber() == "1"][0].GetPosition()
    g = [p for p in j7.Pads() if p.GetNumber() == "2"][0].GetPosition()
    if g.x > q.x + MM(0.5):     # masa po prawej, sygnał patrzy w lewo (na U10)
        break
j7.SetPosition(pcbnew.VECTOR2I(j7.GetPosition().x, j7.GetPosition().y + (p11.y - q.y)))
l2 = [f for f in fp.values() if f.GetValue() == "47nH"][0]
for rot in (90, 270):
    l2.SetOrientationDegrees(rot)
    l2.SetPosition(pcbnew.VECTOR2I(MM(150), p11.y))
    pd = [p for p in l2.Pads() if p.GetNumber() == "2"][0].GetPosition()
    l2.SetPosition(pcbnew.VECTOR2I(MM(150), l2.GetPosition().y + (p11.y - pd.y)))
    pd1 = [p for p in l2.Pads() if p.GetNumber() == "1"][0].GetPosition()
    if pd1.y > p11.y:          # pad 1 (zasilanie anteny) poniżej linii RF
        break
l2.SetLocked(True)
j7.SetLocked(True)
q1 = [p for p in j7.Pads() if p.GetNumber() == "1"][0].GetPosition()
rfn = B.FindNet("GPS_RF")
t = pcbnew.PCB_TRACK(B)
t.SetStart(p11)
t.SetEnd(q1)
t.SetWidth(MM(0.36))
t.SetLayer(pcbnew.F_Cu)
t.SetNet(rfn)
t.SetLocked(True)
B.Add(t)

# diody TVS wyjść mostków: równe rzędy tuż pod pinami kostek
tvs = sorted([r for r, f in fp.items() if f.GetValue() == "PESD24VS1UL"], key=lambda r: int(r[1:]))
for n, r in enumerate(tvs[:26]):           # sekcje 1–13 → pod J1, dwa rzędy
    put(r, 10 + (n % 13) * 4.1, 41 + (n // 13) * 3.4, 90)
for n, r in enumerate(tvs[26:]):           # zawór główny, regulacyjny, rezerwa → pod J2, lewa strona
    put(r, 72.5 + (n % 2) * 3.0, 41 + (n // 2) * 3.4, 90)
# zworki i diody LED przy prawej krawędzi
for i, r in enumerate(sorted(r for r, f in fp.items() if f.GetValue().startswith("JP "))):
    put(r, 132 + i * 8, 114)
for i, r in enumerate(sorted(r for r, f in fp.items() if f.GetValue().startswith("LED "))):
    put(r, 160, 88 + i * 4, 90)
# otwory montażowe M3 (zgodne z obudową)
HOLES = [(4, 46), (161, 48), (4, 116), (161, 116)]
for x, y in HOLES:
    m = pcbnew.FootprintLoad("/usr/share/kicad/footprints/MountingHole.pretty", "MountingHole_3.2mm_M3")
    m.SetPosition(pcbnew.VECTOR2I(MM(x), MM(y)))
    m.SetReference(f"H{HOLES.index((x, y)) + 1}")
    m.SetLocked(True)
    B.Add(m)

# ---------------------------------------------------------------- drobne elementy obok „swojego” układu
POWER = {"GND", "V12", "V3V3", "V5", "V12_ZAWORY", "VIN_RAW"}
placed = [f for f in fp.values() if f.IsLocked()] + [f for f in B.GetFootprints() if f.GetReference().startswith("H")]


def bbox(f, margin=0.15):
    f.BuildCourtyardCaches()
    cy = f.GetCourtyard(pcbnew.B_CrtYd if f.IsFlipped() else pcbnew.F_CrtYd)
    if cy.OutlineCount() > 0:
        bb = cy.BBox()
        if f.GetReference().startswith("H"):
            margin = 2.0          # podkładka śruby M3
        return (bb.GetLeft() / 1e6 - margin, bb.GetTop() / 1e6 - margin, bb.GetRight() / 1e6 + margin, bb.GetBottom() / 1e6 + margin)
    bb = f.GetBoundingBox(False, False)
    return (bb.GetLeft() / 1e6 - margin, bb.GetTop() / 1e6 - margin, bb.GetRight() / 1e6 + margin, bb.GetBottom() / 1e6 + margin)


boxes = [bbox(f) for f in placed]
KEEP = [(130, 8, 165, 32)]  # antena ESP32 + moduł
KEEP.append((p11.x / 1e6 - 0.5, p11.y / 1e6 - 0.8, q1.x / 1e6 + 0.5, p11.y / 1e6 + 0.8))  # tor RF GPS


def free(b):
    if b[0] < 1 or b[1] < 1 or b[2] > W - 1 or b[3] > H - 1:
        return False
    for o in boxes + KEEP:
        if not (b[2] <= o[0] or b[0] >= o[2] or b[3] <= o[1] or b[1] >= o[3]):
            return False
    return True


big = {f.GetReference(): f for f in placed}
small = [f for f in fp.values() if not f.IsLocked()]


def owner(f):
    best = None
    for p in f.Pads():
        n = p.GetNetname()
        if not n or n in POWER:
            continue
        for g in big.values():
            if g.GetReference().startswith("H"):
                continue
            for q in g.Pads():
                if q.GetNetname() == n:
                    return g, q
    # tylko zasilanie: najbliższy układ z tą samą siecią zasilania (kondensatory odsprzęgające)
    return best


# kondensatory odsprzęgające bez „własnych” sygnałów rozdzielamy po układach z tym samym zasilaniem
orphans = []
for f in sorted(small, key=lambda f: f.GetReference()):
    o = owner(f)
    if o is None:
        orphans.append(f)
        continue
    g, pad = o
    cx, cy = pad.GetPosition().x / 1e6, pad.GetPosition().y / 1e6
    done = False
    for rad in [i * 0.5 for i in range(2, 80)]:
        for k in range(24):
            a = 2 * math.pi * k / 24
            x, y = cx + rad * math.cos(a), cy + rad * math.sin(a)
            for rot in (0, 90):
                f.SetOrientationDegrees(rot)
                f.SetPosition(pcbnew.VECTOR2I(MM(x), MM(y)))
                b = bbox(f)
                if free(b):
                    boxes.append(b)
                    done = True
                    break
            if done:
                break
        if done:
            break
    if not done:
        print("nie zmieściłem", f.GetReference())

# sieroty (odsprzęganie zasilań): w kolejce wolnych miejsc przy układach o tej samej sieci zasilania
supply_users = {}
for g in big.values():
    for q in g.Pads():
        if q.GetNetname() in ("V3V3", "V5", "V12"):
            supply_users.setdefault(q.GetNetname(), []).append(q)
rr = {}
for f in orphans:
    nets = {p.GetNetname() for p in f.Pads()} & {"V3V3", "V5", "V12"}
    n = next(iter(nets), "V12")
    lst = supply_users.get(n, [])
    i = rr.get(n, 0)
    rr[n] = i + 1
    q = lst[i % len(lst)] if lst else None
    cx, cy = (q.GetPosition().x / 1e6, q.GetPosition().y / 1e6) if q else (80, 70)
    done = False
    for rad in [i * 0.5 for i in range(2, 120)]:
        for k in range(24):
            a = 2 * math.pi * k / 24
            f.SetPosition(pcbnew.VECTOR2I(MM(cx + rad * math.cos(a)), MM(cy + rad * math.sin(a))))
            b = bbox(f)
            if free(b):
                boxes.append(b)
                done = True
                break
        if done:
            break
    if not done:
        print("nie zmieściłem", f.GetReference())

# klasy połączeń (szerokości ścieżek) — w maszyna.kicad_pro (netclass.py)

# ---------------------------------------------------------------- strefy: In1 masa, In2 +12 V, masa na F/B
def zone(netname, layer, prio=0, pts=((0.5, 0.5), (W - 0.5, 0.5), (W - 0.5, H - 0.5), (0.5, H - 0.5))):
    z = pcbnew.ZONE(B)
    z.SetLayer(layer)
    z.SetNet(B.FindNet(netname))
    z.SetAssignedPriority(prio)
    ol = z.Outline()
    ol.NewOutline()
    for x, y in pts:
        ol.Append(MM(x), MM(y))
    z.SetMinThickness(MM(0.25))
    z.SetThermalReliefGap(MM(0.3))
    z.SetThermalReliefSpokeWidth(MM(0.5))
    B.Add(z)
    return z

zone("GND", pcbnew.In1_Cu)
zone("V12", pcbnew.In2_Cu)
zone("GND", pcbnew.F_Cu)
zone("GND", pcbnew.B_Cu)
# antena ESP32: strefa zakazu miedzi na wszystkich warstwach (od krawędzi płytki)
ko = pcbnew.ZONE(B)
ko.SetIsRuleArea(True)
ko.SetDoNotAllowCopperPour(True)
ko.SetDoNotAllowTracks(True)
ko.SetDoNotAllowVias(True)
ls = pcbnew.LSET()
for l in (pcbnew.F_Cu, pcbnew.In1_Cu, pcbnew.In2_Cu, pcbnew.B_Cu):
    ls.AddLayer(l)
ko.SetLayerSet(ls)
o = ko.Outline()
o.NewOutline()
for x, y in ((156.5, 8), (W, 8), (W, 32), (156.5, 32)):
    o.Append(MM(x), MM(y))
B.Add(ko)

pcbnew.SaveBoard("maszyna.kicad_pcb", B)
print("rozmieszczono", len(fp), "elementów")
