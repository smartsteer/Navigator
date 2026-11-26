"""
Płytka Ster SmartSteer (etap 15b) — schemat jako kod (SKiDL) → netlista KiCad (ster.net). Licencja GPLv3.
Specyfikacja: ../README.md. Uruchomienie:  python3 ster.py   → ster.net + sprawdzenia (assert) na końcu.

Pochodzenie:
 - Wyprowadzenia AMPSEAL 23, zworki A12/A13/A14, PRES/REM, CAN, LOCK, Danfoss, wejścia przełączników, WAS:
   Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), pliki
   TeensyModules/AIO Standard v4/PCB Build/AIO v4.5 Std/EasyEDA files/PCB_AIO v4.5 Std AMP 2024-02-05.json
   i „Ampseal pinout.jpg” (repozytorium github.com/AgOpenGPS-Official/Boards).
 - Przydział pinów Teensy: firmware TeensyModules/AIO v4 Firmware/AIO_v4_Firmware (to samo repozytorium).
 - Rdzeń i.MXRT1062 jak w Teensy 4.1 (zasilanie DCDC, kondensatory, kwarce, flash QSPI, układ bootloadera MKL02,
   kluczowanie 3,3 V przez PMIC_ON_REQ): odtworzone z otwartego projektu SparkFun MicroMod Teensy Processor
   (github.com/sparkfun/MicroMod_Processor_Board-Teensy, CC BY-SA 4.0) — tylko połączenia, nie pliki.
 - Mapa kulek BGA: imxrt1062_kulki.csv (z bga_pinout_t41 PJRC w repozytorium SparkFun, sprawdzone z kartą NXP
   IMXRT1060CEC tabele 86/87 — wszystkie 196 kulek zgodne).

Wyprowadzenia sprawdzone ze źródeł: i.MXRT1062 (karta NXP), MKL02 i W25Q (SparkFun), ZED-F9P, MCP2562, USBLC6-2SC6,
TLV757, MAX3232, ADS1115, LM74700 (biblioteki KiCad), DRV8701E, BUK7J1R4, LMR336x0, PESD2CAN, USB-C (jak płytki Maszyna).
NIE sprawdzone (brak karty w sesji): DP83825I i HR913550A — piny mają NAZWY zamiast numerów, numery uzupełnić z karty
w 15c (kinet2pcb zgłosi błąd, więc nie da się tego przeoczyć). Obudowy z biblioteką „smartsteer_ster:” — do zrobienia w 15c.
"""
import csv
import os

from skidl import KICAD7, Net, Part, Pin, SKIDL, TEMPLATE, ERC, generate_netlist, set_default_tool

set_default_tool(KICAD7)
import skidl
import builtins
NC = builtins.NC   # sieć „niepodłączone” (SKiDL 1.2 trzyma ją w builtins)
TU = os.path.dirname(os.path.abspath(__file__))
PAS = Pin.types.PASSIVE


def czesc(name, prefix, fp, piny, value=None, dest=None):
    """Część zdefiniowana w kodzie (bez bibliotek KiCad): piny = [(numer, nazwa), ...]."""
    kw = dict(tool=SKIDL, name=name, ref_prefix=prefix, footprint=fp, value=value or name,
              pins=[Pin(num=str(n), name=nm, func=PAS) for n, nm in piny])
    if dest:
        kw["dest"] = dest
    return Part(**kw)


R_FP, C_FP = "Resistor_SMD:R_0603_1608Metric", "Capacitor_SMD:C_0603_1608Metric"
C04 = "Capacitor_SMD:C_0402_1005Metric"
C1206 = "Capacitor_SMD:C_1206_3216Metric"
r_t = czesc("R", "R", R_FP, [(1, "1"), (2, "2")], dest=TEMPLATE)
c_t = czesc("C", "C", C_FP, [(1, "1"), (2, "2")], dest=TEMPLATE)


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


def dwa(name, prefix, fp, value, a, b, n1="1", n2="2"):
    p = czesc(name, prefix, fp, [(1, n1), (2, n2)], value)
    p[1] += a
    p[2] += b
    return p


def zworka2(a, b, zwarta, value):
    fp = "Jumper:SolderJumper-2_P1.3mm_Bridged_RoundedPad1.0x1.5mm" if zwarta else "Jumper:SolderJumper-2_P1.3mm_Open_RoundedPad1.0x1.5mm"
    return dwa("ZWORKA2", "JP", fp, value + (" (zwarta)" if zwarta else " (otwarta)"), a, b)


def zworka3(a, wsp, b, value):
    """Zworka 3‑padowa: wspólny pad 2 zwarty fabrycznie z padem 1 (a)."""
    j = czesc("ZWORKA3", "JP", "Jumper:SolderJumper-3_P1.3mm_Bridged12_RoundedPad1.0x1.5mm", [(1, "A"), (2, "C"), (3, "B")], value)
    j[1] += a
    j[2] += wsp
    j[3] += b
    return j


def led(od, value):
    """Pin → 1 kΩ → dioda → masa."""
    a = Net()
    R("1k", od, a)
    dwa("LED", "LED", "LED_SMD:LED_0805_2012Metric", value, GND, a, "K", "A")


def tp(net, value):
    czesc("TP", "TP", "TestPoint:TestPoint_Pad_D1.0mm", [(1, "1")], value)[1] += net


# ================================================================ sieci zasilania
GND = Net("GND")
VIN = Net("VIN_12V")          # AMPSEAL 22, przed idealną diodą
V12 = Net("V12")              # za idealną diodą (AMPSEAL 20 jak „+12V Board” w AIO)
VMOT = Net("V12_SILNIK")      # zasilanie mostka (AMPSEAL 18 „Alt cytron pwr” albo zworka z V12)
V5 = Net("V5")
V5WAS = Net("V5_WAS")         # zasilanie czujnika kąta (AMPSEAL 1), PTC
V3 = Net("V3V3")              # LDO 3,3 V, stale (SNVS, kluczowanie)
V3C = Net("V3V3_STER")        # 3,3 V kluczowane przez PMIC_ON_REQ (jak Teensy 4.1) — procesor, flash, PHY, MKL02
V3G = Net("V3V3_GNSS")        # osobne LDO dla ZED‑F9P
VSOC = Net("VDD_SOC_IN")      # 1,1 V z wewnętrznej przetwornicy DCDC i.MXRT
VBUS = Net("USB_VBUS")        # USB‑C procesora
for n in (GND, V12, V5, V3, V3C, V3G):
    n.drive = 99

# ================================================================ AMPSEAL 23 — układ 1:1 jak AIO v4.5 Std
J_AMP = czesc("AMPSEAL_23", "J", "Connector_TE-Connectivity:TE_AMPSEAL_1-776087-x_3Rows_23_P0.4mm_Horizontal",
              [(i, f"P{i}") for i in range(1, 24)], "AMPSEAL 23 (776087-1) — wiązka AIO")
AMP = {}   # numer → sieć (do sprawdzenia na końcu)


def amp(p, net):
    J_AMP[p] += net
    AMP[p] = net.name


