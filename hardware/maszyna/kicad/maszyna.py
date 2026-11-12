"""
Płytka Maszyna (opryskiwacz) SmartSteer — schemat jako kod (SKiDL) → netlista KiCad (maszyna.net).
Specyfikacja: ../README.md, protokół: ../../../docs/MASZYNA.md. Licencja GPLv3.

Uruchomienie:  KICAD7_SYMBOL_DIR=/usr/share/kicad/symbols python3 maszyna.py
Wynik: maszyna.net (netlista do KiCad PCB), maszyna_erc.txt (sprawdzenie połączeń).

Wyprowadzenia układów sprawdzone z kart katalogowych:
 - DRV8962 DDW (HTSSOP-44): karta TI DRV8962 (tabela pinów) — część własna niżej,
 - Quectel L76K (18 pinów LCC): opis pinów L76KB-A58,
 - pozostałe: symbole z bibliotek KiCad 7 (ESP32-WROOM-32E, TJA1051T/3, ADS1115, CH340C, LMR33610, 74HC595/165,
   74LVC1G17, BTS50010-1TAD — ta sama obudowa i układ pinów co BTS50015-1TAD, SPRAWDZIĆ).
"""
import os

os.environ.setdefault("KICAD7_SYMBOL_DIR", "/usr/share/kicad/symbols")
from skidl import (KICAD7, Net, Part, Pin, SKIDL, TEMPLATE, ERC, generate_netlist, set_default_tool, lib_search_paths, footprint_search_paths)

set_default_tool(KICAD7)
footprint_search_paths[KICAD7].append(os.path.dirname(os.path.abspath(__file__)))

# ---------------------------------------------------------------- pomocnicze
R_FP = "Resistor_SMD:R_0603_1608Metric"
C_FP = "Capacitor_SMD:C_0603_1608Metric"
C50_FP = "Capacitor_SMD:C_1206_3216Metric"   # 10 µF / 50 V X7R

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


# ---------------------------------------------------------------- sieci zasilania
GND = Net("GND")
VIN = Net("VIN_RAW")        # z kostki B, przed ochroną
V12 = Net("V12")            # po ochronie przed odwrotną polaryzacją
V12_ZAW = Net("V12_ZAWORY")  # za bezpiecznikiem 10 A (zawory 3-pin)
V5 = Net("V5")
V3 = Net("V3V3")
for n in (GND, V12, V5, V3):
    n.drive = 99  # sieci zasilania (ERC)

# ---------------------------------------------------------------- złącza
J1 = Part(tool=SKIDL, name="AMPSEAL_35", ref_prefix="J", footprint="smartsteer:TE_AMPSEAL_776231-1_3Rows_35_P4mm_Horizontal",
          pins=[Pin(num=i, name=f"P{i}", func=Pin.types.PASSIVE) for i in range(1, 36)], value="AMPSEAL 35 (sekcje)")
J2 = Part(tool=SKIDL, name="AMPSEAL_23", ref_prefix="J",
          footprint="Connector_TE-Connectivity:TE_AMPSEAL_1-776087-x_3Rows_23_P0.4mm_Horizontal",
          pins=[Pin(num=i, name=f"P{i}", func=Pin.types.PASSIVE) for i in range(1, 24)], value="AMPSEAL 23 (zasilanie, czujniki)")

# Kostka B: 1–3 +12 V, 4–6 masa, 7–8 lampy, 9–10 zawór główny, 11–12 regulacyjny, 13–15 przepływomierz,
# 16–18 ciśnienie, 19–20 CAN, 21–22 rezerwa (mostek 16), 23 masa sygnałowa
for p in (1, 2, 3):
    J2[p] += VIN
for p in (4, 5, 6, 23):
    J2[p] += GND
# Kostka A: 1–26 sekcje A/B, 27–30 +12 V zawory, 31–35 masa
for p in (27, 28, 29, 30):
    J1[p] += V12_ZAW
for p in (31, 32, 33, 34, 35):
    J1[p] += GND

