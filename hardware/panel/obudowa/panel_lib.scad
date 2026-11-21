// Wspólne bryły obudów paneli SmartSteer (panel oprysku i panel hydrauliki) — druk 3D, GPLv3.
// Układ współrzędnych paneli: x w prawo, y od operatora do tyłu, z w górę; wierzch płyty czołowej = z 0.
// Płyta czołowa jest płaska (płytka PCB równoległa do niej), pochylenie daje spód: dno opada do tyłu o kąt `nachyl`.
// Na tym samym pliku opierają się panel_oprysk.scad i ../../hydraulika/obudowa/panel_hydraulika.scad.

$fn = 48;

t_plyta   = 3;      // grubość płyty czołowej
scianka   = 4;      // ścianka spodu (rowek uszczelki w środku)
dno       = 2.5;    // grubość dna
pcb_odst  = 16;     // od spodu płyty czołowej do wierzchu PCB (korpus dźwigni pod płytą; reguluje się nakrętką na tulei)
pcb_gr    = 1.6;
uszcz_d   = 2.2;    // rowek pod sznur uszczelki Ø2 mm (EPDM / silikon)
uszcz_h   = 1.6;
wkladka3  = 4.2;    // otwór pod wtapianą tuleję M3
wkladka4  = 5.6;    // otwór pod wtapianą tuleję M4

// Baterie: koszyk 6 × AA płaski (6 ogniw obok siebie, przewód z wtyczką do PCB) — ok. 92 × 58 × 16 mm, ZMIERZYĆ kupiony
bat_wew   = [96, 62, 18];   // wnętrze komory (z luzem na koszyk i przewód)
bat_klapa = 3;              // grubość klapki
bat_krawedz = 6;            // stopień pod klapkę (szerokość) z rowkiem uszczelki

// AMPS — standard otworów uchwytów RAM i innych (30 × 38 mm, M4): uchwyt kulowy RAM 1" z podstawą AMPS albo płytka z magnesami
amps = [30, 38];

// ---------- obrys ----------
// prostokąt W × D z promieniami narożników [lewy‑przód, prawy‑przód, prawy‑tył, lewy‑tył]
function _luk(c, r, a0) = [for (i = [0:8]) c + r * [cos(a0 + i * 90 / 8), sin(a0 + i * 90 / 8)]];
module obrys(W, D, R) {
    polygon(concat(
        _luk([R[0], R[0]], R[0], 180),
        _luk([W - R[1], R[1]], R[1], 270),
        _luk([W - R[2], D - R[2]], R[2], 0),
        _luk([R[3], D - R[3]], R[3], 90)));
}

// ---------- dno: układ lokalny (dno zewnętrzne = z' 0, z' w głąb obudowy) ----------
module na_dnie(h0, nachyl) { translate([0, 0, -h0]) rotate([-nachyl, 0, 0]) children(); }
function z_dna(y, h0, nachyl) = -h0 - y * tan(nachyl);

// półprzestrzeń nad dnem (wnętrze obudowy w pionie)
module nad_dnem(W, D, h0, nachyl, z0 = 0) {
    na_dnie(h0, nachyl) translate([-50, -50, z0]) cube([W + 100, D / cos(nachyl) + 100, 200]);
}

