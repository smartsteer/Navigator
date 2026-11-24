"""
Płytka Maszyna Lite (etap 16Lb) — schemat jako kod (SKiDL) → netlista KiCad (lite.net).
Specyfikacja: ../README.md, protokół: ../../../docs/MASZYNA.md. Licencja GPLv3.
Bloki wspólne (ESP32, USB/CH340C z auto-resetem, CAN, przepływomierz, ciśnienie, 12→5 V) jak w ../../maszyna/kicad/maszyna.py.

Uruchomienie:  /usr/bin/python3 lite.py   → lite.net
Zmiana względem specyfikacji: przetwornica 12→5 V LMR33610 (jak w pełnej płytce, 36 V) zamiast TPS54202 (28 V) —
lepiej znosi zrzut obciążenia za TVS SMCJ24A (punkt 4 listy „Do sprawdzenia”).
"""
import os

os.environ.setdefault("KICAD7_SYMBOL_DIR", "/usr/share/kicad/symbols")
from skidl import KICAD7, Net, Part, Pin, SKIDL, TEMPLATE, ERC, generate_netlist, set_default_tool

set_default_tool(KICAD7)

R_FP = "Resistor_SMD:R_0603_1608Metric"
C_FP = "Capacitor_SMD:C_0603_1608Metric"
C50_FP = "Capacitor_SMD:C_1206_3216Metric"
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


def hdr2(val):
    return Part("Connector_Generic", "Conn_01x02", footprint="Connector_PinHeader_2.54mm:PinHeader_1x02_P2.54mm_Vertical", value=val)


def listwa(n, val):
    return Part("Connector_Generic", f"Conn_01x{n:02d}",
                footprint=f"Connector_Phoenix_MC:PhoenixContact_MC_1,5_{n}-G-3.5_1x{n:02d}_P3.50mm_Horizontal", value=val)


GND = Net("GND")
VIN = Net("VIN_RAW")
V12 = Net("V12")
V12_OUT = Net("V12_WYJSCIA")   # za bezpiecznikiem 10 A
V5 = Net("V5")
V3 = Net("V3V3")
for n in (GND, V12, V5, V3):
    n.drive = 99

# ---------------------------------------------------------------- zasilanie 12 V: listwa 5,08, P-MOSFET, TVS
J_ZAS = Part("Connector_Generic", "Conn_01x02", footprint="Connector_Phoenix_MSTB:PhoenixContact_MSTBA_2,5_2-G-5,08_1x02_P5.08mm_Horizontal",
             value="ZAS +12/masa")
J_ZAS[1] += VIN
J_ZAS[2] += GND
Q1 = Part("Device", "Q_PMOS_GDS", footprint="Package_TO_SOT_SMD:TO-252-2", value="AOD4185")
q1g = Net("Q1_G")
Q1["D"] += VIN       # dioda w strukturze przewodzi przy dobrej polaryzacji, kanał ją bocznikuje
Q1["S"] += V12
Q1["G"] += q1g
R("10k", q1g, GND)
dz = Part("Device", "D_Zener", footprint="Diode_SMD:D_SOD-123", value="BZT52C15")
dz["K"] += V12
dz["A"] += q1g       # ochrona bramki (Vgs ≤ 15 V) przy zrzucie obciążenia
tvs = Part("Device", "D_TVS", footprint="Diode_SMD:D_SMC", value="SMCJ24A")
tvs[1] += V12
tvs[2] += GND
for _ in range(2):
    ce = Part("Device", "C_Polarized", footprint="Capacitor_SMD:CP_Elec_8x10", value="100u 35V 105°C")
    ce[1] += V12
    ce[2] += GND
F1 = Part("Device", "Fuse", footprint="Fuse:Fuseholder_Blade_Mini_Keystone_3568", value="10A mini")
F1[1] += V12
F1[2] += V12_OUT

# ---------------------------------------------------------------- 12 → 5 V (LMR33610, jak pełna płytka) i 3,3 V
U_BUCK = Part("Regulator_Switching", "LMR33610ADDAR", value="LMR33610ADDAR")
sw, boot, fb, vccb = Net("BUCK_SW"), Net("BUCK_BOOT"), Net("BUCK_FB"), Net("BUCK_VCC")
U_BUCK["VIN"] += V12
U_BUCK["EN"] += V12
U_BUCK["GND"] += GND
U_BUCK["SW"] += sw
U_BUCK["BOOT"] += boot
U_BUCK["FB"] += fb
U_BUCK["VCC"] += vccb
U_BUCK["PG"] += Net("BUCK_PG")
C("10u 50V", V12, GND, C50_FP)
C("100n 50V", V12, GND)
C("100n", boot, sw)
C("1u", vccb, GND)
L1 = Part("Device", "L", footprint="Inductor_SMD:L_Bourns_SRN8040TA", value="10uH 2A")
L1[1] += sw
L1[2] += V5
R("100k", V5, fb)
R("24k9", fb, GND)
C("22u 10V", V5, GND, C50_FP)
C("22u 10V", V5, GND, C50_FP)
U_LDO = Part("Regulator_Linear", "AMS1117-3.3", value="AMS1117-3.3")
U_LDO["VI"] += V5
U_LDO["VO"] += V3
U_LDO["GND"] += GND
C("22u 10V", V3, GND, C50_FP)

