"""Klasy połączeń (lite.kicad_pro): szerokości pod router; prąd 10 A niosą wylewki dodane po routingu (zones.py)."""
import json, os, shutil, sys
p = "lite.kicad_pro"
if not os.path.exists(p):
    shutil.copy("../../maszyna/kicad/maszyna.kicad_pro", p)
pro = json.load(open(p))
pro.setdefault("meta", {})["filename"] = p
base = {"bus_width": 12, "diff_pair_gap": 0.25, "diff_pair_via_gap": 0.25, "diff_pair_width": 0.2, "line_style": 0,
        "microvia_diameter": 0.3, "microvia_drill": 0.1, "pcb_color": "rgba(0, 0, 0, 0.000)",
        "schematic_color": "rgba(0, 0, 0, 0.000)", "wire_width": 6}
def cls(name, track, clear, via, drill):
    d = dict(base); d.update({"name": name, "track_width": track, "clearance": clear, "via_diameter": via, "via_drill": drill})
    return d
ns = pro.setdefault("net_settings", {})
ns["classes"] = [cls("Default", 0.25, 0.15, 0.6, 0.3), cls("Moc", 1.0, 0.25, 1.0, 0.5),
                 cls("Wyjscia", 0.6, 0.2, 0.8, 0.4), cls("Zasilanie", 0.4, 0.15, 0.6, 0.3)]
ns["meta"] = {"version": 3}
pats = [("Moc", n) for n in ("VIN_RAW", "V12", "V12_WYJSCIA")]
pats += [("Wyjscia", n) for n in ("SEKCJA*_A", "SEKCJA*_B", "ZAW_*_A", "ZAW_*_B", "*_ISEN", "LAMPA_CEWKA")]
pats += [("Zasilanie", n) for n in ("V5", "V3V3", "GND", "BUCK_SW")]
ns["netclass_patterns"] = [{"netclass": c, "pattern": n} for c, n in pats]
rules = pro.setdefault("board", {}).setdefault("design_settings", {}).setdefault("rules", {})
rules.update({"min_copper_edge_clearance": 0.3, "min_through_hole_diameter": 0.2, "min_via_annular_width": 0.1, "min_via_diameter": 0.45, "min_clearance": 0.15, "min_track_width": 0.2})
json.dump(pro, open(p, "w"), indent=2)
print("klasy:", [c["name"] for c in ns["classes"]])
