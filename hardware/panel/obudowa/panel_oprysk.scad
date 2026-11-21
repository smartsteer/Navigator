// Obudowa panelu oprysku (wersja 2: pod dłoń, e‑papier 4,2", roller dawki) — etap 22c, druk 3D (ASA), GPLv3.
// Na podstawie hardware/panel/README.md i wizualizacja_v2.png. Wspólne bryły: panel_lib.scad.
// Eksport (każda część osobno, już ułożona do druku):
//   openscad -D 'czesc="pokrywa"' -o oprysk_pokrywa.stl panel_oprysk.scad
//   czesc = "pokrywa" | "spod" | "klapka" | "garb" | "rant" | "roller_kolo" | "roller_widelki" | "uchwyt_magnes" | "oba" (podgląd złożenia)
// Położenia otworów (tabela `ELEMENTY` w README obudowy) są wymiarami dla płytki PCB w etapie 22b.

include <panel_lib.scad>

czesc = "oba";

W = 300; D = 140;           // obrys z góry
R = [18, 40, 40, 18];       // narożniki: lewy‑przód, prawy‑przód, prawy‑tył, lewy‑tył
h0 = 36;                    // wysokość z przodu (od dna do wierzchu płyty)
nachyl = 9;                 // pochylenie płyty względem dna (z tyłu wyżej: 36 + 140·tg 9° ≈ 58 mm)

// --- elementy na płycie (x, y) ---
sekcja_x = [for (k = [0:12]) 22 + 17 * k];   // 13 dźwigni co 17 mm
sekcja_y = 30;
led_y    = 48;
ekran_c  = [62, 94];    // środek okna e‑papieru
ekran_ok = [86, 65];    // okno (pole aktywne 4,2" = 84,8 × 63,6)
szybka   = [96, 75, 1]; // szybka PC/akryl 2 mm wklejana od spodu (wgłębienie 1 mm)
auto_xy  = [150, 100];
swiatlo_xy = [175, 100];
zasil_xy = [205, 100];
led_stan = [226, 122];
czujnik_swiatla = [212, 122];
roller_c = [240, 62];   // środek kieszeni rollera (oś koła wzdłuż x)
roller_k = [16, 36];    // kieszeń
stop_xy  = [262, 108];
dlon_c   = [276, 48];   // podparcie dłoni (garb)
dlon_s   = [26, 40, 13];

rant_x = [for (k = [0:13]) 22 - 8.5 + 17 * k];   // osłony między dźwigniami sekcji
rant_grzb = 41;
garb_kolki = [[268, 40], [284, 62]];             // kołki Ø3 ustalające garb (klej epoksydowy)

sruby = [[40, 7.5], [120, 7.5], [200, 7.5], [258, 7.5],
         [120, 132.5], [200, 132.5], [258, 132.5],
         [7.5, 40], [7.5, 110], [292.5, 70]];
pcb_slupki = [[20, 20], [149.5, 20], [272, 24], [20, 57], [112, 120], [272, 116]];

// komora baterii i AMPS w układzie dna (y' = y / cos 9°)
bat_pol  = [102, 66];
amps_pol = [150, 30];

// głębokość kieszeni rollera: dno kieszeni 1 mm nad czujnikami na PCB
pcb_wierzch = -t_plyta - pcb_odst;
kiesz_dno = pcb_wierzch + 1 + 1.2;    // wierzch dna kieszeni (dno 1,2 mm)

module pokrywa() {
    difference() {
        union() {
            plyta_baza(W, D, R, sruby, pcb_slupki);
            // obudowa kieszeni rollera pod płytą
            translate([roller_c[0] - roller_k[0] / 2 - 2, roller_c[1] - roller_k[1] / 2 - 2, kiesz_dno - 1.2])
                cube([roller_k[0] + 4, roller_k[1] + 4, -kiesz_dno + 1.2 - 0.01]);
            // ramka okna ekranu od spodu (sztywność, klej)
            translate([ekran_c[0] - szybka[0] / 2 - 3, ekran_c[1] - szybka[1] / 2 - 3, -t_plyta - 2])
                cube([szybka[0] + 6, szybka[1] + 6, 2.01]);
        }
        // okno e‑papieru + wgłębienie pod szybkę od spodu
        translate([ekran_c[0] - ekran_ok[0] / 2, ekran_c[1] - ekran_ok[1] / 2, -10]) cube([ekran_ok[0], ekran_ok[1], 20]);
        translate([ekran_c[0] - szybka[0] / 2, ekran_c[1] - szybka[1] / 2, -t_plyta - 2 - 0.01])
            cube([szybka[0], szybka[1], 2 + szybka[2]]);
        for (x = sekcja_x) { otwor_dzwigni(x, sekcja_y); otwor_led(x, led_y); }
        otwor_dzwigni(auto_xy[0], auto_xy[1]);
        otwor_dzwigni(swiatlo_xy[0], swiatlo_xy[1]);
        otwor_kolyska(zasil_xy[0], zasil_xy[1]);
        otwor_led(led_stan[0], led_stan[1]);
        translate([czujnik_swiatla[0], czujnik_swiatla[1], -10]) cylinder(d = 3.2, h = 20);
        otwor_22(stop_xy[0], stop_xy[1]);
        // kieszeń rollera (zamknięte dno — czujniki pod spodem, bez otworu)
        translate([roller_c[0] - roller_k[0] / 2, roller_c[1] - roller_k[1] / 2, kiesz_dno]) cube([roller_k[0], roller_k[1], 60]);
        // dostęp do śrub przez garb i rant
        for (p = sruby) translate([p[0], p[1], 0]) cylinder(d = 7, h = 40);
        rowek_grzbietu(rant_x, rant_grzb);
        for (p = garb_kolki) translate([p[0], p[1], -2]) cylinder(d = 3.1, h = 3);
        // napisy
        for (k = [0:12]) grawer(sekcja_x[k], 14, str(k + 1), 4.5);
        grawer(auto_xy[0], 86, "AUTO", 3.6);
        grawer(swiatlo_xy[0], 86, "ŚWIATŁO", 3.2);
        grawer(zasil_xy[0], 89, "I / O", 3.6);
        grawer(roller_c[0], 87, "DAWKA", 3.6);
        grawer(182, 124, "OPRYSK", 5);
    }
}

