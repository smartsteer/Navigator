// Obudowa panelu hydrauliki belki (12 dźwigni dwukierunkowych) — etap 23c (część: obudowa), druk 3D (ASA), GPLv3.
// Na podstawie hardware/hydraulika/README.md i wizualizacja_panel.png. Wspólne bryły z panelem oprysku: panel_lib.scad.
// Eksport: openscad -D 'czesc="pokrywa"' -o hydraulika_pokrywa.stl panel_hydraulika.scad   (czesc = "pokrywa" | "spod" | "oslona" ×2 | "oba")
// Klapka baterii i uchwyt z magnesami — te same co w panelu oprysku (hardware/panel/obudowa).

include <../../panel/obudowa/panel_lib.scad>

czesc = "oba";

W = 280; D = 150;
R = [22, 22, 22, 22];
h0 = 36;                 // z przodu; z tyłu 36 + 150·tg 8° ≈ 57 mm
nachyl = 8;

kol_x = [for (k = [0:5]) 30 + 38 * k];   // 6 kolumn dźwigni
rzad_y = [42, 100];                      // rząd przedni i tylny
led_d = [14, 14];                        // dioda względem dźwigni
pasek = [34, 8];                         // wgłębienie na wymienny pasek z opisem (naklejka / wsuwka), nad dźwignią
pasek_dy = 26;
hydr_xy = [255, 108];                    // HYDR. AKTYWNA Ø22 z podświetleniem
stop_xy = [255, 68];                     // STOP Ø22
zasil_xy = [255, 28];
led_stan = [255, 140];

// osłony boczne dźwigni (przypadkowe trącenie) — osobny grzebień na rząd, drukować 2 sztuki
oslona_x = [for (x = kol_x, s = [-1, 1]) x + s * 10];
oslona_grzb = -10.2;    // grzbiet przed dźwignią (z tyłu jest dioda)
module oslona(y) { grzebien(oslona_x, y - 11, 22, 6, y + oslona_grzb); }

sruby = [[60, 7.5], [140, 7.5], [220, 7.5], [60, 142.5], [140, 142.5], [220, 142.5], [7.5, 75], [272.5, 88]];
pcb_slupki = [[18, 18], [262, 18], [18, 132], [262, 132], [125, 71]];
bat_pol = [92, 70];
amps_pol = [140, 32];

module pokrywa() {
    difference() {
        plyta_baza(W, D, R, sruby, pcb_slupki);
        for (y = rzad_y) rowek_grzbietu(oslona_x, y + oslona_grzb);
        for (x = kol_x, y = rzad_y) {
            otwor_dzwigni(x, y);
            otwor_led(x + led_d[0], y + led_d[1]);
            translate([x - pasek[0] / 2, y + pasek_dy - pasek[1] / 2, -0.6]) cube([pasek[0], pasek[1], 1]);
        }
        otwor_22(hydr_xy[0], hydr_xy[1]);
        otwor_22(stop_xy[0], stop_xy[1]);
        otwor_kolyska(zasil_xy[0], zasil_xy[1]);
        otwor_led(led_stan[0], led_stan[1]);
        grawer(255, 127, "HYDRAULIKA", 3.4);
        grawer(hydr_xy[0], 92.5, "AKTYWNA", 3.4);
        grawer(stop_xy[0], 52.5, "STOP", 3.6);
        grawer(zasil_xy[0], 14, "I / O", 3.6);
    }
}

module calosc_spod() { spod(W, D, R, h0, nachyl, sruby, bat_pol, amps_pol); }

if (czesc == "oslona") translate([0, -rzad_y[0], 0.8]) oslona(rzad_y[0]);
else if (czesc == "pokrywa") rotate([180, 0, 0]) pokrywa();
else if (czesc == "spod") rotate([nachyl, 0, 0]) translate([0, 0, h0]) calosc_spod();
else { color("DimGray") pokrywa(); color("SlateGray") calosc_spod(); for (y = rzad_y) color("Gray") oslona(y); }