WAS_H, WAS_L = Net("WAS_H"), Net("WAS_L")
LEFT, RIGHT, LOCK = Net("SILNIK_LEFT"), Net("SILNIK_RIGHT"), Net("LOCK")
STEERPIN, WORKPIN, PRESREM = Net("STEERPIN"), Net("WORKPIN"), Net("PRES_REM")
A12B, A13B, P13 = Net("A12B"), Net("A13B"), Net("AMP13_A14_CAN2L")
SPEEDP, SPEEDM = Net("SPEED_PLUS"), Net("SPEED_MINUS")
CAN3H, CAN3L, CAN2H, CAN2L = Net("CAN3_H"), Net("CAN3_L"), Net("CAN2_MASZYNA_H"), Net("CAN2_MASZYNA_L")
for p, n in ((1, V5WAS), (2, WAS_H), (3, WAS_L), (4, GND), (5, LEFT), (6, RIGHT), (7, LOCK), (8, STEERPIN),
             (9, WORKPIN), (10, PRESREM), (11, A12B), (12, A13B), (13, P13), (14, SPEEDP), (15, SPEEDM),
             (16, CAN3H), (17, CAN3L), (18, VMOT), (19, CAN2H), (20, V12), (21, GND), (22, VIN), (23, GND)):
    amp(p, n)

# ================================================================ wejście 12 V: idealna dioda + TVS
U_ID = czesc("LM74700", "U", "Package_TO_SOT_SMD:SOT-23-6",
             [(1, "VCAP"), (2, "GND"), (3, "EN"), (4, "CATHODE"), (5, "GATE"), (6, "ANODE")], "LM74700QDBVRQ1")
nmos_t = czesc("NMOS_LFPAK56", "Q", "Package_TO_SOT_SMD:LFPAK56", [(1, "S"), (2, "S"), (3, "S"), (4, "G"), (5, "D")],
               "BUK7J1R4-40H", dest=TEMPLATE)
idg, idc = Net("ID_GATE"), Net("ID_VCAP")
U_ID["ANODE"] += VIN
U_ID["EN"] += VIN
U_ID["CATHODE"] += V12
U_ID["GATE"] += idg
U_ID["VCAP"] += idc
U_ID["GND"] += GND
C("100n", idc, VIN)
for _ in range(2):            # Q5, Q6 w BOM — równolegle (silnik + reszta, do 15 A)
    q = nmos_t()
    for p in (1, 2, 3):
        q[p] += VIN
    q[4] += idg
    q[5] += V12
dwa("D_TVS", "D", "Diode_SMD:D_SMC", "SMCJ24A", V12, GND, "K", "A")
for _ in range(2):
    dwa("C_Polarized", "C", "Capacitor_THT:CP_Radial_D12.5mm_P5.00mm", "1000u 35V (jak AIO)", V12, GND, "+", "-")
zworka2(V12, VMOT, True, "SILNIK z V12 — otworzyć przy osobnym zasilaniu silnika na AMPSEAL 18")

# ================================================================ 12 V → 5 V (LMR33620, ta sama rodzina i piny co LMR33610)
U_BUCK = czesc("LMR33620", "U", "Package_SO:Texas_HSOP-8-1EP_3.9x4.9mm_P1.27mm_ThermalVias",
               [(1, "GND"), (2, "VIN"), (3, "EN"), (4, "PG"), (5, "FB"), (6, "VCC"), (7, "BOOT"), (8, "SW"), (9, "EP")],
               "LMR33620ADDAR")
bsw, bboot, bfb, bvcc = Net("BUCK_SW"), Net("BUCK_BOOT"), Net("BUCK_FB"), Net("BUCK_VCC")
vf = Net("V12_FILTR")
dwa("Polyfuse", "F", "Fuse:Fuse_1812_4532Metric", "PTC 1,1 A", V12, vf)
U_BUCK["VIN"] += vf
U_BUCK["EN"] += vf
U_BUCK[1] += GND
U_BUCK[9] += GND
U_BUCK["SW"] += bsw
U_BUCK["BOOT"] += bboot
U_BUCK["FB"] += bfb
U_BUCK["VCC"] += bvcc
U_BUCK["PG"] += NC
C("10u 50V", vf, GND, C1206)
C("100n 50V", vf, GND)
C("100n", bboot, bsw)
C("1u", bvcc, GND)
dwa("L", "L", "Inductor_SMD:L_Bourns_SRN8040TA", "10uH 3A", bsw, V5)
R("100k", V5, bfb)            # FB 1,0 V → 5,02 V
R("24k9", bfb, GND)
for _ in range(2):
    C("22u 10V", V5, GND, C1206)

# 5 V → 3,3 V: TLV75733 (procesor, PHY) i drugi TLV75733 przez ferryt (GNSS)
def ldo(wyj, wej, nazwa):
    u = czesc("TLV75733PDBV", "U", "Package_TO_SOT_SMD:SOT-23-5", [(1, "IN"), (2, "GND"), (3, "EN"), (4, "NC"), (5, "OUT")],
              nazwa)
    u["IN"] += wej
    u["EN"] += wej
    u["GND"] += GND
    u["OUT"] += wyj
    u["NC"] += NC
    C("1u", wej, GND)
    C("10u", wyj, GND, "Capacitor_SMD:C_0805_2012Metric")
    return u


ldo(V3, V5, "TLV75733PDBVR (3,3 V procesor)")
v5g = Net("V5_GNSS")
dwa("FerriteBead", "FB", "Inductor_SMD:L_0603_1608Metric", "600R@100MHz", V5, v5g)
ldo(V3G, v5g, "TLV75733PDBVR (3,3 V GNSS)")
dwa("Polyfuse", "F", "Fuse:Fuse_0805_2012Metric", "PTC 100 mA (czujnik kąta)", V5, V5WAS)
C("100n", V5WAS, GND)
# zasilanie z USB przy programowaniu na biurku (bez 12 V): VBUS → dioda → 5 V
dwa("D_Schottky", "D", "Diode_SMD:D_SOD-123", "SS14 / B5819W (VBUS→5 V)", V5, VBUS, "K", "A")

# ================================================================ i.MXRT1062 (BGA‑196) wg imxrt1062_kulki.csv
with open(os.path.join(TU, "imxrt1062_kulki.csv"), newline="") as f:
    KULKI = list(csv.DictReader(f, delimiter=";"))
U_MCU = czesc("MIMXRT1062", "U", "smartsteer_ster:NXP_MAPBGA-196_12x12mm_P0.8mm",
              [(k["kulka"], k["sygnal"]) for k in KULKI], "MIMXRT1062DVL6A")
TEENSY = {int(k["pin_teensy"]): k["kulka"] for k in KULKI if k["pin_teensy"]}
SYG = {}
for k in KULKI:
    SYG.setdefault(k["sygnal"], []).append(k["kulka"])
UZYTE = set()


def T(n, net):
    """Podłącz sieć do pinu Teensy 4.1 nr n = ta sama kulka i.MXRT co w Teensy (firmware AIO działa bez zmian)."""
    UZYTE.add(TEENSY[n])
    U_MCU[TEENSY[n]] += net


