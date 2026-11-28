"""Klasy połączeń płytki Ster (ster.kicad_pro) pod JLCPCB 4 warstwy (JLC04161H-7628, 1 oz). GPLv3.
Default 0,15 mm / odstęp 0,127 / przelotka 0,45/0,2 — mieści się między kulkami BGA 0,8 mm (przelotka w środku
między 4 polami: odstęp ≈ 0,14 mm). Moc 1,2 mm (silnik i 12 V; prąd ciągły dodatkowo wylewkami po routingu),
Zasilanie 0,4 mm, RF 0,35 mm (≈ 50 Ω mikropasek nad masą In1, prepreg 0,21 mm), USB pary 0,2/0,15 (≈ 90 Ω)."""
import json

p = "ster.kicad_pro"
pro = json.load(open(p))
base = {"bus_width": 12, "diff_pair_gap": 0.15, "diff_pair_via_gap": 0.25, "diff_pair_width": 0.2, "line_style": 0,
        "microvia_diameter": 0.3, "microvia_drill": 0.1, "pcb_color": "rgba(0, 0, 0, 0.000)",
        "schematic_color": "rgba(0, 0, 0, 0.000)", "wire_width": 6}


def cls(name, track, clear, via, drill):
    d = dict(base)
    d.update({"name": name, "track_width": track, "clearance": clear, "via_diameter": via, "via_drill": drill})
    return d


ns = pro.setdefault("net_settings", {})
ns["classes"] = [cls("Default", 0.15, 0.127, 0.45, 0.2), cls("Moc", 1.2, 0.2, 0.8, 0.4),
                 cls("Zasilanie", 0.4, 0.15, 0.6, 0.3), cls("RF", 0.35, 0.3, 0.45, 0.2), cls("USB", 0.2, 0.15, 0.45, 0.2)]
ns["meta"] = {"version": 3}
pats = [("Moc", n) for n in ("VIN_12V", "V12", "V12_FILTR", "V12_SILNIK", "SILNIK_LEFT", "SILNIK_RIGHT", "MOT_BOCZNIK",
                             "LOCK", "BUCK_SW")]
pats += [("Zasilanie", n) for n in ("V5", "V5_*", "V3V3*", "VDD_SOC_IN", "DCDC_*", "USB_VBUS*", "GPS_VBUS", "GND")]
pats += [("RF", "GNSS_RF")] + [("USB", n) for n in ("USB1_D*", "GPS_USB_D*")]
ns["netclass_patterns"] = [{"netclass": c, "pattern": n} for c, n in pats]
rules = pro.setdefault("board", {}).setdefault("design_settings", {}).setdefault("rules", {})
rules.update({"min_copper_edge_clearance": 0.3, "min_through_hole_diameter": 0.2, "min_via_annular_width": 0.1,
              "min_via_diameter": 0.4, "min_clearance": 0.1, "min_track_width": 0.1, "min_hole_clearance": 0.2})
json.dump(pro, open(p, "w"), indent=2)
print("klasy:", [c["name"] for c in ns["classes"]])