# ---------------------------------------------------------------- ochrona wejścia 12 V (idealna dioda)
U_ID = Part("Power_Management", "LM74700", footprint="Package_TO_SOT_SMD:SOT-23-6", value="LM74700-Q1")
Q1 = Part(tool=SKIDL, name="NMOS_LFPAK56", ref_prefix="Q", footprint="Package_TO_SOT_SMD:LFPAK56", value="BUK7J1R4-40H",
          pins=[Pin(num=1, name="S", func=Pin.types.PASSIVE), Pin(num=2, name="S", func=Pin.types.PASSIVE),
                Pin(num=3, name="S", func=Pin.types.PASSIVE), Pin(num=4, name="G", func=Pin.types.PASSIVE),
                Pin(num=5, name="D", func=Pin.types.PASSIVE)])
gate = Net("ID_GATE")
vcap = Net("ID_VCAP")
U_ID["ANODE"] += VIN
U_ID["CATHODE"] += V12
U_ID["GATE"] += gate
U_ID["EN"] += VIN
U_ID["GND"] += GND
U_ID["VCAP"] += vcap
C("100n", vcap, VIN)
for p in (1, 2, 3):
    Q1[p] += VIN       # źródło od strony wejścia
Q1[4] += gate
Q1[5] += V12           # dren (zakładka) — wyjście
tvs = Part("Device", "D_TVS", footprint="Diode_SMD:D_SMC", value="SMDJ26A")
tvs[1] += V12
tvs[2] += GND
for _ in range(2):
    ce = Part("Device", "C_Polarized", footprint="Capacitor_SMD:CP_Elec_10x10.5", value="220u 35V 105°C")
    ce[1] += V12
    ce[2] += GND
for _ in range(4):
    C("10u 50V", V12, GND, C50_FP)

# bezpiecznik zaworów 3-pin
F1 = Part("Device", "Fuse", footprint="Fuse:Fuseholder_Blade_Mini_Keystone_3568", value="10A mini")
F1[1] += V12
F1[2] += V12_ZAW

# ---------------------------------------------------------------- 12 V → 5 V (LMR33610, 400 kHz)
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
R("100k", V5, fb)      # FB: 1,0 V → 100k / 24,9k = 5,02 V
R("24k9", fb, GND)
C("22u 10V", V5, GND, "Capacitor_SMD:C_1206_3216Metric")
C("22u 10V", V5, GND, "Capacitor_SMD:C_1206_3216Metric")

# 5 V → 3,3 V
U_LDO = Part("Regulator_Linear", "AMS1117-3.3", value="AMS1117-3.3")
U_LDO["VI"] += V5
U_LDO["VO"] += V3
U_LDO["GND"] += GND
C("22u 10V", V5, GND, "Capacitor_SMD:C_1206_3216Metric")
C("22u 10V", V3, GND, "Capacitor_SMD:C_1206_3216Metric")

# ---------------------------------------------------------------- ESP32
U_MCU = Part("RF_Module", "ESP32-WROOM-32E", footprint="RF_Module:ESP32-WROOM-32D", value="ESP32-WROOM-32E-N8")
U_MCU["VDD"] += V3
U_MCU["GND"] += GND
C("10u", V3, GND, "Capacitor_SMD:C_0805_2012Metric")
C("100n", V3, GND)
en = Net("ESP_EN")
io0 = Net("ESP_IO0")
U_MCU["EN"] += en
U_MCU["IO0"] += io0
R("10k", V3, en)
C("1u", en, GND)
R("10k", V3, io0)
io2, io12 = Net("ESP_IO2"), Net("ESP_IO12")
U_MCU["IO2"] += io2
U_MCU["IO12"] += io12
R("10k", io2, GND)
R("10k", io12, GND)   # IO12 nisko przy starcie (napięcie flash 3,3 V)