def S(nazwa):
    """Wszystkie kulki o danej nazwie sygnału (np. VSS)."""
    kulki = SYG[nazwa]
    UZYTE.update(kulki)
    return [U_MCU[b] for b in kulki]


def polacz(nazwa, net):
    for p in S(nazwa):
        p += net


# zasilanie (jak Teensy 4.1 / SparkFun MicroMod Teensy)
for nm in ("DCDC_IN_Q", "NVCC_SD0", "NVCC_SD1", "NVCC_EMC", "NVCC_GPIO", "VDDA_ADC_3P3", "VDD_HIGH_IN"):
    polacz(nm, V3C)
dcin, dclp = Net("DCDC_IN"), Net("DCDC_LP")
dwa("FerriteBead", "FB", "Inductor_SMD:L_0402_1005Metric", "120R@100MHz", V3C, dcin)
polacz("DCDC_IN", dcin)
polacz("DCDC_LP", dclp)
dwa("L", "L", "Inductor_SMD:L_1008_2520Metric", "4.7uH (DCDC rdzenia)", dclp, VSOC)
polacz("VDD_SOC_IN", VSOC)
polacz("DCDC_SENSE", VSOC)
for nm in ("VSS", "DCDC_GND", "TEST_MODE", "NGND_KEL0", "GPIO_AD_B0_05"):   # AD_B0_05 = BOOT_MODE1 = 0
    polacz(nm, GND)
C("2.2u", V3C, GND, C04)
C("4.7u", V3C, GND, C04)
C("0.22u", V3C, GND, C04)
C("0.22u", V3C, GND, C04)
C("10n", V3C, GND, C04)
C("10u", dcin, GND, C04)
C("4.7u", dcin, GND, C04)
C("0.22u", dcin, GND, C04)
C("10n", VSOC, GND, C04)
C("0.22u", VSOC, GND, C04)
C("4.7u", VSOC, GND, C04)
C("10u", VSOC, GND, C04)
SIEC = {}
for nm, wart in (("NVCC_PLL", ("0.22u", "4.7u")), ("VDD_HIGH_CAP", ("0.22u", "4.7u")), ("VDD_SNVS_CAP", ("0.22u",)),
                 ("VDD_USB_CAP", ("0.22u", "10u"))):
    n = Net(nm)
    SIEC[nm] = n
    polacz(nm, n)
    for w in wart:
        C(w, n, GND, C04)
snvs = Net("VDD_SNVS_IN")
polacz("VDD_SNVS_IN", snvs)
C("0.22u", snvs, GND, C04)
dwa("D_Schottky", "D", "Diode_SMD:D_SOD-523", "RB751S40", snvs, V3, "K", "A")
dwa("D_Schottky", "D", "Diode_SMD:D_SOD-523", "RB751S40", snvs, SIEC["VDD_USB_CAP"], "K", "A")
vbusf = Net("USB_VBUS_MCU")
dwa("FerriteBead", "FB", "Inductor_SMD:L_0402_1005Metric", "120R@100MHz", VBUS, vbusf)
C("2.2u", VBUS, GND, C04)
polacz("USB_OTG1_VBUS", vbusf)
polacz("USB_OTG2_VBUS", vbusf)
C("0.22u", vbusf, GND, C04)
C("0.22u", vbusf, GND, C04)

# kluczowanie 3,3 V przez PMIC_ON_REQ (P‑MOSFET + N‑MOSFET, jak Teensy 4.1)
pmic, qg = Net("PMIC_ON_REQ"), Net("V3_KLUCZ_G")
polacz("PMIC_ON_REQ", pmic)
qp = czesc("Q_PMOS_GSD", "Q", "Package_TO_SOT_SMD:SOT-23", [(1, "G"), (2, "S"), (3, "D")], "DMG2305UX")
qp["G"] += qg
qp["S"] += V3
qp["D"] += V3C
qn = czesc("Q_NMOS_GSD", "Q", "Package_TO_SOT_SMD:SOT-23", [(1, "G"), (2, "S"), (3, "D")], "2N7002")
qn["G"] += pmic
qn["S"] += GND
qn["D"] += qg
R("100k", V3, qg)
R("100k", V3C, pmic)

# kwarce
x24i, x24o, x32i, x32o = Net("XTALI"), Net("XTALO"), Net("RTC_XTALI"), Net("RTC_XTALO")
polacz("XTALI", x24i)
polacz("XTALO", x24o)
polacz("RTC_XTALI", x32i)
polacz("RTC_XTALO", x32o)
dwa("Crystal", "Y", "Crystal:Crystal_SMD_2016-4Pin_2.0x1.6mm", "24 MHz", x24i, x24o)
C("12p", x24i, GND, C04)
C("12p", x24o, GND, C04)
R("2.2M", x24i, GND, "Resistor_SMD:R_0402_1005Metric")
dwa("Crystal", "Y", "Crystal:Crystal_SMD_3215-2Pin_3.2x1.5mm", "32.768 kHz", x32i, x32o)
C("20p", x32i, GND, C04)
C("20p", x32o, GND, C04)

# flash QSPI (program) — SD_B1_06..11 jak Teensy 4.1
U_FL = czesc("W25Q64JV", "U", "smartsteer_ster:WSON-8_6x5mm_P1.27mm",
             [(1, "~CS"), (2, "DO_IO1"), (3, "~WP_IO2"), (4, "GND"), (5, "DI_IO0"), (6, "CLK"), (7, "~HOLD_IO3"), (8, "VCC"),
              (9, "EP")], "W25Q64JVZPIQ (8 MB)")
for pin, syg in ((1, "GPIO_SD_B1_06"), (6, "GPIO_SD_B1_07"), (5, "GPIO_SD_B1_08"), (2, "GPIO_SD_B1_09"),
                 (3, "GPIO_SD_B1_10"), (7, "GPIO_SD_B1_11")):
    n = Net("QSPI_" + syg[-5:])
    polacz(syg, n)
    U_FL[pin] += n
U_FL[8] += V3C
U_FL[4] += GND
U_FL[9] += NC
C("0.1u", V3C, GND, C04)
C("0.1u", V3C, GND, C04)

# układ bootloadera PJRC (MKL02) — połączenia jak w Teensy 4.x
U_BL = czesc("MKL02Z32VFG4", "U", "smartsteer_ster:QFN-16_3x3mm_P0.5mm_EP",
             [(1, "VDD"), (2, "VSS"), (3, "PTA3"), (4, "PTA4"), (5, "PTA5"), (6, "PTA6"), (7, "PTA7"), (8, "PTB0"),
              (9, "PTB1"), (10, "PTB2"), (11, "PTB3"), (12, "PTB4"), (13, "PTB5"), (14, "PTA0"), (15, "PTA1"),
              (16, "PTA2"), (17, "EP")], "PJRC MKL02 (bootloader Teensy)")