# ---------------------------------------------------------------- ESP32
U_MCU = Part("RF_Module", "ESP32-WROOM-32E", footprint="smartsteer_lite:ESP32-WROOM-32D_Przelotki0.3", value="ESP32-WROOM-32E-N4")
U_MCU["VDD"] += V3
U_MCU["GND"] += GND
C("10u", V3, GND, "Capacitor_SMD:C_0805_2012Metric")
C("100n", V3, GND)
en, io0 = Net("ESP_EN"), Net("ESP_IO0")
U_MCU["EN"] += en
U_MCU["IO0"] += io0
R("10k", V3, en)
C("1u", en, GND)
R("10k", V3, io0)
io2, io12 = Net("ESP_IO2"), Net("ESP_IO12")
U_MCU["IO2"] += io2
U_MCU["IO12"] += io12
R("10k", io2, GND)
R("10k", io12, GND)

SCK, MOSI, RCLK, NOE = Net("SPI_SCK"), Net("SPI_MOSI"), Net("REG_RCLK"), Net("REG_nOE")
MS = [Net(f"MUX_S{i}") for i in range(4)]
MUX_OUT, P_ADC, V_ADC, FLOW = Net("MUX_ADC"), Net("CISN_ADC"), Net("ZAS_ADC"), Net("FLOW_3V3")
CAN_TX, CAN_RX = Net("CAN_TX"), Net("CAN_RX")
LAMP_IN, LED_G, LED_R, JP_MODE = Net("LAMP_IN"), Net("LED_ZIELONA"), Net("LED_CZERWONA"), Net("JP_CAN")
SDA, SCL = Net("I2C_SDA"), Net("I2C_SCL")
U0TX, U0RX = Net("U0TXD"), Net("U0RXD")
pinmap = {"IO18": SCK, "IO23": MOSI, "IO5": RCLK, "IO4": NOE, "IO16": MS[0], "IO17": MS[1], "IO13": MS[2], "IO14": MS[3],
          "IO34": MUX_OUT, "SENSOR_VP": P_ADC, "SENSOR_VN": V_ADC, "IO35": FLOW, "IO32": CAN_TX, "IO33": CAN_RX,
          "IO25": LAMP_IN, "IO26": LED_G, "IO27": LED_R, "IO19": JP_MODE, "IO21": SDA, "IO22": SCL,
          "TXD0/IO1": U0TX, "RXD0/IO3": U0RX}
for p, n in pinmap.items():
    U_MCU[p] += n
R("10k", V3, NOE)       # po resecie wyjścia rejestrów odcięte
R("10k", V3, JP_MODE)
jp = hdr2("JP CAN/BT")
jp[1] += JP_MODE
jp[2] += GND
for net, col in ((LED_G, "zielona"), (LED_R, "czerwona")):
    d = Part("Device", "LED", footprint="LED_SMD:LED_0805_2012Metric", value=f"LED {col}")
    a = Net(f"LED_A_{col}")
    R("1k", net, a)
    d["A"] += a
    d["K"] += GND
J_SERW = Part("Connector_Generic", "Conn_01x04", footprint="Connector_PinHeader_2.54mm:PinHeader_1x04_P2.54mm_Vertical",
              value="SERW 3V3/SDA/SCL/GND")
for i, n in enumerate((V3, SDA, SCL, GND), 1):
    J_SERW[i] += n
R("4k7", V3, SDA)
R("4k7", V3, SCL)

# ---------------------------------------------------------------- USB-C + CH340C + auto-reset
J_USB = Part("Connector", "USB_C_Receptacle_USB2.0_16P", footprint="Connector_USB:USB_C_Receptacle_GCT_USB4105-xx-A_16P_TopMnt_Horizontal",
             value="USB-C")
DP, DM = Net("USB_DP"), Net("USB_DM")
for p in ("A6", "B6"):
    J_USB[p] += DP
for p in ("A7", "B7"):
    J_USB[p] += DM
for p in ("A1", "A12", "B1", "B12", "S1"):
    J_USB[p] += GND
vbus = Net("USB_VBUS")
for p in ("A4", "A9", "B4", "B9"):
    J_USB[p] += vbus
C("100n", vbus, GND)
R("5k1", J_USB["A5"], GND)
R("5k1", J_USB["B5"], GND)
for p in ("A8", "B8"):
    J_USB[p] += Net(f"NC_{p}")
