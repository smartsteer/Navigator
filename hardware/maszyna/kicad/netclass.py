"""Klasy połączeń w projekcie KiCad (maszyna.kicad_pro): szerokości ścieżek dla mocy 20 A, wyjść zaworów 2 A, zasilań."""
import json
p = "maszyna.kicad_pro"
pro = json.load(open(p))
base = {"bus_width": 12, "diff_pair_gap": 0.25, "diff_pair_via_gap": 0.25, "diff_pair_width": 0.2, "line_style": 0,
        "microvia_diameter": 0.3, "microvia_drill": 0.1, "pcb_color": "rgba(0, 0, 0, 0.000)",
        "schematic_color": "rgba(0, 0, 0, 0.000)", "wire_width": 6}
def cls(name, track, clear, via, drill):
    d = dict(base); d.update({"name": name, "track_width": track, "clearance": clear, "via_diameter": via, "via_drill": drill})
    return d
ns = pro.setdefault("net_settings", {})
ns["classes"] = [cls("Default", 0.2, 0.2, 0.6, 0.3), cls("Moc20A", 3.0, 0.4, 1.2, 0.6),
                 cls("Moc2A", 1.0, 0.3, 1.0, 0.5), cls("Zasilanie", 0.6, 0.25, 0.8, 0.4)]
ns["meta"] = {"version": 3}
pats = [("Moc20A", n) for n in ("VIN_RAW", "LAMPA_OUT", "V12", "V12_ZAWORY", "SILNIK_A", "SILNIK_B", "SILNIK_BOCZNIK")]
pats += [("Moc2A", "SEKCJA*"), ("Moc2A", "ZAW_*"), ("Moc2A", "REZERWA_*")]
pats += [("Zasilanie", n) for n in ("V5", "V3V3", "BUCK_SW", "GND")]
ns["netclass_patterns"] = [{"netclass": c, "pattern": n} for c, n in pats]
json.dump(pro, open(p, "w"), indent=2)
print("klasy:", [c["name"] for c in ns["classes"]])