# sygnały
SCK, MOSI, MISO = Net("SPI_SCK"), Net("SPI_MOSI"), Net("SPI_MISO")
RCLK, NSLEEP, PL = Net("REG_RCLK"), Net("DRV_nSLEEP"), Net("FLT_PL")
SDA, SCL = Net("I2C_SDA"), Net("I2C_SCL")
CAN_TX, CAN_RX = Net("CAN_TX"), Net("CAN_RX")
GPS_RX, GPS_TX = Net("GPS_TXD"), Net("GPS_RXD")     # nazwy od strony GPS
U0TX, U0RX = Net("U0TXD"), Net("U0RXD")
FLOW = Net("FLOW_3V3")
LAMP_IN = Net("LAMP_IN")
LED_G, LED_R = Net("LED_ZIELONA"), Net("LED_CZERWONA")
JP_MODE = Net("JP_CAN")

U_MCU["IO18"] += SCK
U_MCU["IO23"] += MOSI
U_MCU["IO19"] += MISO
U_MCU["IO5"] += RCLK
U_MCU["IO4"] += NSLEEP
U_MCU["IO13"] += PL
U_MCU["IO21"] += SDA
U_MCU["IO22"] += SCL
U_MCU["IO32"] += CAN_TX
U_MCU["IO33"] += CAN_RX
U_MCU["IO16"] += GPS_RX       # RX2 ← TXD GPS
U_MCU["IO17"] += GPS_TX       # TX2 → RXD GPS
U_MCU["TXD0/IO1"] += U0TX
U_MCU["RXD0/IO3"] += U0RX
U_MCU["IO35"] += FLOW
U_MCU["IO25"] += LAMP_IN
U_MCU["IO26"] += LED_G
U_MCU["IO27"] += LED_R
U_MCU["SENSOR_VP"] += JP_MODE  # IO36
R("100k", NSLEEP, GND)        # po resecie mostki uśpione = wyjścia wyłączone
R("10k", V3, PL)
R("10k", V3, JP_MODE)
jp3 = Part("Connector_Generic", "Conn_01x02", footprint="Connector_PinHeader_2.54mm:PinHeader_1x02_P2.54mm_Vertical", value="JP CAN/BT")
jp3[1] += JP_MODE
jp3[2] += GND

for net, col in ((LED_G, "zielona"), (LED_R, "czerwona")):
    d = Part("Device", "LED", footprint="LED_SMD:LED_0805_2012Metric", value=f"LED {col}")
    a = Net(f"LED_A_{col}")
    R("1k", net, a)
    d["A"] += a
    d["K"] += GND

# ---------------------------------------------------------------- USB-C + CH340C + auto-reset
J3 = Part("Connector", "USB_C_Receptacle_USB2.0_16P", footprint="Connector_USB:USB_C_Receptacle_GCT_USB4105-xx-A_16P_TopMnt_Horizontal", value="USB-C")
DP, DM = Net("USB_DP"), Net("USB_DM")
J3["A6"] += DP
J3["B6"] += DP
J3["A7"] += DM
J3["B7"] += DM
J3["A1"] += GND
J3["A12"] += GND
J3["B1"] += GND
J3["B12"] += GND
J3["S1"] += GND
vbus = Net("USB_VBUS")
for p in ("A4", "A9", "B4", "B9"):
    J3[p] += vbus
C("100n", vbus, GND)
R("5k1", J3["A5"], GND)
R("5k1", J3["B5"], GND)
for p in ("A8", "B8"):
    J3[p] += Net(f"NC_{p}")
U_USB = Part("Interface_USB", "CH340C", value="CH340C")
U_USB["VCC"] += V3
U_USB["V3"] += V3            # zasilanie 3,3 V: V3 połączone z VCC
U_USB["GND"] += GND
U_USB["UD+"] += DP
U_USB["UD-"] += DM
C("100n", V3, GND)
dtr, rts = Net("USB_DTR"), Net("USB_RTS")
U_USB[13] += dtr
U_USB[14] += rts
R("470", U_USB[2], U0RX)     # TXD CH340 → RX ESP
R("470", U0TX, U_USB[3])     # TX ESP → RXD CH340
for p in (7, 8, 9, 10, 11, 12, 15):
    U_USB[p] += Net(f"NC_CH340_{p}")