U_USB = Part("Interface_USB", "CH340C", value="CH340C")
U_USB["VCC"] += V3
U_USB["V3"] += V3
U_USB["GND"] += GND
U_USB["UD+"] += DP
U_USB["UD-"] += DM
C("100n", V3, GND)
dtr, rts = Net("USB_DTR"), Net("USB_RTS")
U_USB[13] += dtr
U_USB[14] += rts
R("470", U_USB[2], U0RX)
R("470", U0TX, U_USB[3])
for p in (7, 8, 9, 10, 11, 12, 15):
    U_USB[p] += Net(f"NC_CH340_{p}")
qa = Part("Transistor_BJT", "MMBT3904")
qb = Part("Transistor_BJT", "MMBT3904")
ba, bb = Net("AR_B1"), Net("AR_B2")
R("10k", dtr, ba)
R("10k", rts, bb)
qa["B"] += ba
qa["E"] += rts
qa["C"] += en
qb["B"] += bb
qb["E"] += dtr
qb["C"] += io0

# ---------------------------------------------------------------- CAN
J_CAN = listwa(3, "CAN H/L/masa")
U_CAN = Part("Interface_CAN_LIN", "TJA1051T-3", value="TJA1051T/3")
CANH, CANL = Net("CANH"), Net("CANL")
U_CAN["TXD"] += CAN_TX
U_CAN["RXD"] += CAN_RX
U_CAN["VCC"] += V5
U_CAN["VIO"] += V3
U_CAN["GND"] += GND
U_CAN["S"] += GND
U_CAN["CANH"] += CANH
U_CAN["CANL"] += CANL
C("100n", V5, GND)
C("100n", V3, GND)
term = Net("CAN_TERM")
R("120", CANH, term)
jt = hdr2("JP terminator CAN")
jt[1] += term
jt[2] += CANL
tcan = Part(tool=SKIDL, name="PESD2CAN", ref_prefix="D", footprint="Package_TO_SOT_SMD:SOT-23", value="PESD2CAN",
            pins=[Pin(num=1, name="L1", func=Pin.types.PASSIVE), Pin(num=2, name="L2", func=Pin.types.PASSIVE),
                  Pin(num=3, name="COM", func=Pin.types.PASSIVE)])
tcan[1] += CANH
tcan[2] += CANL
tcan[3] += GND
J_CAN[1] += CANH
J_CAN[2] += CANL
J_CAN[3] += GND

# ---------------------------------------------------------------- czujniki: przepływomierz i ciśnienie (listwa 6-pin)
J_CZ = listwa(6, "CZUJ przepl +12/syg/masa, cisn +5/syg/masa")
F2 = Part("Device", "Polyfuse", footprint="Fuse:Fuse_1812_4532Metric", value="0.5A")
F2[1] += V12
F2[2] += J_CZ[1]
fsig = Net("PRZEPL_SYG")
J_CZ[2] += fsig
J_CZ[3] += GND
R("4k7", J_CZ[1], fsig)
fdiv = Net("PRZEPL_DZ")
R("10k", fsig, fdiv)
R("4k7", fdiv, GND)
C("1n", fdiv, GND)
U_SCH = Part("74xGxx", "74LVC1G17", footprint="Package_TO_SOT_SMD:SOT-353_SC-70-5", value="74LVC1G17")
U_SCH[2] += fdiv
U_SCH[4] += FLOW
U_SCH["VCC"] += V3
U_SCH["GND"] += GND
U_SCH[1] += Net("NC_SCH")
C("100n", V3, GND)
F3 = Part("Device", "Polyfuse", footprint="Fuse:Fuse_1812_4532Metric", value="0.2A")
F3[1] += V5
F3[2] += J_CZ[4]
psig = Net("CISN_SYG")
J_CZ[5] += psig
J_CZ[6] += GND
R("10k", psig, P_ADC)
R("20k", P_ADC, GND)          # 4,5 V → 3,0 V
C("100n", P_ADC, GND)
jp2 = hdr2("JP 4-20mA")
shunt = Net("CISN_SHUNT")
jp2[1] += psig
jp2[2] += shunt
R("150 0.1%", shunt, GND, "Resistor_SMD:R_0805_2012Metric")
tps = Part("Device", "D_TVS", footprint="Diode_SMD:D_SOD-323", value="PESD5V0S1BA")
tps[1] += psig
tps[2] += GND
R("100k", V12, V_ADC)
R("15k", V_ADC, GND)          # 22 V → 2,9 V
C("100n", V_ADC, GND)