U_BL["VDD"] += V3C
U_BL["VSS"] += GND
U_BL["EP"] += GND
trst = Net("JTAG_TRST")
for bl, syg, nazwa in (("PTA4", "GPIO_AD_B0_04", "BOOT_MODE0"), ("PTA1", "GPIO_EMC_01", "DEBUG_EN"),
                       ("PTB4", "GPIO_B0_13", "DEBUG_SWO"), ("PTB5", "GPIO_AD_B0_08", "JTAG_MOD"),
                       ("PTB0", "GPIO_AD_B0_07", "JTAG_TCK"), ("PTA7", "GPIO_AD_B0_09", "JTAG_TDI"),
                       ("PTA6", "GPIO_AD_B0_10", "JTAG_TDO"), ("PTA5", "GPIO_AD_B0_06", "JTAG_TMS"),
                       ("PTB1", "POR_B", "POR_B")):
    n = Net(nazwa)
    polacz(syg, n)
    U_BL[bl] += n
U_BL["PTA3"] += trst
polacz("GPIO_AD_B0_11", trst)
polacz("DCDC_PSWITCH", trst)
R("100k", trst, GND)
prog = Net("PROGRAM")
U_BL["PTB2"] += prog
dwa("SW_Push", "SW", "Button_Switch_SMD:SW_SPST_TS-1187A", "PROGRAM", prog, GND)
bled = Net("BL_LED")
U_BL["PTB3"] += bled
la = Net()
R("1k", bled, la)
dwa("LED", "LED", "LED_SMD:LED_0603_1608Metric", "żółta — bootloader", la, V3C, "K", "A")
for bl, nm in (("PTA0", "SWD_CLK"), ("PTA2", "SWD_DIO")):
    n = Net(nm)
    U_BL[bl] += n
    tp(n, nm)

# USB‑C procesora (AgIO USB, programowanie)
def usb_c(nazwa, dp, dm, vbus):
    j = czesc("USB_C_Receptacle_USB2.0_16P", "J", "Connector_USB:USB_C_Receptacle_GCT_USB4105-xx-A_16P_TopMnt_Horizontal",
              [("A1", "GND"), ("A12", "GND"), ("B1", "GND"), ("B12", "GND"), ("A4", "VBUS"), ("A9", "VBUS"), ("B4", "VBUS"),
               ("B9", "VBUS"), ("A5", "CC1"), ("B5", "CC2"), ("A6", "D+"), ("B6", "D+"), ("A7", "D-"), ("B7", "D-"),
               ("A8", "SBU1"), ("B8", "SBU2"), ("S1", "SHIELD")], nazwa)
    for p in ("A1", "A12", "B1", "B12", "S1"):
        j[p] += GND
    for p in ("A4", "A9", "B4", "B9"):
        j[p] += vbus
    for p in ("A5", "B5"):
        cc = Net()
        j[p] += cc
        R("5.1k", cc, GND)
    for p in ("A6", "B6"):
        j[p] += dp
    for p in ("A7", "B7"):
        j[p] += dm
    j["A8"] += NC
    j["B8"] += NC
    esd = czesc("USBLC6-2SC6", "U", "Package_TO_SOT_SMD:SOT-23-6",
                [(1, "IO1"), (2, "GND"), (3, "IO2"), (4, "IO2b"), (5, "VBUS"), (6, "IO1b")], "USBLC6-2SC6")
    esd[1] += dp
    esd[6] += dp
    esd[3] += dm
    esd[4] += dm
    esd[2] += GND
    esd[5] += vbus
    C("1u", vbus, GND)


usb_dp, usb_dm = Net("USB1_DP"), Net("USB1_DN")
polacz("USB_OTG1_DP", usb_dp)
polacz("USB_OTG1_DN", usb_dm)
usb_c("USB-C procesor (AgIO, wgrywanie)", usb_dp, usb_dm, VBUS)

# ================================================================ Ethernet: DP83825I (RMII, zegar 50 MHz z i.MXRT) + RJ45
# PINY NAZWAMI — numery z karty DP83825I (SNLS637) do uzupełnienia w 15c.
U_PHY = czesc("DP83825I", "U", "smartsteer_ster:WQFN-24_3x3mm_P0.4mm_EP",
              [(n, n) for n in ("TX_EN", "TX_D0", "TX_D1", "RX_D0", "RX_D1", "CRS_DV", "RX_ER", "MDC", "MDIO", "RST_N",
                                "XI", "INT_PWDN_N", "LED0", "TD_P", "TD_M", "RD_P", "RD_M", "RBIAS", "VDDA3V3", "VDDIO",
                                "GND")], "DP83825IRMQR")
for syg, phy, nazwa in (("GPIO_B1_09", "TX_EN", "ENET_TX_EN"), ("GPIO_B1_07", "TX_D0", "ENET_TX0"),
                        ("GPIO_B1_08", "TX_D1", "ENET_TX1"), ("GPIO_B1_04", "RX_D0", "ENET_RX0"),
                        ("GPIO_B1_05", "RX_D1", "ENET_RX1"), ("GPIO_B1_06", "CRS_DV", "ENET_CRS_DV"),
                        ("GPIO_B1_11", "RX_ER", "ENET_RX_ER"), ("GPIO_B1_14", "MDC", "ENET_MDC"),
                        ("GPIO_B1_15", "MDIO", "ENET_MDIO"), ("GPIO_B0_14", "RST_N", "ENET_RST"),
                        ("GPIO_B1_10", "XI", "ENET_REF_CLK_50M"), ("GPIO_B0_15", "INT_PWDN_N", "ENET_PWDN")):
    n = Net(nazwa)
    polacz(syg, n)
    U_PHY[phy] += n
R("1.5k", V3C, U_PHY["MDIO"])
U_PHY["VDDA3V3"] += V3C
U_PHY["VDDIO"] += V3C
U_PHY["GND"] += GND
C("1u", V3C, GND, C04)
C("0.1u", V3C, GND, C04)
C("0.1u", V3C, GND, C04)
R("6.49k 1%", U_PHY["RBIAS"], GND, "Resistor_SMD:R_0402_1005Metric")
td_p, td_m, rd_p, rd_m = Net("ETH_TD_P"), Net("ETH_TD_M"), Net("ETH_RD_P"), Net("ETH_RD_M")
U_PHY["TD_P"] += td_p
U_PHY["TD_M"] += td_m
U_PHY["RD_P"] += rd_p
U_PHY["RD_M"] += rd_m
J_RJ = czesc("HR913550A", "J", "smartsteer_ster:RJ45_HanRun_HR913550A",
             [(n, n) for n in ("TD+", "TD-", "TCT", "RD+", "RD-", "RCT", "LED_ZIEL_A", "LED_ZIEL_K", "LED_ZOLTA_A",
                               "LED_ZOLTA_K", "SHIELD")], "HR913550A (RJ45 z transformatorem)")
J_RJ["TD+"] += td_p
J_RJ["TD-"] += td_m
J_RJ["RD+"] += rd_p
J_RJ["RD-"] += rd_m
tct = Net("ETH_CT")
J_RJ["TCT"] += tct
J_RJ["RCT"] += tct
C("0.1u", tct, GND, C04)
J_RJ["SHIELD"] += GND
lnk = Net("ETH_LED_LINK")
U_PHY["LED0"] += lnk
J_RJ["LED_ZIEL_A"] += V3C
R("470", lnk, J_RJ["LED_ZIEL_K"])
J_RJ["LED_ZOLTA_A"] += NC
J_RJ["LED_ZOLTA_K"] += NC