# auto-reset jak w ESP32-DevKitC
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
jp1 = Part("Connector_Generic", "Conn_01x02", footprint="Connector_PinHeader_2.54mm:PinHeader_1x02_P2.54mm_Vertical", value="JP terminator CAN")
jp1[1] += term
jp1[2] += CANL
# PESD2CAN (Nexperia, SOT-23): 1 i 2 = linie CAN, 3 = wspólna (masa)
tcan = Part(tool=SKIDL, name="PESD2CAN", ref_prefix="D", footprint="Package_TO_SOT_SMD:SOT-23", value="PESD2CAN",
            pins=[Pin(num=1, name="L1", func=Pin.types.PASSIVE), Pin(num=2, name="L2", func=Pin.types.PASSIVE),
                  Pin(num=3, name="COM", func=Pin.types.PASSIVE)])
tcan[1] += CANH
tcan[2] += CANL
tcan[3] += GND
J2[19] += CANH
J2[20] += CANL

# ---------------------------------------------------------------- przetwornik A/C (ciśnienie, napięcie, prąd lamp)
U_ADC = Part("Analog_ADC", "ADS1115IDGS", value="ADS1115IDGSR")
V3_ADC = Net("V3V3_ADC")        # filtr RC: mniej szumu od ESP32 (przegląd 2026-11-12)
R("10", V3, V3_ADC)
C("1u", V3_ADC, GND)
U_ADC["VDD"] += V3_ADC
U_ADC["GND"] += GND
U_ADC["ADDR"] += GND
U_ADC["SDA"] += SDA
U_ADC["SCL"] += SCL
U_ADC["ALERT/RDY"] += Net("ADC_RDY")
C("100n", V3_ADC, GND)
R("4k7", V3, SDA)
R("4k7", V3, SCL)
# ciśnienie: kostka B 16 +5 V (PTC), 17 sygnał, 18 masa
F3 = Part("Device", "Polyfuse", footprint="Fuse:Fuse_1812_4532Metric", value="0.2A")
F3[1] += V5
F3[2] += J2[16]
J2[18] += GND
psig = Net("CISN_SYG")
J2[17] += psig
ain0 = Net("ADC_AIN0")
R("10k", psig, ain0)
R("20k", ain0, GND)          # 4,5 V → 3,0 V
C("100n", ain0, GND)
jp2 = Part("Connector_Generic", "Conn_01x02", footprint="Connector_PinHeader_2.54mm:PinHeader_1x02_P2.54mm_Vertical", value="JP 4-20mA")
shunt = Net("CISN_SHUNT")
jp2[1] += psig
jp2[2] += shunt
R("150 0.1%", shunt, GND, "Resistor_SMD:R_0805_2012Metric")   # 4–20 mA → 0,6–3,0 V
tps = Part("Device", "D_TVS", footprint="Diode_SMD:D_SOD-323", value="PESD5V0S1BA")
tps[1] += psig
tps[2] += GND
U_ADC["AIN0"] += ain0
# napięcie zasilania
ain1 = Net("ADC_AIN1")
R("100k", V12, ain1)
R("10k", ain1, GND)           # 33 V → 3,0 V
C("100n", ain1, GND)
U_ADC["AIN1"] += ain1
# prąd lamp (IS)
ain2 = Net("ADC_AIN2")
U_ADC["AIN2"] += ain2
U_ADC["AIN3"] += GND

# ---------------------------------------------------------------- lampy robocze 20 A
U_LAMP = Part("Power_Management", "BTS50010-1TAD", value="BTS50015-1TAD")
LAMPA = Net("LAMPA_OUT")
U_LAMP["Vbb"] += V12
U_LAMP["OUT"] += LAMPA
lamp_gnd = Net("LAMP_GND")
U_LAMP["GND"] += lamp_gnd
R("47", lamp_gnd, GND)
R("4k7", LAMP_IN, U_LAMP["IN"])
is_net = Net("LAMP_IS")
U_LAMP["IS"] += is_net
R("2k2", is_net, GND)          # k_ILIS ≈ 22000: 20 A → ~2,0 V
R("10k", is_net, ain2)         # ograniczenie prądu do ADC przy sygnale błędu IS
C("100n", ain2, GND)
J2[7] += LAMPA
J2[8] += LAMPA