// ---------- spód ----------
// sruby: śruby pokrywy (słupki z tuleją M3), bat: [x, y'] lewego‑przedniego rogu wnętrza komory w układzie dna,
// amps_xy: środek otworów AMPS w układzie dna
module spod(W, D, R, h0, nachyl, sruby, bat, amps_xy) {
    difference() {
        union() {
            difference() {
                intersection() {
                    translate([0, 0, -200]) linear_extrude(200 - t_plyta) obrys(W, D, R);
                    nad_dnem(W, D, h0, nachyl);
                }
                // wnętrze
                intersection() {
                    translate([0, 0, -199]) linear_extrude(199 - t_plyta + 1) offset(-scianka) obrys(W, D, R);
                    nad_dnem(W, D, h0, nachyl, dno);
                }
            }
            // słupki śrub pokrywy
            for (p = sruby) intersection() {
                translate([p[0], p[1], -200]) cylinder(d = 9, h = 200 - t_plyta);
                nad_dnem(W, D, h0, nachyl);
            }
            // ścianki komory baterii (od dna do sufitu komory)
            na_dnie(h0, nachyl) translate([bat[0] - 2, bat[1] - 2, 0])
                cube([bat_wew[0] + 4, bat_wew[1] + 4, bat_wew[2] + bat_klapa + 1.5]);
            // pogrubienia pod tuleje AMPS
            na_dnie(h0, nachyl) for (sx = [-1, 1], sy = [-1, 1])
                translate([amps_xy[0] + sx * amps[0] / 2, amps_xy[1] + sy * amps[1] / 2, 0]) cylinder(d = 11, h = 10);
        }
        // rowek uszczelki na wierzchu ścianki
        translate([0, 0, -t_plyta - uszcz_h]) linear_extrude(uszcz_h + 1) difference() {
            offset(-scianka / 2 + uszcz_d / 2) obrys(W, D, R);
            offset(-scianka / 2 - uszcz_d / 2) obrys(W, D, R);
        }
        // otwory pod tuleje M3 w słupkach
        for (p = sruby) translate([p[0], p[1], -t_plyta - 8]) cylinder(d = wkladka3, h = 9);
        // komora baterii otwarta od spodu: wnętrze + stopień pod klapkę z rowkiem uszczelki
        na_dnie(h0, nachyl) {
            translate([bat[0], bat[1], -1]) cube([bat_wew[0], bat_wew[1], bat_wew[2] + bat_klapa + 1]);
            translate([bat[0] - bat_krawedz, bat[1] - bat_krawedz, -1])
                cube([bat_wew[0] + 2 * bat_krawedz, bat_wew[1] + 2 * bat_krawedz, bat_klapa + 1]);
            // rowek uszczelki w stopniu
            translate([0, 0, bat_klapa - 0.01]) linear_extrude(uszcz_h) difference() {
                translate([bat[0] - bat_krawedz / 2 - uszcz_d / 2, bat[1] - bat_krawedz / 2 - uszcz_d / 2])
                    square([bat_wew[0] + bat_krawedz + uszcz_d, bat_wew[1] + bat_krawedz + uszcz_d]);
                translate([bat[0] - bat_krawedz / 2 + uszcz_d / 2, bat[1] - bat_krawedz / 2 + uszcz_d / 2])
                    square([bat_wew[0] + bat_krawedz - uszcz_d, bat_wew[1] + bat_krawedz - uszcz_d]);
            }
            // tuleje M4 na śruby radełkowane klapki (kręcone palcami — bez narzędzi)
            for (sx = [0, 1]) translate([bat[0] + bat_wew[0] / 2 + (sx - 0.5) * (bat_wew[0] + bat_krawedz),
                                         bat[1] + bat_wew[1] / 2, bat_klapa - 0.5]) cylinder(d = wkladka4, h = 8);
            // przelot przewodu baterii do PCB (uszczelnić silikonem / przepustem)
            translate([bat[0] + bat_wew[0] - 10, bat[1] + bat_wew[1] - 10, 0]) cylinder(d = 5, h = 60);
            // tuleje M4 AMPS
            for (sx = [-1, 1], sy = [-1, 1])
                translate([amps_xy[0] + sx * amps[0] / 2, amps_xy[1] + sy * amps[1] / 2, -1]) cylinder(d = wkladka4, h = 9);
        }
    }
}

module klapka_baterii() {
    L = bat_wew[0] + 2 * bat_krawedz - 0.6;
    S = bat_wew[1] + 2 * bat_krawedz - 0.6;
    difference() {
        translate([0, 0, 0]) cube([L, S, bat_klapa]);
        for (sx = [0, 1]) translate([L / 2 + (sx - 0.5) * (bat_wew[0] + bat_krawedz), S / 2, -1]) cylinder(d = 4.5, h = 10);
        // wgłębienie pod palec i napis
        translate([L / 2, S / 2, -1]) scale([1, 0.5, 1]) cylinder(d = 30, h = 1.8);
        translate([L / 2, S / 2 + 14, bat_klapa - 0.6]) linear_extrude(1)
            text("6 × AA", size = 6, halign = "center", valign = "center", font = "DejaVu Sans:style=Bold");
    }
}

