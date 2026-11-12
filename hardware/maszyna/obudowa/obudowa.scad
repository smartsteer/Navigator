// Obudowa płytki Maszyna (opryskiwacz) SmartSteer — druk 3D (ASA lub PETG), GPLv3.
// Parametryczna: wymiary płytki i otworów złączy na górze pliku. Przed drukiem zmierzyć gniazda AMPSEAL
// z rysunku TE (776231-1 35-pin, 770680-1 23-pin) i poprawić amp35 / amp23.
// Eksport: openscad -D 'czesc="spod"' -o spod.stl obudowa.scad   (oraz czesc="pokrywa")

czesc = "oba";            // "spod", "pokrywa", "oba" (podgląd)

// --- płytka ---
pcb = [165, 120, 1.6];    // wymiary płytki (KiCad: hardware/maszyna/kicad)
pcb_z = 6;                // wysokość płytki nad dnem (słupki)
pcb_otw = [[4,46],[161,40],[4,116],[161,116]];   // otwory M3 w płytce (jak w KiCad, od lewego górnego rogu = ściana złączy)

// --- obudowa ---
sc = 3;                   // ścianka
luz = 8;                  // odstęp płytki od ścianki (narożne słupki pokrywy nie mogą zachodzić na płytkę)
h_spod = 42;              // wysokość spodu (wewnątrz)
h_pokr = 12;              // wysokość pokrywy (wewnątrz)
r = 5;                    // zaokrąglenie narożników
uszczelka = 2.2;          // rowek pod sznur uszczelki Ø2 mm
wkret_pokr = 5.6;         // otwór pod wtopiony gwint M4 (pokrywa)
wkret_pcb = 4.2;          // otwór pod wtopiony gwint M3 (płytka)

// --- otwory w ścianie przedniej (y = 0), x od lewej krawędzi wnętrza ---
amp35 = [62, 21];         // wycięcie kołnierza AMPSEAL 35 (szer., wys.) — ZMIERZYĆ
amp23 = [46, 21];         // wycięcie kołnierza AMPSEAL 23 — ZMIERZYĆ
amp_x = [11, 81];         // położenie lewej krawędzi wycięć
amp_z = pcb_z + pcb[2];   // dół wycięcia = wierzch płytki (gniazda leżą na płytce)
amp_sruby = 4.0;          // otwory śrub kołnierza AMPSEAL (po bokach wycięcia)

// --- otwory w ścianie prawej (x = max) ---
usb = [10, 4.2];          // USB-C (z gumową zaślepką na zewnątrz)
sma = 6.6;                // gniazdo SMA anteny GPS (gwint 1/4"-36)
led = 5.2;                // okienko LED (światłowód / soczewka)
odpow = 12.6;             // membrana odpowietrzająca M12 (IP67)

wew = [pcb[0] + 2*luz, pcb[1] + 2*luz];
zew = [wew[0] + 2*sc, wew[1] + 2*sc];
$fn = 48;

module zaokr(s, h) { linear_extrude(h) offset(r) offset(-r) square(s); }

module rogi() { for (p = [[r+1, r+1], [zew[0]-r-1, r+1], [r+1, zew[1]-r-1], [zew[0]-r-1, zew[1]-r-1]]) translate(p) children(); }

