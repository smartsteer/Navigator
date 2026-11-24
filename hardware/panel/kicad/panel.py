"""
Panel przełączników opryskiwacza SmartSteer (etap 22b) — schemat jako kod (SKiDL) → netlista KiCad (panel.net).
Specyfikacja: ../README.md, położenia elementów: ../obudowa/README.md (tabela „Panel oprysku”). Licencja GPLv3.

Uruchomienie:  /usr/bin/python3 panel.py   → panel.net, panel_erc.txt
Dalej:         kinet2pcb -i panel.net -w → panel.kicad_pcb, potem place.py, netclass.py, routing (README.md).

Zmiany względem specyfikacji 22a (powód w README.md):
 - procesor ESP32-S3-MINI-1 zamiast ESP32-C3-MINI-1 (symbol i footprint sprawdzone w bibliotece KiCad, więcej pinów),
 - 4 × TCA9534 (dźwignie sekcji chwilowe = 2 styki na sekcję: 26 wejść + AUTO + ŚWIATŁO),
 - roller: jeden czujnik TMAG5111 (dwa zatrzaski Halla w jednym układzie = kroki i kierunek) + SM351LT (wciśnięcie),
 - e‑papier jako gotowy moduł SPI 4,2" (np. Waveshare) na złączu 8‑pin, zasilany przez klucz (zero prądu w uśpieniu).
Pinouty układów z bibliotek KiCad 7 (ESP32-S3-MINI-1, TCA9534, IS31FL3236A-TQ, TPS62177DQC, TMAG5111, SM351LT).
"""
import os

os.environ.setdefault("KICAD7_SYMBOL_DIR", "/usr/share/kicad/symbols")
from skidl import KICAD7, Net, Part, Pin, SKIDL, TEMPLATE, ERC, generate_netlist, set_default_tool, footprint_search_paths

set_default_tool(KICAD7)
footprint_search_paths[KICAD7].append(os.path.dirname(os.path.abspath(__file__)))

R_FP = "Resistor_SMD:R_0603_1608Metric"
C_FP = "Capacitor_SMD:C_0603_1608Metric"
r_t = Part("Device", "R", dest=TEMPLATE, footprint=R_FP)
c_t = Part("Device", "C", dest=TEMPLATE, footprint=C_FP)


def R(val, a, b, fp=R_FP):
    r = r_t(value=val, footprint=fp)
    r[1] += a
    r[2] += b
    return r


def C(val, a, b, fp=C_FP):
    c = c_t(value=val, footprint=fp)
    c[1] += a
    c[2] += b
    return c


GND = Net("GND")
VBAT = Net("VBAT")        # z koszyka 6 × AA (przez włącznik)
VBAT_SW = Net("VBAT_SW")  # za włącznikiem kołyskowym
VIN = Net("VIN")          # za ochroną przed odwrotnym włożeniem
V3 = Net("V3V3")
for n in (GND, V3, VIN):
    n.drive = 99

# ---------------------------------------------------------------- złącza baterii i włącznika
J_BAT = Part("Connector_Generic", "Conn_01x02", footprint="Connector_JST:JST_XH_B2B-XH-A_1x02_P2.50mm_Vertical",
             value="BATERIA 6xAA")
J_BAT[1] += VBAT
J_BAT[2] += GND
J_PWR = Part("Connector_Generic", "Conn_01x02", footprint="Connector_JST:JST_XH_B2B-XH-A_1x02_P2.50mm_Vertical",
             value="WLACZNIK I/O")
J_PWR[1] += VBAT
J_PWR[2] += VBAT_SW

# ochrona przed odwrotnym włożeniem baterii: MOSFET P (dren od baterii, źródło do układu)
Q1 = Part("Device", "Q_PMOS_GSD", footprint="Package_TO_SOT_SMD:SOT-23", value="DMP3099L")
Q1["D"] += VBAT_SW
Q1["S"] += VIN
Q1G = Net("Q1_G")
Q1["G"] += Q1G
R("100k", Q1G, GND)
D1 = Part("Diode", "SMAJ12A", footprint="Diode_SMD:D_SMA")
D1[1] += VIN   # katoda
D1[2] += GND
C("10u 25V", VIN, GND, fp="Capacitor_SMD:C_1206_3216Metric")