# ================================================================ GNSS: ZED‑F9P (UART1 ↔ Serial7, USB → USB‑C „GPS”)
U_GPS = czesc("ZED-F9P", "U", "RF_GPS:ublox_ZED",
              [(1, "GND"), (2, "RF_IN"), (3, "GND"), (4, "ANT_DETECT"), (5, "ANT_OFF"), (6, "~ANT_SHORT"), (7, "VCC_RF"),
               (12, "GND"), (14, "GND"), (19, "GEOFENCE_STAT"), (20, "RTK_STAT"), (26, "RXD2"), (27, "TXD2"), (32, "GND"),
               (33, "VCC"), (34, "VCC"), (36, "V_BCKP"), (37, "GND"), (38, "V_USB"), (39, "USB_DM"), (40, "USB_DP"),
               (41, "GND"), (42, "TXD"), (43, "RXD"), (44, "SDA"), (45, "SCL"), (46, "TX_READY"), (47, "D_SEL"),
               (48, "GND"), (49, "~RESET"), (50, "~SAFEBOOT"), (51, "EXTINT"), (53, "TIMEPULSE"), (55, "GND")]
              + [(n, "Reserved") for n in (8, 9, 10, 11, 13, 15, 16, 17, 18, 21, 22, 23, 24, 25, 28, 29, 30, 31, 35, 52, 54)],
              "ZED-F9P-04B")
for p in (1, 3, 12, 14, 32, 37, 41, 48, 55):
    U_GPS[p] += GND
for p in (8, 9, 10, 11, 13, 15, 16, 17, 18, 21, 22, 23, 24, 25, 28, 29, 30, 31, 35, 52, 54,
          4, 5, 6, 19, 44, 45, 46, 51):
    U_GPS[p] += NC
U_GPS["VCC"] += V3G
U_GPS["V_BCKP"] += V3G
C("10u", V3G, GND, "Capacitor_SMD:C_0805_2012Metric")
C("0.1u", V3G, GND)
U_GPS["D_SEL"] += NC          # otwarty = UART1 + I²C
gps_rst, gps_sb = Net("GPS_RESET"), Net("GPS_SAFEBOOT")
U_GPS["~RESET"] += gps_rst
U_GPS["~SAFEBOOT"] += gps_sb
tp(gps_rst, "GPS RESET")
tp(gps_sb, "GPS SAFEBOOT")
# antena aktywna: VCC_RF (3,3 V) → 10 Ω → 47 nH → linia RF (RF_IN ma wewnętrzny kondensator DC)
rf, vccrf, rfl = Net("GNSS_RF"), Net("GNSS_VCC_RF"), Net()
U_GPS["VCC_RF"] += vccrf
U_GPS["RF_IN"] += rf
R("10", vccrf, rfl)
dwa("L", "L", "Inductor_SMD:L_0402_1005Metric", "47nH (zasilanie anteny)", rfl, rf)
C("10n", rfl, GND, C04)
J_SMA = czesc("SMA", "J", "smartsteer_ster:SMA_Edge_50R", [(1, "In"), (2, "Ext")], "SMA krawędziowe — antena GNSS")
J_SMA["In"] += rf
J_SMA["Ext"] += GND
gtx, grx = Net("GPS_TX_SERIAL7_RX"), Net("GPS_RX_SERIAL7_TX")
U_GPS["TXD"] += gtx
U_GPS["RXD"] += grx
T(28, gtx)                  # Serial7 RX  (firmware AIO: SerialGPS = &Serial7, 460800)
T(29, grx)                  # Serial7 TX  (RTCM do odbiornika)
g2tx, g2rx = Net("GPS_TXD2"), Net("GPS_RXD2")
U_GPS["TXD2"] += g2tx
U_GPS["RXD2"] += g2rx
tp(g2tx, "GPS UART2 TX")
tp(g2rx, "GPS UART2 RX")
rtk = Net("GPS_RTK_STAT")
U_GPS["RTK_STAT"] += rtk
la = Net()
R("1k", V3G, la)
dwa("LED", "LED", "LED_SMD:LED_0805_2012Metric", "RTK (świeci = FIX)", rtk, la, "K", "A")
tpulse = Net("GPS_TIMEPULSE")
U_GPS["TIMEPULSE"] += tpulse
led(tpulse, "GPS 1PPS")
gusb_dp, gusb_dm, gvbus = Net("GPS_USB_DP"), Net("GPS_USB_DM"), Net("GPS_VBUS")
U_GPS["USB_DP"] += gusb_dp
U_GPS["USB_DM"] += gusb_dm
U_GPS["V_USB"] += gvbus
R("100k", gvbus, GND)          # bez kabla V_USB = 0 (u-blox: nieużywane USB → V_USB na masie)
usb_c("USB-C GPS (u-center)", gusb_dp, gusb_dm, gvbus)

# ================================================================ silnik skrętu: DRV8701E + 4 × BUK7J1R4 (tryb „Cytron”)
U_MOT = czesc("DRV8701E", "U", "Package_DFN_QFN:VQFN-24-1EP_4x4mm_P0.5mm_EP2.45x2.45mm_ThermalVias",
              [(1, "VM"), (2, "VCP"), (3, "CPH"), (4, "CPL"), (5, "GND"), (6, "VREF"), (7, "AVDD"), (8, "DVDD"),
               (9, "nFAULT"), (10, "SNSOUT"), (11, "SO"), (12, "IDRIVE"), (13, "nSLEEP"), (14, "EN"), (15, "PH"),
               (16, "GND"), (17, "GH1"), (18, "SH1"), (19, "GL1"), (20, "SN"), (21, "SP"), (22, "GL2"), (23, "SH2"),
               (24, "GH2"), (25, "PAD")], "DRV8701ERGER")
m_pwm, m_dir, m_flt, m_slp, m_so = Net("MOT_PWM_PIN2"), Net("MOT_DIR_PIN4"), Net("MOT_nFAULT"), Net("MOT_nSLEEP"), Net("MOT_SO")
mvcp, mcph, mcpl, mavdd, mdvdd, mvref, msp = (Net(n) for n in ("MOT_VCP", "MOT_CPH", "MOT_CPL", "MOT_AVDD", "MOT_DVDD",
                                                               "MOT_VREF", "MOT_BOCZNIK"))
U_MOT["VM"] += VMOT
for p in (5, 16, 25):
    U_MOT[p] += GND
for pin, n in (("VCP", mvcp), ("CPH", mcph), ("CPL", mcpl), ("AVDD", mavdd), ("DVDD", mdvdd), ("VREF", mvref),
               ("nSLEEP", m_slp), ("EN", m_pwm), ("PH", m_dir), ("nFAULT", m_flt), ("SO", m_so), ("SP", msp),
               ("SH1", LEFT), ("SH2", RIGHT)):
    U_MOT[pin] += n