module spod() {
    difference() {
        union() {
            difference() {
                zaokr(zew, sc + h_spod);
                translate([sc, sc, sc]) zaokr(wew, h_spod + 1);
            }
            // słupki narożne pod śruby pokrywy
            rogi() cylinder(d = 11, h = sc + h_spod);
            // słupki pod płytkę
            for (p = pcb_otw) translate([sc + luz + p[0], sc + luz + p[1], 0]) cylinder(d = 8, h = sc + pcb_z);
            // ucha montażowe M8 do ramy opryskiwacza
            for (x = [-12, zew[0] + 12]) hull() {
                translate([x, zew[1]/2, 0]) cylinder(d = 22, h = 6);
                translate([x < 0 ? 0 : zew[0] - 1, zew[1]/2 - 15, 0]) cube([1, 30, 6]);
            }
        }
        // otwory: śruby pokrywy, płytki, ucha
        rogi() translate([0, 0, sc + h_spod - 10]) cylinder(d = wkret_pokr, h = 11);
        for (p = pcb_otw) translate([sc + luz + p[0], sc + luz + p[1], sc]) cylinder(d = wkret_pcb, h = pcb_z);
        for (x = [-12, zew[0] + 12]) translate([x, zew[1]/2, -1]) cylinder(d = 8.5, h = 8);
        // rowek uszczelki w górnej krawędzi
        translate([0, 0, sc + h_spod - uszczelka]) difference() {
            translate([sc/2 - uszczelka/2 + 0.4, sc/2 - uszczelka/2 + 0.4, 0]) zaokr([zew[0] - sc + uszczelka - 0.8, zew[1] - sc + uszczelka - 0.8], uszczelka + 1);
            translate([sc/2 + uszczelka/2 - 0.4, sc/2 + uszczelka/2 - 0.4, -1]) zaokr([zew[0] - sc - uszczelka + 0.8, zew[1] - sc - uszczelka + 0.8], uszczelka + 3);
        }
        // AMPSEAL 35 i 23 — ściana przednia
        for (i = [0, 1]) {
            a = i == 0 ? amp35 : amp23;
            translate([sc + amp_x[i], -1, sc + amp_z]) cube([a[0], sc + 2, a[1]]);
            for (dx = [-5, a[0] + 5]) translate([sc + amp_x[i] + dx, -1, sc + amp_z + a[1]/2]) rotate([-90, 0, 0]) cylinder(d = amp_sruby, h = sc + 2);
        }
        // ściana prawa: USB-C, SMA, LED, odpowietrznik
        xr = zew[0] - sc - 1;
        translate([xr, sc + luz + 76 - usb[0]/2, sc + pcb_z + pcb[2]]) cube([sc + 2, usb[0], usb[1]]);   // USB-C: y = 76 mm na płytce
        translate([xr, sc + luz + 52, sc + 24]) rotate([0, 90, 0]) cylinder(d = sma, h = sc + 2);
        translate([xr, sc + luz + 90, sc + 24]) rotate([0, 90, 0]) cylinder(d = led, h = sc + 2);
        translate([xr, sc + luz + 106, sc + 26]) rotate([0, 90, 0]) cylinder(d = odpow, h = sc + 2);
        // napis
        translate([zew[0]/2, 0.6, sc + h_spod - 9]) rotate([90, 0, 0])
            linear_extrude(1) text("SmartSteer", size = 6, halign = "center", font = "Liberation Sans:style=Bold");
    }
}

module pokrywa() {
    difference() {
        union() {
            difference() {
                zaokr(zew, sc + h_pokr);
                translate([sc, sc, sc]) zaokr(wew, h_pokr + 1);
            }
            rogi() cylinder(d = 11, h = sc + h_pokr);
            // pióro dociskające uszczelkę
            translate([0, 0, sc + h_pokr]) difference() {
                translate([sc/2 - 0.6, sc/2 - 0.6, 0]) zaokr([zew[0] - sc + 1.2, zew[1] - sc + 1.2], 1.6);
                translate([sc/2 + 0.6, sc/2 + 0.6, -1]) zaokr([zew[0] - sc - 1.2, zew[1] - sc - 1.2], 4);
            }
        }
        rogi() translate([0, 0, -1]) cylinder(d = 4.5, h = sc + h_pokr + 3);
        rogi() translate([0, 0, -1]) cylinder(d = 8.5, h = 4.5);   // łby śrub M4
        translate([zew[0]/2, zew[1]/2, -0.01]) mirror([1, 0, 0])
            linear_extrude(0.8) text("WAR · SmartSteer · opryskiwacz", size = 7, halign = "center", valign = "center", font = "Liberation Sans:style=Bold");
    }
}

if (czesc == "spod") spod();
else if (czesc == "pokrywa") pokrywa();
else { spod(); translate([0, zew[1] + 30, 0]) pokrywa(); }