# ---------------------------------------------------------------- przepływomierz
F2 = Part("Device", "Polyfuse", footprint="Fuse:Fuse_1812_4532Metric", value="0.5A")
F2[1] += V12
F2[2] += J2[13]
fsig = Net("PRZEPL_SYG")
J2[14] += fsig
J2[15] += GND
R("4k7", J2[13], fsig)        # podciągnięcie dla czujników z otwartym kolektorem
fdiv = Net("PRZEPL_DZ")
R("10k", fsig, fdiv)
R("4k7", fdiv, GND)           # 12 V → 3,8 V (74LVC1G17 znosi 5,5 V)
C("1n", fdiv, GND)
U_SCH = Part("74xGxx", "74LVC1G17", footprint="Package_TO_SOT_SMD:SOT-353_SC-70-5", value="74LVC1G17")
U_SCH[2] += fdiv
U_SCH[4] += FLOW
U_SCH["VCC"] += V3
U_SCH["GND"] += GND
U_SCH[1] += Net("NC_SCH")
C("100n", V3, GND)

# ---------------------------------------------------------------- GPS (tylko prędkość)
U_GPS = Part(tool=SKIDL, name="L76K", ref_prefix="U", footprint="RF_GPS:Quectel_L76", value="Quectel L76K",
             pins=[Pin(num=n, name=nm, func=Pin.types.PASSIVE) for n, nm in [
                 (1, "GND"), (2, "TXD"), (3, "RXD"), (4, "1PPS"), (5, "WAKE_UP"), (6, "V_BCKP"), (7, "RES7"), (8, "VCC"),
                 (9, "RESET_N"), (10, "GND"), (11, "RF_IN"), (12, "GND"), (13, "ANTON"), (14, "VDD_RF"), (15, "RES15"),
                 (16, "RES16"), (17, "RES17"), (18, "SET")]])
for p in (1, 10, 12):
    U_GPS[p] += GND
V3_GPS = Net("V3V3_GPS")        # koralik ferrytowy: odcięcie szpilek prądu ESP32 (przegląd 2026-11-12)
fb = Part("Device", "FerriteBead", footprint="Inductor_SMD:L_0603_1608Metric", value="600R@100MHz")
fb[1] += V3
fb[2] += V3_GPS
U_GPS["VCC"] += V3_GPS
U_GPS["V_BCKP"] += V3_GPS
U_GPS["TXD"] += GPS_RX
U_GPS["RXD"] += GPS_TX
for p in (4, 5, 7, 9, 13, 15, 16, 17, 18):
    U_GPS[p] += Net(f"NC_GPS{p}")
C("10u", V3_GPS, GND, "Capacitor_SMD:C_0805_2012Metric")
C("100n", V3_GPS, GND)
rf = Net("GPS_RF")
U_GPS["RF_IN"] += rf
vrf, vrf2 = Net("GPS_VDD_RF"), Net("GPS_BIAS")
U_GPS["VDD_RF"] += vrf          # VDD_RF = WYJŚCIE modułu (zasilanie anteny aktywnej, = VCC) — bias przez R/L do RF_IN
R("10", vrf, vrf2)
Lb = Part("Device", "L", footprint="Inductor_SMD:L_0603_1608Metric", value="47nH")
Lb[1] += vrf2
Lb[2] += rf                    # zasilanie anteny aktywnej przez RF_IN
C("100n", vrf2, GND)
J4 = Part("Connector", "Conn_Coaxial", footprint="Connector_Coaxial:U.FL_Hirose_U.FL-R-SMT-1_Vertical", value="U.FL → SMA")
J4[1] += rf
J4[2] += GND

