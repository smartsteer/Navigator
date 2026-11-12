"""Raport do przeglądu schematu (dla elektronika): z netlisty maszyna.net → przeglad_schematu.html (PDF przez przeglądarkę)."""
import re, html, collections
net = open("maszyna.net", encoding="utf-8").read()
comps = {}
for m in re.finditer(r'\(comp\s+\(ref "([^"]+)"\)\s+\(value "([^"]*)"\).*?\(footprint "([^"]*)"\)', net, re.S):
    comps[m.group(1)] = (m.group(2), m.group(3).split(":")[-1])
pins = collections.defaultdict(list)   # ref -> [(pin, pinname, net)]
members = collections.defaultdict(list)
for m in re.finditer(r'\(net\s+\(code "\d+"\)\s+\(name "([^"]*)"\)(.*?)\)\s*\)\s*(?=\(net|\)\s*\)\s*$|\)\s*$)', net, re.S):
    pass
# prostszy parser: dzielimy na bloki (net ...)
for blk in re.split(r'\n\s*\(net\s', net)[1:]:
    nm = re.search(r'\(name "([^"]*)"\)', blk).group(1)
    for nd in re.finditer(r'\(node\s+\(ref "([^"]+)"\)\s+\(pin "([^"]+)"\)(?:\s+\(pinfunction "([^"]*)"\))?', blk):
        ref, pin, fn = nd.group(1), nd.group(2), nd.group(3) or ""
        pins[ref].append((pin, fn, nm))
        members[nm].append(f"{ref}.{fn or pin}")
names = {}
for line in open("piny.tsv", encoding="utf-8"):
    r, n, nm = line.rstrip("\n").split("\t")
    names[(r, n)] = nm
for r in list(pins):
    pins[r] = [(p, fn or names.get((r, p), ""), nm) for p, fn, nm in pins[r]]
for nm in list(members):
    members[nm] = [x if not x.split(".")[-1].isdigit() else x.rsplit(".", 1)[0] + "." + (names.get((x.rsplit(".", 1)[0], x.rsplit(".", 1)[1])) or x.rsplit(".", 1)[1]) for x in members[nm]]
def key(r):
    return (r.rstrip("0123456789"), int(re.sub(r"\D", "", r) or 0))
def pkey(p):
    return (0, int(p)) if p.isdigit() else (1, p)
out = ['<!doctype html><meta charset="utf-8"><title>Płytka Maszyna — przegląd schematu</title><style>',
       'body{font:11px/1.35 sans-serif;margin:18px;color:#111} h1{font-size:18px} h2{font-size:14px;margin:18px 0 4px;border-bottom:1px solid #888}',
       'table{border-collapse:collapse;width:100%;margin:4px 0 10px} td,th{border:1px solid #ccc;padding:2px 4px;vertical-align:top} th{background:#eee;text-align:left}',
       '.m{color:#555}</style>',
       '<h1>SmartSteer — płytka Maszyna (opryskiwacz): przegląd połączeń</h1>',
       '<p>Wygenerowane z netlisty <code>maszyna.net</code> (źródło: <code>maszyna.py</code>, SKiDL → KiCad 7). Specyfikacja, wyprowadzenia złączy i lista „do sprawdzenia”: <code>hardware/maszyna/README.md</code>. '
       'Dla każdego układu: pin → sieć → co jeszcze jest w tej sieci. Elementy bierne (R, C, D, L, F) na końcu.</p>']
big = [r for r in comps if r[0] in "UJQ"]
for r in sorted(big, key=key):
    v, f = comps[r]
    out.append(f"<h2>{r} — {html.escape(v)} <span class=m>({html.escape(f)})</span></h2><table><tr><th>pin</th><th>nazwa</th><th>sieć</th><th>połączone z</th></tr>")
    for pin, fn, nm in sorted(pins[r], key=lambda t: pkey(t[0])):
        others = [x for x in members[nm] if not x.startswith(r + ".")]
        if nm in ("GND", "V12", "V3V3", "V5") and len(others) > 6:
            o = f"{len(others)} połączeń (zasilanie)"
        else:
            o = ", ".join(others)
        out.append(f"<tr><td>{pin}</td><td>{html.escape(fn)}</td><td><b>{html.escape(nm)}</b></td><td>{html.escape(o)}</td></tr>")
    out.append("</table>")
out.append("<h2>Elementy bierne</h2><table><tr><th>ref</th><th>wartość</th><th>obudowa</th><th>sieci</th></tr>")
for r in sorted([r for r in comps if r[0] not in "UJQ"], key=key):
    v, f = comps[r]
    out.append(f"<tr><td>{r}</td><td>{html.escape(v)}</td><td>{html.escape(f)}</td><td>{html.escape(' — '.join(n for _, _, n in sorted(pins[r], key=lambda t: pkey(t[0]))))}</td></tr>")
out.append("</table>")
open("przeglad_schematu.html", "w", encoding="utf-8").write("\n".join(out))
print(len(big), "układów/złączy,", len(comps) - len(big), "elementów biernych")