U_MOT["SN"] += GND
U_MOT["SNSOUT"] += Net("MOT_SNSOUT")
C("1u 16V", mvcp, VMOT)
C("100n 50V", mcph, mcpl)
C("1u", mavdd, GND)
C("1u", mdvdd, GND)
R("10k", mavdd, mvref)        # VREF = 4,8 V · 4,7/14,7 = 1,53 V → ograniczenie 1,53 / (20 · 3 mΩ) ≈ 25 A szczyt
R("4.7k", mvref, GND)          # (cel 15 A ciągłe ustawiony prądem bramek i firmware — do sprawdzenia, README pkt 5)
R("200k", U_MOT["IDRIVE"], GND)
R("10k", mdvdd, U_MOT["SNSOUT"])
R("10k", V3C, m_flt)
C("100n 50V", VMOT, GND)
for _ in range(2):
    C("10u 50V", VMOT, GND, C1206)
for half, faza, gh, gl in ((1, LEFT, "GH1", "GL1"), (2, RIGHT, "GH2", "GL2")):
    hs, ls = nmos_t(), nmos_t()
    hs[5] += VMOT
    for p in (1, 2, 3):
        hs[p] += faza
    ls[5] += faza
    for p in (1, 2, 3):
        ls[p] += msp
    hs[4] += U_MOT[gh]
    ls[4] += U_MOT[gl]
R("3m 3W", msp, GND, "Resistor_SMD:R_2512_6332Metric")
T(2, m_pwm)                  # PWM1_LPWM → EN
T(4, m_dir)                  # DIR1_RL_ENABLE → PH
R("10k", m_pwm, GND)
R("10k", m_dir, GND)
T(33, m_flt)                 # nFAULT na wolnym pinie 33 (firmware 17)
so_f = Net("MOT_PRAD_A17")
R("1k", m_so, so_f)
C("10n", so_f, GND)
T(41, so_f)                  # CURRENT_SENSOR_PIN (A17): 60 mV/A (bocznik 3 mΩ × 20)
pin3 = Net("PWM2_PIN3")
T(3, pin3)
slp_stale = Net("nSLEEP_3V3")
zworka3(slp_stale, m_slp, pin3, "nSLEEP: 1‑2 stale (domyślnie) / 2‑3 od pinu 3 (silnik odłączony przy wyłączonym autosterze)")
R("10k", V3C, slp_stale)
R("100k", m_slp, GND)

# ================================================================ LOCK (zawór blokady, 5 A) i Danfoss (TC4427, opcja)
qlock = czesc("Q_PMOS_GDS", "Q", "Package_TO_SOT_SMD:TO-252-2", [(1, "G"), (2, "D"), (3, "S")], "AOD4185 (zamiast IRF5305)")
lg, lgd = Net("LOCK_G"), Net("LOCK_G_STER")
qlock["S"] += V12
qlock["D"] += LOCK
qlock["G"] += lg
R("10k", V12, lg)
dwa("D_Zener", "D", "Diode_SMD:D_SOD-123", "BZT52C15 (ochrona bramki)", V12, lg, "K", "A")
R("2.2k", lg, lgd)
qn2 = czesc("Q_NMOS_GSD", "Q", "Package_TO_SOT_SMD:SOT-23", [(1, "G"), (2, "S"), (3, "D")], "2N7002")
qn2["G"] += pin3
qn2["S"] += GND
qn2["D"] += lgd
R("100k", pin3, GND)
dwa("D_Schottky", "D", "Diode_SMD:D_SMA", "SS34 (gaszenie cewki LOCK)", LOCK, GND, "K", "A")
U_DF = czesc("TC4427", "U", "Package_SO:SOIC-8_3.9x4.9mm_P1.27mm",
             [(1, "NC"), (2, "INA"), (3, "GND"), (4, "INB"), (5, "OUTB"), (6, "VDD"), (7, "OUTA"), (8, "NC2")],
             "TC4427AEOA (Danfoss, NIE MONTOWAĆ domyślnie)")
U_DF["INA"] += m_pwm
U_DF["INB"] += GND
U_DF["GND"] += GND
U_DF["VDD"] += LOCK            # jak AIO: zasilany z wyjścia LOCK
U_DF["OUTB"] += NC
U_DF[1] += NC
U_DF[8] += NC
dfo = Net("DANFOSS_OUT")
U_DF["OUTA"] += dfo
C("1u 25V", LOCK, GND, "Capacitor_SMD:C_0805_2012Metric")
dfr = Net()
R("100", dfo, dfr)
zworka2(dfr, LEFT, False, "DANFOSS → LEFT (tylko bez mostka silnika)")

# ================================================================ czujnik kąta: ADS1115 przy 5 V + translatory I²C (jak AIO)
U_ADC = czesc("ADS1115IDGS", "U", "Package_SO:TSSOP-10_3x3mm_P0.5mm",
              [(1, "ADDR"), (2, "ALERT"), (3, "GND"), (4, "AIN0"), (5, "AIN1"), (6, "AIN2"), (7, "AIN3"), (8, "VDD"),
               (9, "SDA"), (10, "SCL")], "ADS1115IDGSR")
U_ADC["VDD"] += V5
U_ADC["GND"] += GND
U_ADC["ADDR"] += GND          # adres 0x48
U_ADC["ALERT"] += NC
U_ADC["AIN2"] += GND
U_ADC["AIN3"] += GND
C("100n", V5, GND)
for was, ain in ((WAS_H, "AIN0"), (WAS_L, "AIN1")):
    n = Net(was.name + "_ADC")
    R("1k", was, n)
    C("100n", n, GND)
    U_ADC[ain] += n
sda_hv, scl_hv, sda, scl = Net("SDA_5V"), Net("SCL_5V"), Net("SDA"), Net("SCL")
U_ADC["SDA"] += sda_hv
U_ADC["SCL"] += scl_hv
for lv, hv in ((sda, sda_hv), (scl, scl_hv)):
    q = czesc("Q_NMOS_GSD", "Q", "Package_TO_SOT_SMD:SOT-23", [(1, "G"), (2, "S"), (3, "D")], "BSS138")
    q["G"] += V3C
    q["S"] += lv
    q["D"] += hv
    R("10k", V3C, lv)
    R("10k", V5, hv)
T(18, sda)                   # Wire SDA
T(19, scl)                   # Wire SCL

# ================================================================ wejścia: Steer / Work / Remote (transoptory EL3H7)
def wejscie(net_amp, pin_teensy, nazwa):
    k = Net(nazwa + "_K")
    dwa("D", "D", "Diode_SMD:D_SOD-123F", "SS14 (jak AIO)", net_amp, k, "K", "A")   # zwarcie do masy w wiązce
    opto = czesc("EL3H7", "U", "Package_SO:SOP-4_4.4x2.6mm_P1.27mm", [(1, "A"), (2, "K"), (3, "E"), (4, "C")],
                 "EL3H7(B)(TA)-G")
    a = Net(nazwa + "_A")
    R("1k", V5, a)
    opto["A"] += a
    opto["K"] += k
    opto["E"] += GND
    wy = Net(nazwa + "_WE")
    opto["C"] += wy
    R("10k", V3C, wy)
    T(pin_teensy, wy)
    led_a = Net()
    R("2.2k", V5, led_a)       # dioda stanu przełącznika (świeci przy zwarciu)
    dwa("LED", "LED", "LED_SMD:LED_0603_1608Metric", nazwa, k, led_a, "K", "A")   # za diodą: 12 V na pinie jej nie zniszczy
    dwa("SW_Push", "SW", "Button_Switch_SMD:SW_SPST_TS-1187A", f"serwis {nazwa}", net_amp, GND)