# ---------------------------------------------------------------- rejestry (IN/EN mostków) i odczyt błędów
# 16 mostków × (IN_A, IN_B, EN) = 48 sygnałów → 6 × 74HC595 w łańcuchu
regs = []
ser = MOSI
for i in range(6):
    u = Part("74xx", "74HC595", footprint="Package_SO:TSSOP-16_4.4x5mm_P0.65mm", value="74HC595PW-Q100")
    u["VCC"] += V3
    u["GND"] += GND
    u["SER"] += ser
    u["SRCLK"] += SCK
    u["RCLK"] += RCLK
    u["~{OE}"] += GND
    u["~{SRCLR}"] += V3
    C("100n", V3, GND)
    ser = Net(f"SR_CHAIN{i}")
    u[9] += ser               # QH' (numer pinu — nazwa „QH” pasowałaby też do QH')
    regs.append(u)
outs = []
for u in regs:
    for q in (15, 1, 2, 3, 4, 5, 6, 7):   # QA..QH
        outs.append(u[q])

U_FLT = Part("74xx", "74HC165", footprint="Package_SO:TSSOP-16_4.4x5mm_P0.65mm", value="74HC165PW-Q100")
U_FLT["VCC"] += V3
U_FLT["GND"] += GND
U_FLT["CP"] += SCK
U_FLT["~{PL}"] += PL
U_FLT["~{CE}"] += GND
U_FLT["DS"] += GND
U_FLT["Q7"] += MISO
U_FLT["~{Q7}"] += Net("NC_Q7N")
C("100n", V3, GND)

# ---------------------------------------------------------------- 8 × DRV8962 = 16 pełnych mostków po 2 A
drv_pins = [(1, "VCP"), (2, "VM"), (3, "PGND1"), (4, "OUT1"), (5, "OUT1"), (6, "OUT1"), (7, "OUT2"), (8, "OUT2"), (9, "OUT2"),
            (10, "PGND2"), (11, "VM"), (12, "VM"), (13, "PGND4"), (14, "OUT4"), (15, "OUT4"), (16, "OUT4"), (17, "OUT3"),
            (18, "OUT3"), (19, "OUT3"), (20, "PGND3"), (21, "VM"), (22, "GND"), (23, "GND"), (24, "DVDD"), (25, "VCC"),
            (26, "nFAULT"), (27, "OCPM"), (28, "MODE"), (29, "IPROPI4"), (30, "IPROPI3"), (31, "IPROPI2"), (32, "IPROPI1"),
            (33, "VREF"), (34, "EN4"), (35, "EN3"), (36, "EN2"), (37, "EN1"), (38, "IN4"), (39, "IN3"), (40, "IN2"),
            (41, "IN1"), (42, "nSLEEP"), (43, "CPL"), (44, "CPH"), (45, "PAD")]
drv_t = Part(tool=SKIDL, name="DRV8962DDW", ref_prefix="U", dest=TEMPLATE,
             footprint="Package_SO:HTSSOP-44-1EP_6.1x14mm_P0.635mm_EP5.2x14mm_Mask4.31x8.26mm_ThermalVias",
             pins=[Pin(num=n, name=nm, func=Pin.types.PASSIVE) for n, nm in drv_pins], value="DRV8962QDDWRQ1")