# przetwornica 3,3 V (Iq ~5 µA)
U_REG = Part("Regulator_Switching", "TPS62177DQC", footprint="Package_SON:WSON-10-1EP_2x3mm_P0.5mm_EP0.84x2.4mm")
SW = Net("BUCK_SW")
U_REG["VIN"] += VIN
U_REG["EN"] += VIN
U_REG["PGND", "AGND", "PAD"] += GND
U_REG["SW"] += SW
U_REG["VOS", "FB"] += V3
U_SLEEP = Net("REG_nSLEEP")
U_REG["~{SLEEP}"] += U_SLEEP
R("1M", U_SLEEP, V3)          # domyślnie praca normalna; ESP ściąga w dół przed uśpieniem
L1 = Part("Device", "L", footprint="Inductor_SMD:L_1210_3225Metric", value="2.2u 1A")
L1[1] += SW
L1[2] += V3
C("22u 10V", V3, GND, fp="Capacitor_SMD:C_1206_3216Metric")
C("100n", VIN, GND)

# pomiar baterii — dzielnik z kluczem (prąd tylko przy pomiarze)
Q2 = Part("Transistor_FET", "BSS84")
Q3 = Part("Device", "Q_NMOS_GSD", footprint="Package_TO_SOT_SMD:SOT-23", value="2N7002")
BAT_EN = Net("BAT_EN")
BAT_DIV = Net("BAT_DIV")
BAT_ADC = Net("BAT_ADC")
Q2G = Net("Q2_G")
Q2["S"] += VIN
Q2["G"] += Q2G
Q2["D"] += BAT_DIV
R("1M", Q2G, VIN)
Q3["D"] += Q2G
Q3["S"] += GND
Q3["G"] += BAT_EN
R("1M", BAT_EN, GND)
R("1M", BAT_DIV, BAT_ADC)
R("220k", BAT_ADC, GND)
C("100n", BAT_ADC, GND)

# ---------------------------------------------------------------- procesor
U1 = Part("MCU_Espressif", "ESP32-S3-MINI-1", footprint="RF_Module:ESP32-S2-MINI-1")
U1["3V3"] += V3
U1["GND"] += GND
EN = Net("ESP_EN")
U1["EN"] += EN
R("10k", EN, V3)
C("1u", EN, GND)
C("10u", V3, GND, fp="Capacitor_SMD:C_0805_2012Metric")
C("100n", V3, GND)
IO0 = Net("ESP_IO0")
U1["IO0"] += IO0
R("10k", IO0, V3)

SDA, SCL = Net("I2C_SDA"), Net("I2C_SCL")
R("4.7k", SDA, V3)
R("4.7k", SCL, V3)
EXP_INT = Net("EXP_nINT")
R("1M", EXP_INT, V3)
START = Net("START")
ROL_PRESS = Net("ROLLER_WCISK")
ROL_A, ROL_B, ROL_PWR = Net("ROLLER_A"), Net("ROLLER_B"), Net("ROLLER_ZASIL")
LIGHT_EN, LIGHT_ADC = Net("SWIATLO_EN"), Net("SWIATLO_ADC")
LED_SDB = Net("LED_SDB")
EPD = {k: Net("EPD_" + k) for k in ("SCK", "MOSI", "CS", "DC", "RST", "BUSY", "nPWR")}
ID0 = Net("ID_PANELU")
TXD, RXD, USB_DN, USB_DP = Net("ESP_TXD"), Net("ESP_RXD"), Net("USB_DN"), Net("USB_DP")