wejscie(STEERPIN, 32, "STEER")
wejscie(WORKPIN, 34, "WORK")
REMOTEPIN, PRESS = Net("REMOTEPIN"), Net("PRESSURESENSE")
zworka3(REMOTEPIN, PRESREM, PRESS, "PRES/REM (AMPSEAL 10): 1‑2 Remote (domyślnie) / 2‑3 ciśnienie")
wejscie(REMOTEPIN, 37, "REMOTE")
# ciśnienie 0–5 V (dzielnik → 3,3 V) albo 4–20 mA (rezystor 250 Ω zworką) → A10 (pin 24)
pr = Net("PRESSURE_A10")
R("10k", PRESS, pr)
R("20k", pr, GND)
C("100n", pr, GND)
p250 = Net()
R("250 0.1%", PRESS, p250, "Resistor_SMD:R_0805_2012Metric")
zworka2(p250, GND, False, "4–20 mA")
T(24, pr)

# A12 / A13 (AMPSEAL 11, 12) — zworki jak AIO (H2, H5)
for net_amp, pin_t in ((A12B, 26), (A13B, 27)):
    n = Net(net_amp.name + "_MCU")
    zworka2(net_amp, n, True, net_amp.name)
    T(pin_t, n)
# AMPSEAL 13: w AIO A14; tu domyślnie CAN2_L „Maszyna” (pin 19 = CAN2_H — w AIO wolny)
a14 = Net("A14_MCU")
zworka3(CAN2L, P13, a14, "AMPSEAL 13: 1‑2 CAN Maszyna L (domyślnie) / 2‑3 A14 jak AIO")
T(38, a14)
R("100k", a14, GND)

# ================================================================ impuls prędkości (AMPSEAL 14/15) — transoptor
sp_in, sp_a = Net("SPEED_PWM_PIN36"), Net()
T(36, sp_in)
R("330", sp_in, sp_a)
opto = czesc("EL3H7", "U", "Package_SO:SOP-4_4.4x2.6mm_P1.27mm", [(1, "A"), (2, "K"), (3, "E"), (4, "C")], "EL3H7(B)(TA)-G")
opto["A"] += sp_a
opto["K"] += GND
opto["C"] += SPEEDP
opto["E"] += SPEEDM
spu = Net()
R("2.2k", V12, spu, "Resistor_SMD:R_0805_2012Metric")
zworka2(spu, SPEEDP, False, "SPEED+ podciągnięcie do 12 V")
zworka2(SPEEDM, GND, False, "SPEED- do masy")

# ================================================================ CAN: CAN3 (ciągnik / Keya) i CAN2 (płytka Maszyna)
def can(h, l, rx_t, tx_t, nazwa):
    u = czesc("MCP2562", "U", "Package_SO:SOIC-8_3.9x4.9mm_P1.27mm",
              [(1, "TXD"), (2, "VSS"), (3, "VDD"), (4, "RXD"), (5, "Vio"), (6, "CANL"), (7, "CANH"), (8, "STBY")],
              f"MCP2562-E/SN ({nazwa})")
    u["VDD"] += V5
    u["Vio"] += V3C
    u["VSS"] += GND
    u["STBY"] += GND
    u["CANH"] += h
    u["CANL"] += l
    rx, tx = Net(nazwa + "_RX"), Net(nazwa + "_TX")
    u["RXD"] += rx
    u["TXD"] += tx
    T(rx_t, rx)
    T(tx_t, tx)
    C("100n", V5, GND)
    C("100n", V3C, GND)
    esd = czesc("PESD2CAN", "D", "Package_TO_SOT_SMD:SOT-23", [(1, "L1"), (2, "L2"), (3, "COM")], "PESD1CAN,215")
    esd["L1"] += l
    esd["L2"] += h
    esd["COM"] += GND
    t = Net()
    R("120", h, t, "Resistor_SMD:R_0805_2012Metric")
    zworka2(t, l, False, f"terminator {nazwa}")


can(CAN3H, CAN3L, 30, 31, "CAN3")      # firmware AIO (Keya): CAN3, piny 30/31
can(CAN2H, CAN2L, 0, 1, "CAN2")        # do płytki Maszyna (docs/MASZYNA.md)

# ================================================================ porty szeregowe: radio RTK (Serial3), IMU (Serial5), zapas (Serial2)
J_RAD = czesc("JST_GH_4", "J", "Connector_JST:JST_GH_SM04B-GHS-TB_1x04-1MP_P1.25mm_Horizontal",
              [(1, "5V"), (2, "GND"), (3, "TX_RADIA"), (4, "RX_RADIA"), ("MP", "MP")], "Radio RTK (Serial3, 115200)")
J_RAD[1] += V5
J_RAD[2] += GND
J_RAD["MP"] += GND
r_rx, r_tx = Net("RADIO_DO_SERIAL3_RX"), Net("SERIAL3_TX_DO_RADIA")
R("100", J_RAD[3], r_rx)
R("100", r_tx, J_RAD[4])
T(15, r_rx)
T(14, r_tx)

# TM171 / BNO085 RVC: JST GH 6‑pin; zasilanie zworkami, TTL domyślnie, MAX3232 opcja (RS232)
J_IMU = czesc("JST_GH_6", "J", "Connector_JST:JST_GH_SM06B-GHS-TB_1x06-1MP_P1.25mm_Horizontal",
              [(1, "VCC_IMU"), (2, "GND"), (3, "TX_IMU"), (4, "RX_IMU"), (5, "ZAPAS"), (6, "GND2"), ("MP", "MP")],
              "IMU TM171 / BNO085 RVC (Serial5)")
vimu = Net("VCC_IMU")
J_IMU[1] += vimu
for p in (2, 6, "MP"):
    J_IMU[p] += GND
J_IMU[5] += NC
vimu_f = Net()
dwa("Polyfuse", "F", "Fuse:Fuse_0805_2012Metric", "PTC 200 mA", vimu_f, vimu)
zworka2(V3C, vimu_f, False, "IMU 3,3 V")
zworka2(V5, vimu_f, True, "IMU 5 V")
zworka2(V12, vimu_f, False, "IMU 12 V")
imu_tx, imu_rx = Net("IMU_TX_LINIA"), Net("IMU_RX_LINIA")
J_IMU[3] += imu_tx
J_IMU[4] += imu_rx
U_232 = czesc("MAX3232", "U", "Package_SO:SOIC-16_3.9x9.9mm_P1.27mm",
              [(1, "C1+"), (2, "VS+"), (3, "C1-"), (4, "C2+"), (5, "C2-"), (6, "VS-"), (7, "T2OUT"), (8, "R2IN"),
               (9, "R2OUT"), (10, "T2IN"), (11, "T1IN"), (12, "R1OUT"), (13, "R1IN"), (14, "T1OUT"), (15, "GND"),
               (16, "VCC")], "MAX3232EIDR (NIE MONTOWAĆ — tylko gdy TM171 ma RS232)")