# ---------------------------------------------------------------- lampy: cewka przekaźnika w wiązce
J_LAMP = listwa(2, "LAMPY +12/cewka")
Q2 = Part("Device", "Q_NMOS_GSD", footprint="Package_TO_SOT_SMD:SOT-23", value="AO3400A")
coil = Net("LAMPA_CEWKA")
Q2["D"] += coil
Q2["S"] += GND
gl = Net("LAMP_G")
Q2["G"] += gl
R("100", LAMP_IN, gl)
R("100k", gl, GND)
D3 = Part("Device", "D_Schottky", footprint="Diode_SMD:D_SMA", value="SS14")
D3["K"] += V12
D3["A"] += coil
J_LAMP[1] += V12
J_LAMP[2] += coil

# ---------------------------------------------------------------- rejestry 4 × 74HC595 (IN1/IN2 mostków)
regs = []
ser = MOSI
for i in range(4):
    u = Part("74xx", "74HC595", footprint="Package_SO:TSSOP-16_4.4x5mm_P0.65mm", value="74HC595PW")
    u["VCC"] += V3
    u["GND"] += GND
    u["SER"] += ser
    u["SRCLK"] += SCK
    u["RCLK"] += RCLK
    u["~{OE}"] += NOE
    u["~{SRCLR}"] += V3
    C("100n", V3, GND)
    ser = Net(f"SR_CHAIN{i}")
    u[9] += ser               # QH' (numer pinu: nazwa „QH” pasowałaby też do QH')
    regs.append(u)
reg_out = [u[q] for u in regs for q in (15, 1, 2, 3, 4, 5, 6, 7)]   # QA..QH, bit 0..31

# ---------------------------------------------------------------- multiplekser prądów mostków
U_MUX = Part("74xx", "CD74HC4067SM", value="CD74HC4067SM96")
U_MUX["VCC"] += V3
U_MUX["GND"] += GND
U_MUX["~{E}"] += GND
U_MUX["COM"] += MUX_OUT
for i in range(4):
    U_MUX[f"S{i}"] += MS[i]
C("100n", V3, GND)
# Kanały multipleksera (zmienione pod ścieżki 2026-11-24): wyjście k → kanał MUX_CH[k]; kanał 10 wolny (brak miejsca
# na ścieżkę do masy — zero ADC firmware bierze z kalibracji eFuse). Ta sama tabela musi być w firmware Lite (etap 16Lc).
MUX_CH = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 11, 12, 13, 14]
U_MUX["I10"] += Net("NC_MUX_I10")

# ---------------------------------------------------------------- 15 × DRV8870 + listwy W1…W15
VREF = Net("DRV_VREF")
R("2k", V3, VREF)
R("20k", VREF, GND)           # 3,0 V → I_lim = 3,0 / (10 · 0,15) = 2 A
C("100n", VREF, GND)
NAMES = [f"SEKCJA{k}" for k in range(1, 14)] + ["ZAW_GLOWNY", "ZAW_REG"]
for k, name in enumerate(NAMES):
    u = Part("Driver_Motor", "DRV8870DDA", value="DRV8870DDAR")
    u["VM"] += V12_OUT
    u["GND"] += GND
    u["VREF"] += VREF
    in1, in2 = Net(f"{name}_IN1"), Net(f"{name}_IN2")
    reg_out[2 * k] += in1
    reg_out[2 * k + 1] += in2
    u["IN1"] += in1
    u["IN2"] += in2
    R("10k", in1, GND)
    R("10k", in2, GND)
    isen = Net(f"{name}_ISEN")
    u["ISEN"] += isen
    R("0R15 1W", isen, GND, "Resistor_SMD:R_2512_6332Metric")
    imeas = Net(f"{name}_IPOM")
    R("1k", isen, imeas)
    C("1u", imeas, GND)
    U_MUX[f"I{MUX_CH[k]}"] += imeas
    a, b = Net(f"{name}_A"), Net(f"{name}_B")
    u["OUT1"] += a
    u["OUT2"] += b
    C("10u 50V", V12_OUT, GND, C50_FP)
    C("100n 50V", V12_OUT, GND)
    j = listwa(4, f"W{k + 1} {name} A/B/+12/masa")
    j[1] += a
    j[2] += b
    j[3] += V12_OUT
    j[4] += GND
    for n in (a, b):   # TVS na pinach listwy (zawory: wyładowania, zwarcia wiązki)
        t = Part("Device", "D_TVS", footprint="Diode_SMD:D_SOD-323", value="ESD 24V (np. PESD24VS1UL)")
        t[1] += n
        t[2] += GND
for q in reg_out[30:]:
    q += Net(f"NC_REG_{id(q)}")

# ---------------------------------------------------------------- otwory montażowe M3
for _ in range(4):
    Part(tool=SKIDL, name="OTWOR", ref_prefix="H", footprint="MountingHole:MountingHole_3.2mm_M3", value="M3", pins=[])

if __name__ == "__main__":
    ERC()
    generate_netlist(file_="lite.net")