pinmap = {
    "IO1": EXP_INT, "IO2": START, "IO4": ROL_PRESS, "IO5": ROL_A, "IO6": ROL_B, "IO7": ROL_PWR,
    "IO8": LIGHT_EN, "IO9": LIGHT_ADC, "IO10": BAT_ADC, "IO11": BAT_EN, "IO12": U_SLEEP,
    "IO13": SDA, "IO14": SCL, "IO15": LED_SDB,
    "IO16": EPD["SCK"], "IO17": EPD["MOSI"], "IO18": EPD["CS"], "IO21": EPD["DC"],
    "IO38": EPD["RST"], "IO39": EPD["BUSY"], "IO40": EPD["nPWR"], "IO41": ID0,
    "TXD0": TXD, "RXD0": RXD, "IO19": USB_DN, "IO20": USB_DP,
}
for pin, net in pinmap.items():
    U1[pin] += net
R("100k", ID0, GND)   # panel oprysku = 0 (panel hydrauliki: bez tego rezystora → 1)
R("1M", START, V3)
R("1M", LED_SDB, GND)

# złącze programowania (pady, bez wlutowanego złącza): 3V3, GND, EN, IO0, TX, RX, USB D-, D+
J_PROG = Part("Connector_Generic", "Conn_01x08", footprint="Connector_PinHeader_2.54mm:PinHeader_1x08_P2.54mm_Vertical",
              value="PROG (nie montować)")
for i, n in enumerate((V3, GND, EN, IO0, TXD, RXD, USB_DN, USB_DP), 1):
    J_PROG[i] += n

# ---------------------------------------------------------------- dźwignie i przełączniki (ekspandery I2C)
exp_t = Part("Interface_Expansion", "TCA9534", dest=TEMPLATE, footprint="Package_SO:TSSOP-16_4.4x5mm_P0.65mm")
sw_t = Part(tool=SKIDL, name="DZWIGNIA", ref_prefix="SW", dest=TEMPLATE,
            footprint="smartsteer_panel:SW_Toggle_CK7000_PC_P4.70mm",
            pins=[Pin(num=1, name="A", func=Pin.types.PASSIVE), Pin(num=2, name="COM", func=Pin.types.PASSIVE),
                  Pin(num=3, name="B", func=Pin.types.PASSIVE)])
inputs = []
for k in range(1, 14):
    s = sw_t(value=f"SEKCJA {k} (ON)-OFF-(ON)")
    up, dn = Net(f"S{k}_WL"), Net(f"S{k}_WYL")
    s["COM"] += GND
    s["A"] += up      # styk przy dźwigni w górę — sprawdzić z kartą dźwigni (dźwignia zwiera styk po przeciwnej stronie)
    s["B"] += dn
    inputs += [up, dn]
for name in ("AUTO", "SWIATLO"):
    s = sw_t(value=f"{name} ON-OFF")
    n = Net(f"P_{name}")
    s["COM"] += GND
    s["A"] += n
    s["B"] += Net(f"NC_{name}")
    inputs.append(n)
while len(inputs) < 32:
    inputs.append(None)
for e in range(4):
    u = exp_t(value="TCA9534")
    u["VDD"] += V3
    u["GND"] += GND
    u["SDA"] += SDA
    u["SCL"] += SCL
    u["~{INT}"] += EXP_INT
    # numery pinów zamiast nazw: w SKiDL 3 nazwa „P1” pasuje też do aliasu numeru „p1” (pin A0)
    u[1] += GND if not (e & 1) else V3   # A0
    u[2] += GND if not (e & 2) else V3   # A1
    u[3] += GND                          # A2
    C("100n", V3, GND)
    for b in range(8):
        n = inputs[e * 8 + b]
        if n is None:
            n = Net(f"EXP{e}_P{b}_ZAPAS")
        u[(4, 5, 6, 7, 9, 10, 11, 12)[b]] += n   # P0…P7
        R("1M", n, V3)

# START/STOP (Ø22 z pierścieniem RGB) — przewody przyciskiem do złącza: COM, NO, anoda, R, G, B
J_START = Part("Connector_Generic", "Conn_01x06", footprint="Connector_JST:JST_XH_B6B-XH-A_1x06_P2.50mm_Vertical",
               value="START/STOP")