# wyjścia mostków → złącza: 0–12 sekcje (kostka A 1–26), 13 zawór główny (B 9–10), 14 regulacyjny (B 11–12), 15 rezerwa (B 21–22)
bridge_pins = [(J1[2 * b + 1], J1[2 * b + 2]) for b in range(13)] + [(J2[9], J2[10]), (J2[11], J2[12]), (J2[21], J2[22])]
bridge_names = [f"SEKCJA{b + 1}" for b in range(13)] + ["ZAW_GLOWNY", "ZAW_REG", "REZERWA"]
bit = 0
for k in range(8):
    u = drv_t()
    for nm in ("VM",):
        for p in u.pins:
            if p.name == nm:
                p += V12
    for p in u.pins:
        if p.name in ("PGND1", "PGND2", "PGND3", "PGND4", "GND", "PAD", "MODE"):
            p += GND
        if p.name.startswith("IPROPI"):
            p += GND          # bez regulacji prądu (ochrona nadprądowa w układzie zostaje)
    vcp, cpl, cph, dvdd, vref = Net(f"DRV{k}_VCP"), Net(f"DRV{k}_CPL"), Net(f"DRV{k}_CPH"), Net(f"DRV{k}_DVDD"), Net(f"DRV{k}_VREF")
    u["VCP"] += vcp
    u["CPL"] += cpl
    u["CPH"] += cph
    u["DVDD"] += dvdd
    u["VCC"] += dvdd          # brak osobnego zasilania logiki: VCC = DVDD (karta TI)
    u["VREF"] += vref
    u["OCPM"] += dvdd         # automatyczny powrót po przeciążeniu
    u["nSLEEP"] += NSLEEP
    flt = Net(f"DRV{k}_nFAULT")
    u["nFAULT"] += flt
    R("10k", V3, flt)
    U_FLT[f"D{k}"] += flt
    C("1u 16V", vcp, V12)
    C("100n 50V", cph, cpl)
    C("1u", dvdd, GND)
    C("100n", dvdd, GND)
    R("10k", dvdd, vref)
    R("10k", vref, GND)
    C("10n 50V", V12, GND)
    C("10n 50V", V12, GND)
    C("10u 50V", V12, GND, C50_FP)
    for half in range(2):
        b = 2 * k + half
        oa, ob = ("OUT1", "OUT2") if half == 0 else ("OUT3", "OUT4")
        ia, ib = ("IN1", "IN2") if half == 0 else ("IN3", "IN4")
        ea, eb = ("EN1", "EN2") if half == 0 else ("EN3", "EN4")
        na, nb = Net(f"{bridge_names[b]}_A"), Net(f"{bridge_names[b]}_B")
        u[oa] += na
        u[ob] += nb
        pa, pb = bridge_pins[b]
        pa += na
        pb += nb
        n_ina, n_inb, n_en = Net(f"M{b + 1}_INA"), Net(f"M{b + 1}_INB"), Net(f"M{b + 1}_EN")
        u[ia] += n_ina
        u[ib] += n_inb
        u[ea] += n_en
        u[eb] += n_en
        for n in (n_ina, n_inb, n_en):
            o = outs[bit]
            if b == 15 and n is n_en:
                # mostek 16 (REZERWA): EN wprost z ESP32 IO14 — sprzętowy PWM (LEDC, 15–20 kHz) do silnika DC
                # z regulacją prędkości (np. napęd aparatu wysiewającego); kierunek nadal z rejestru (INA/INB).
                # Bit rejestru zostaje wolny. Przy starcie nSLEEP = 0, więc impulsy IO14 przy bootowaniu nic nie włączą.
                U_MCU["IO14"] += n
                o += Net("NC_REG_BIT47")
            else:
                o += n
            R("10k", n, GND)      # stan bezpieczny: mostek wyłączony
            bit += 1
        # ochrona wyjść na kostce (ESD / indukcja przewodów)
        for n in (na, nb):
            d = Part("Device", "D_TVS", footprint="Diode_SMD:D_SOD-323", value="PESD24VS1UL")
            d[1] += n
            d[2] += GND
# 74HC165 wejścia D0–D7 = nFAULT układów 0–7 (zrobione wyżej)

if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    ERC()
    generate_netlist(file_=os.path.join(here, "maszyna.net"))
    # nazwy pinów do raportu przeglądu (review.py)
    import builtins
    default_circuit = builtins.default_circuit
    with open(os.path.join(here, "piny.tsv"), "w", encoding="utf-8") as f:
        for part in default_circuit.parts:
            for pin in part.pins:
                f.write(f"{part.ref}\t{pin.num}\t{pin.name}\n")
