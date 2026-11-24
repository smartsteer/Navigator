"""Klasy połączeń (panel.kicad_pro)."""
import json, os, shutil
p = "panel.kicad_pro"
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
ns["classes"] = [cls("Default", 0.2, 0.15, 0.6, 0.3), cls("Zasilanie", 0.5, 0.15, 0.6, 0.3)]
ns["meta"] = {"version": 3}
ns["netclass_patterns"] = [{"netclass": "Zasilanie", "pattern": n} for n in ("VBAT", "VBAT_SW", "VIN", "V3V3", "GND", "BUCK_SW", "EPD_3V3")]
rules = pro.setdefault("board", {}).setdefault("design_settings", {}).setdefault("rules", {})
rules.update({"min_copper_edge_clearance": 0.3, "min_through_hole_diameter": 0.2, "min_via_annular_width": 0.1, "min_via_diameter": 0.45, "min_clearance": 0.15, "min_track_width": 0.2})
json.dump(pro, open(p, "w"), indent=2)
print("klasy:", [c["name"] for c in ns["classes"]])