# ---------------------------------------------------------------- diody RGB (2 × IS31FL3236A)
LED_A = V3
led_t = Part("Device", "LED_RAGB", dest=TEMPLATE, footprint="LED_THT:LED_D5.0mm-4_RGB_Staggered_Pins")  # większe pady (pierścień ≥0,45 mm)
chans = []
for k in range(1, 15):
    d = led_t(value="RGB 5mm WA" + (f" S{k}" if k <= 13 else " STAN"))
    d["A"] += LED_A
    chans += [(d, "RK"), (d, "GK"), (d, "BK")]
st = [Net("START_R"), Net("START_G"), Net("START_B")]
J_START[1] += GND
J_START[2] += START
J_START[3] += LED_A
for i, n in enumerate(st):
    J_START[4 + i] += n
drivers = []
for k in range(2):
    u = Part("Driver_LED", "IS31FL3236A-TQ", value="IS31FL3236A")
    u["VCC"] += V3
    u["GND"] += GND
    u["SDA"] += SDA
    u["SCL"] += SCL
    u["~{SDB}"] += LED_SDB
    u["AD"] += GND if k == 0 else V3
    rx = Net(f"LED{k}_REXT")
    u["R_EXT"] += rx
    R("10k", rx, GND)
    C("100n", V3, GND)
    C("4.7u", V3, GND)
    drivers.append(u)
outs = [(drivers[0], i) for i in range(1, 37)] + [(drivers[1], i) for i in range(1, 37)]
for idx, (d, pname) in enumerate(chans):
    u, o = outs[idx]
    n = Net(f"LED_{d.ref}_{pname[0]}")
    d[pname] += n
    u[f"OUT{o}"] += n
for i, n in enumerate(st):
    u, o = outs[len(chans) + i]
    u[f"OUT{o}"] += n
C("10u", LED_A, GND, fp="Capacitor_SMD:C_0805_2012Metric")

# ---------------------------------------------------------------- roller (Hall) i czujnik światła
U_ROL = Part("Sensor_Magnetic", "TMAG5111C2xxDBV", value="TMAG5111C2 (ZY)")
U_ROL["VCC"] += ROL_PWR
U_ROL["GND"] += GND
U_ROL["OUT1"] += ROL_A
U_ROL["OUT2"] += ROL_B
C("100n", ROL_PWR, GND)
R("100k", ROL_A, ROL_PWR)
R("100k", ROL_B, ROL_PWR)
U_PR = Part("Sensor_Magnetic", "SM351LT")
U_PR["VCC"] += V3
U_PR["GND"] += GND
U_PR["OUTPUT"] += ROL_PRESS
C("100n", V3, GND)

PH1 = Part("Device", "Q_Photo_NPN_CE", footprint="LED_SMD:LED_0805_2012Metric", value="PT19-21C (0805)")
PH1["C"] += LIGHT_EN
PH1["E"] += LIGHT_ADC
R("10k", LIGHT_ADC, GND)

# ---------------------------------------------------------------- e‑papier 4,2" (moduł SPI) z kluczem zasilania
Q4 = Part("Transistor_FET", "BSS84", value="BSS84 (zasilanie EPD)")
EPD_V = Net("EPD_3V3")
Q4["S"] += V3
Q4["D"] += EPD_V
Q4["G"] += EPD["nPWR"]
R("100k", EPD["nPWR"], V3)
C("10u", EPD_V, GND, fp="Capacitor_SMD:C_0805_2012Metric")
J_EPD = Part("Connector_Generic", "Conn_01x08", footprint="Connector_JST:JST_PH_B8B-PH-K_1x08_P2.00mm_Vertical",
             value="E-PAPIER 4.2 SPI")
for i, n in enumerate((EPD_V, GND, EPD["MOSI"], EPD["SCK"], EPD["CS"], EPD["DC"], EPD["RST"], EPD["BUSY"]), 1):
    J_EPD[i] += n

# ---------------------------------------------------------------- otwory montażowe
for _ in range(6):
    Part(tool=SKIDL, name="OTWOR", ref_prefix="H", footprint="MountingHole:MountingHole_3.2mm_M3", value="M3", pins=[])

if __name__ == "__main__":
    ERC()
    generate_netlist(file_="panel.net")