// ---------- pokrywa (płyta czołowa) ----------
// sruby: przelotowe z łbem stożkowym M3; pcb_slupki: tuleje M3 od spodu do mocowania PCB
module plyta_baza(W, D, R, sruby, pcb_slupki) {
    difference() {
        union() {
            translate([0, 0, -t_plyta]) linear_extrude(t_plyta) obrys(W, D, R);
            for (p = pcb_slupki) translate([p[0], p[1], -t_plyta - pcb_odst]) cylinder(d = 7, h = pcb_odst + 0.01);
        }
        for (p = sruby) translate([p[0], p[1], -t_plyta - 1]) {
            cylinder(d = 3.4, h = 10);
            translate([0, 0, t_plyta + 1 - 1.8]) cylinder(d1 = 3.4, d2 = 6.6, h = 1.81);
        }
        for (p = pcb_slupki) translate([p[0], p[1], -t_plyta - pcb_odst - 1]) cylinder(d = wkladka3, h = 9);
    }
}

module napis(t, s = 4.2, b = true) {
    linear_extrude(1) text(t, size = s, halign = "center", valign = "center",
                           font = b ? "DejaVu Sans:style=Bold" : "DejaVu Sans");
}
// wytłoczenie napisu w płycie (głębokość 0,6 mm) — wypełnić farbą
module grawer(x, y, t, s = 4.2) { translate([x, y, -0.6]) napis(t, s); }

// otwór pod tuleję 1/4" (dźwignie IP67: APEM 600H, C&K 7000 — sprawdzić) z wycięciem na podkładkę ustalającą
module otwor_dzwigni(x, y) {
    translate([x, y, -20]) cylinder(d = 6.6, h = 30);
    translate([x, y + 6, -t_plyta - 1]) cylinder(d = 2.6, h = t_plyta - 0.8);   // nie na wylot — pod wypust podkładki
}
module otwor_led(x, y) {          // dioda 5 mm (soczewka) wklejona od spodu, uszczelnić żywicą
    translate([x, y, -20]) cylinder(d = 5.2, h = 30);
}
module otwor_22(x, y) {           // przycisk wandaloodporny Ø22 IP67
    translate([x, y, -40]) cylinder(d = 22.3, h = 80);
}
module otwor_kolyska(x, y) {      // włącznik kołyskowy uszczelniony, wycięcie 19,2 × 12,9 (sprawdzić z kupionym)
    translate([x - 19.2 / 2, y - 12.9 / 2, -20]) cube([19.2, 12.9, 30]);
}

// ---------- uchwyt z magnesami (AMPS, 4 × magnes w kubku Ø25 z trzpieniem M4) ----------
module uchwyt_magnes() {
    L = 92; S = 72; g = 6;
    difference() {
        hull() for (sx = [-1, 1], sy = [-1, 1]) translate([sx * (L / 2 - 8), sy * (S / 2 - 8), 0]) cylinder(r = 8, h = g);
        // otwory AMPS: śruby M4 z łbem stożkowym od strony magnesów (spód uchwytu)
        for (sx = [-1, 1], sy = [-1, 1]) translate([sx * amps[0] / 2, sy * amps[1] / 2, -1]) {
            cylinder(d = 4.5, h = g + 2);
            cylinder(d1 = 8.6, d2 = 4.5, h = 2.6);
        }
        // magnesy: trzpień M4 przez płytkę, nakrętka w gnieździe sześciokątnym od góry
        for (sx = [-1, 1], sy = [-1, 1]) translate([sx * (L / 2 - 13), sy * (S / 2 - 13), -1]) {
            cylinder(d = 4.5, h = g + 2);
            translate([0, 0, g + 1 - 3.4]) cylinder(d = 8.1, h = 4, $fn = 6);
        }
    }
}

// ---------- osłony dźwigni jako osobny grzebień (płyta drukuje się wierzchem w dół, bez podpór) ----------
// żebra w punktach xs, od y0 do y0 + dl, połączone grzbietem przy y_grzb (wklejany w rowek 0,8 mm w płycie)
module grzebien(xs, y0, dl, h, y_grzb) {
    for (x = xs) translate([x - 0.8, y0, 0])
        hull() { cube([1.6, dl, h - 0.8]); translate([0.8, 0, h - 0.8]) rotate([-90, 0, 0]) cylinder(d = 1.6, h = dl); }
    translate([min(xs) - 0.8, y_grzb - 0.8, -0.8]) cube([max(xs) - min(xs) + 1.6, 1.6, 2.4]);
}
module rowek_grzbietu(xs, y_grzb) {
    translate([min(xs) - 1, y_grzb - 0.95, -0.8]) cube([max(xs) - min(xs) + 2, 1.9, 1]);
}