U_232["VCC"] += V3C
U_232["GND"] += GND
c1p, c1m, c2p, c2m, vsp, vsm = (Net() for _ in range(6))
for pin, n in (("C1+", c1p), ("C1-", c1m), ("C2+", c2p), ("C2-", c2m), ("VS+", vsp), ("VS-", vsm)):
    U_232[pin] += n
C("100n", c1p, c1m)
C("100n", c2p, c2m)
C("100n", vsp, GND)
C("100n", vsm, GND)
C("100n", V3C, GND)
for p in ("T2OUT", "R2IN", "R2OUT", "T2IN"):
    U_232[p] += NC
s5rx, s5tx = Net("SERIAL5_RX"), Net("SERIAL5_TX")
T(21, s5rx)
T(20, s5tx)
ttl_rx, ttl_tx = Net(), Net()
R("100", imu_tx, ttl_rx)
R("100", ttl_tx, imu_rx)
U_232["R1IN"] += imu_tx
r1out, t1out = Net("IMU_R1OUT"), Net("IMU_T1OUT")
U_232["R1OUT"] += r1out
U_232["T1IN"] += s5tx
U_232["T1OUT"] += t1out
zworka3(ttl_rx, s5rx, r1out, "IMU RX: 1‑2 TTL (domyślnie) / 2‑3 RS232")
zworka3(ttl_tx, s5tx, NC, "IMU TX: 1‑2 TTL (domyślnie)")
zworka2(t1out, imu_rx, False, "IMU TX RS232 (przy RS232 przeciąć 1‑2 wyżej)")

# Serial2 (piny 7/8) — pady zapasowe (druga antena / UM982)
J_S2 = czesc("Conn_01x04", "J", "Connector_PinHeader_2.54mm:PinHeader_1x04_P2.54mm_Vertical",
             [(1, "3V3"), (2, "GND"), (3, "RX2"), (4, "TX2")], "Serial2 zapas (nie montować)")
J_S2[1] += V3C
J_S2[2] += GND
s2rx, s2tx = Net("SERIAL2_RX"), Net("SERIAL2_TX")
J_S2[3] += s2rx
J_S2[4] += s2tx
T(7, s2rx)
T(8, s2tx)

# ================================================================ diody stanu (piny jak w firmware AIO v4)
for pin_t, opis in ((5, "zasilanie"), (6, "Ethernet"), (9, "GPS czerwona"), (10, "GPS zielona"),
                    (11, "autoster czuwanie"), (12, "autoster aktywny"), (13, "GGA")):
    n = Net(f"LED_PIN{pin_t}")
    T(pin_t, n)
    led(n, opis)

# ================================================================ otwory M3
for _ in range(4):
    Part(tool=SKIDL, name="OTWOR", ref_prefix="H", footprint="MountingHole:MountingHole_3.2mm_M3_Pad_Via", value="M3 (masa)",
         pins=[Pin(num="1", name="1", func=PAS)])[1] += GND

# ================================================================ nieużywane kulki i.MXRT → NC
for k in KULKI:
    if k["kulka"] not in UZYTE:
        U_MCU[k["kulka"]] += NC
NIEUZYTE = [k for k in KULKI if k["kulka"] not in UZYTE]


# ================================================================ sprawdzenia (uruchamiane przy każdym generowaniu)
def sprawdz():
    # 1) AMPSEAL 1:1 z AIO v4.5 Std (nazwy sieci z pliku PCB AIO → nasze sieci); 13 i 19 — świadoma zmiana (CAN2)
    aio = {1: "5V_WAS", 2: "WAS_H", 3: "WAS_L", 4: "GND", 5: "LEFT", 6: "RIGHT", 7: "LOCK", 8: "STEERPIN", 9: "WORKPIN",
           10: "PRES/REM", 11: "A12B", 12: "A13B", 13: "A14B", 14: "SPEED+", 15: "SPEED-", 16: "CANH1", 17: "CANL1",
           18: "CYTRON_AUX", 19: "", 20: "+12V_BOARD", 21: "GND", 22: "+12V_IN", 23: "GND"}
    nasze = {"5V_WAS": "V5_WAS", "WAS_H": "WAS_H", "WAS_L": "WAS_L", "GND": "GND", "LEFT": "SILNIK_LEFT",
             "RIGHT": "SILNIK_RIGHT", "LOCK": "LOCK", "STEERPIN": "STEERPIN", "WORKPIN": "WORKPIN", "PRES/REM": "PRES_REM",
             "A12B": "A12B", "A13B": "A13B", "SPEED+": "SPEED_PLUS", "SPEED-": "SPEED_MINUS", "CANH1": "CAN3_H",
             "CANL1": "CAN3_L", "CYTRON_AUX": "V12_SILNIK", "+12V_BOARD": "V12", "+12V_IN": "VIN_12V"}
    for p, s in aio.items():
        if p in (13, 19):
            continue
        assert AMP[p] == nasze[s], (p, AMP[p], s)
    assert AMP[19] == "CAN2_MASZYNA_H" and AMP[13] == "AMP13_A14_CAN2L"
    # 2) piny Teensy użyte przez firmware AIO v4 → kulki jak w Teensy 4.1
    oczekiwane = {0: "G11", 1: "M11", 2: "F2", 3: "G5", 4: "H5", 5: "H3", 6: "D9", 7: "B11", 8: "A11", 9: "A10",
                  10: "D7", 11: "E8", 12: "E7", 13: "D8", 14: "L11", 15: "M12", 18: "K11", 19: "J11", 20: "L13",
                  21: "J13", 24: "K14", 26: "G12", 27: "J14", 28: "D5", 29: "C5", 30: "E4", 31: "C3", 32: "C10",
                  33: "H4", 34: "D14", 36: "C11", 37: "D11", 38: "H12", 41: "K12"}
    for t, b in oczekiwane.items():
        assert TEENSY[t] == b, (t, TEENSY[t], b)
        assert b in UZYTE, f"pin Teensy {t} niepodłączony"
    # 3) wszystkie kulki zasilania i masy podłączone
    for k in KULKI:
        s = k["sygnal"]
        if not s.startswith("GPIO_") and s not in ("WAKEUP", "ONOFF", "PMIC_STBY_REQ", "GPANAIO", "CCM_CLK1_P",
                                                     "CCM_CLK1_N", "USB_OTG1_CHD_B", "USB_OTG2_DN", "USB_OTG2_DP"):
            assert k["kulka"] in UZYTE, f"kulka {k['kulka']} {s} niepodłączona"
    assert len(KULKI) == 196


if __name__ == "__main__":
    sprawdz()
    ERC()
    generate_netlist(file_="ster.net")
    print(f"OK: kulek i.MXRT użytych {len(UZYTE)}, nieużywanych {len(NIEUZYTE)}")