module calosc_spod() { spod(W, D, R, h0, nachyl, sruby, bat_pol, amps_pol); }

// Garb pod dłoń — osobna część (druk płaską stroną na stole), przyklejony na kołkach; śruba [292.5, 70] pod garbem — dostęp otworem.
module garb() {
    difference() {
        intersection() {
            translate([dlon_c[0], dlon_c[1], 0]) scale(dlon_s) sphere(1, $fn = 96);
            linear_extrude(30) offset(-0.5) obrys(W, D, R);
        }
        for (p = garb_kolki) translate([p[0], p[1], -1]) cylinder(d = 3.1, h = 4);
        for (p = sruby) translate([p[0], p[1], -1]) cylinder(d = 7, h = 40);
        translate([roller_c[0] - roller_k[0] / 2 - 1, roller_c[1] - roller_k[1] / 2 - 1, -1]) cube([roller_k[0] + 2, roller_k[1] + 2, 40]);
    }
}
module rant() { grzebien(rant_x, 19, 24, 7, rant_grzb); }

// Roller: rdzeń Ø22 z 2 rowkami na o‑ringi 20 × 2,5 (bieżnik Ø24), oś: kołek stalowy Ø3 × 16.
// 8 magnesów Ø3 × 3 promieniowo, bieguny na przemian (N, S, N…) — 2 czujniki Halla pod dnem kieszeni dają kierunek i kroki.
module roller_kolo() {
    w = 11.6;
    difference() {
        cylinder(d = 22, h = w, $fn = 64);
        translate([0, 0, -1]) cylinder(d = 3.3, h = w + 2);
        for (z = [2.2, w - 2.2]) translate([0, 0, z]) rotate_extrude($fn = 64) translate([11, 0]) circle(d = 2.6);
        for (i = [0:7]) rotate([0, 0, i * 45]) translate([11 - 3.2, 0, w / 2]) rotate([0, 90, 0]) cylinder(d = 3.15, h = 4);
    }
}
// Widełki rollera z TPU 95A: podstawa ugina się przy wciśnięciu koła (~1 mm) — magnes Ø4 × 1 pod środkiem podstawy,
// czujnik Halla (przełącznik) pod dnem kieszeni wykrywa wciśnięcie. Prototyp — do sprawdzenia w ręku.
module roller_widelki() {
    L = roller_k[1] - 0.6; S = roller_k[0] - 0.6; zb = 2; gb = 1.5; os = 14.5;
    difference() {
        union() {
            translate([0, 0, zb]) cube([S, L, gb]);
            for (y = [0, L - 4]) translate([0, y, 0]) cube([S, 4, zb]);            // stopki na końcach
            for (x = [0, S - 1.5]) translate([x, L / 2 - 5, zb]) cube([1.5, 10, os + 3.5]);   // ramiona
        }
        translate([-1, L / 2, zb + os]) rotate([0, 90, 0]) cylinder(d = 2.9, h = S + 2);  // wcisk kołka Ø3
        translate([S / 2, L / 2 + 9, zb - 1]) cylinder(d = 4.2, h = 1.01 + 1);                     // magnes przycisku
    }
}

if (czesc == "pokrywa") rotate([180, 0, 0]) pokrywa();          // do druku wierzchem w dół — bez podpór
else if (czesc == "garb") translate(-[dlon_c[0], dlon_c[1], 0]) garb();
else if (czesc == "rant") translate([0, 0, 0.8]) rant();
else if (czesc == "spod") rotate([nachyl, 0, 0]) translate([0, 0, h0]) calosc_spod();   // dnem na stole
else if (czesc == "klapka") klapka_baterii();
else if (czesc == "roller_kolo") roller_kolo();
else if (czesc == "roller_widelki") roller_widelki();
else if (czesc == "uchwyt_magnes") uchwyt_magnes();
else {
    color("DimGray") pokrywa();
    color("SlateGray") calosc_spod();
    color("Gray") garb();
    color("DimGray") rant();
    color("DarkOliveGreen") translate([roller_c[0] - 11.6 / 2, roller_c[1], -0.3]) rotate([0, 90, 0]) roller_kolo();
}
