#!/usr/bin/env python3
"""Czujnik przeszkód dla WAR Navigatora: kamera Luxonis OAK-D (pomiar odległości) -> UDP JSON co ~0,1 s.

Protokół i ustawienia: docs/PRZESZKODY.md. Navigator (Ustawienia -> Nadzór autosteru -> Czujnik przeszkód)
liczy strefę stopu i wyłącza autoster; ten program tylko mówi, co i gdzie jest przed maszyną.

  python3 oak_przeszkody.py --symulacja                 # bez kamery: „osoba” zbliża się co 30 s (próba alarmu)
  python3 oak_przeszkody.py                             # OAK-D: ludzie, zwierzęta, pojazdy + siatka głębi
  python3 oak_przeszkody.py --adres 192.168.5.20        # Navigator na innym komputerze w kabinie

Kamera: pip install "depthai<3" blobconverter (biblioteka DepthAI 2.x; model MobileNet-SSD pobiera się przy
pierwszym uruchomieniu z internetu). NIE SPRAWDZONE NA KAMERZE — przed pracą w polu próba na placu:
osoba przed maszyną, słup, przejazd obok płotu (fałszywe alarmy), kamera brudna/zasłonięta.

Układ: z = metry przed PRZODEM maszyny (odległość od kamery minus --przod), x = metry w prawo (+) / w lewo (−)
od osi maszyny (kamera na osi; inaczej --bok).
"""
import argparse
import json
import math
import socket
import sys
import time

# MobileNet-SSD (VOC): co jest przeszkodą, którą trzeba nazwać
KLASY = {15: "osoba", 12: "pies", 8: "kot", 10: "krowa", 13: "koń", 17: "owca", 7: "samochód", 6: "autobus",
         14: "motocykl", 2: "rower", 19: "pociąg"}


def wyslij(sock, adres, przeszkody, ok=True, zrodlo="oak-przod"):
    pk = {"v": 1, "zrodlo": zrodlo, "ok": ok, "t": round(time.time(), 2),
          "przeszkody": [{"z": round(z, 2), "x": round(x, 2), "klasa": k, "p": round(p, 2)} for z, x, k, p in przeszkody]}
    sock.sendto(json.dumps(pk, ensure_ascii=False).encode("utf-8"), adres)


def symulacja(sock, adres, okres=30.0):
    """Bez kamery: przez pierwsze 20 s okresu „osoba” idzie z 25 m do 2 m przed maszyną, potem 10 s czysto."""
    print(f"symulacja -> {adres[0]}:{adres[1]} (Ctrl+C — koniec)")
    t0 = time.time()
    while True:
        f = (time.time() - t0) % okres
        przeszkody = [(25 - f * 1.15, 0.3 * math.sin(f), "osoba", 0.9)] if f < 20 else []
        wyslij(sock, adres, przeszkody, zrodlo="symulacja")
        time.sleep(0.1)


def kamera(sock, adres, a):
    try:
        import depthai as dai
        import blobconverter
    except ImportError:
        sys.exit('Brak biblioteki: pip install "depthai<3" blobconverter')

    p = dai.Pipeline()
    rgb = p.create(dai.node.ColorCamera)
    rgb.setPreviewSize(300, 300)
    rgb.setInterleaved(False)
    rgb.setColorOrder(dai.ColorCameraProperties.ColorOrder.BGR)
    rgb.setFps(a.fps)
    lewa, prawa = p.create(dai.node.MonoCamera), p.create(dai.node.MonoCamera)
    for m, gniazdo in ((lewa, dai.CameraBoardSocket.CAM_B), (prawa, dai.CameraBoardSocket.CAM_C)):
        m.setResolution(dai.MonoCameraProperties.SensorResolution.THE_400_P)
        m.setBoardSocket(gniazdo)
        m.setFps(a.fps)
    stereo = p.create(dai.node.StereoDepth)
    stereo.setDefaultProfilePreset(dai.node.StereoDepth.PresetMode.HIGH_DENSITY)
    stereo.setDepthAlign(dai.CameraBoardSocket.CAM_A)
    stereo.setOutputSize(lewa.getResolutionWidth(), lewa.getResolutionHeight())
    lewa.out.link(stereo.left)
    prawa.out.link(stereo.right)

    nn = p.create(dai.node.MobileNetSpatialDetectionNetwork)
    nn.setBlobPath(blobconverter.from_zoo(name="mobilenet-ssd", shaves=6))
    nn.setConfidenceThreshold(0.4)
    nn.input.setBlocking(False)
    nn.setBoundingBoxScaleFactor(0.5)
    nn.setDepthLowerThreshold(200)
    nn.setDepthUpperThreshold(40000)
    rgb.preview.link(nn.input)
    stereo.depth.link(nn.inputDepth)
    wyj_nn = p.create(dai.node.XLinkOut)
    wyj_nn.setStreamName("det")
    nn.out.link(wyj_nn.input)

    # siatka głębi: pas na wysokości przed maszyną (nie ziemia) podzielony na kolumny — najbliższy punkt w każdej.
    # Łapie to, czego sieć nie zna (słup, bela, ściana). Pas ustawia się --pas (część wysokości obrazu, od góry).
    kolumny = a.kolumny
    if kolumny:
        sl = p.create(dai.node.SpatialLocationCalculator)
        g, d = a.pas
        for i in range(kolumny):
            c = dai.SpatialLocationCalculatorConfigData()
            c.depthThresholds.lowerThreshold = 300
            c.depthThresholds.upperThreshold = 30000
            c.calculationAlgorithm = dai.SpatialLocationCalculatorAlgorithm.MIN
            c.roi = dai.Rect(dai.Point2f(i / kolumny, g), dai.Point2f((i + 1) / kolumny, d))
            sl.initialConfig.addROI(c)
        stereo.depth.link(sl.inputDepth)
        wyj_sl = p.create(dai.node.XLinkOut)
        wyj_sl.setStreamName("siatka")
        sl.out.link(wyj_sl.input)

    with dai.Device(p) as dev:
        q_det = dev.getOutputQueue("det", maxSize=4, blocking=False)
        q_sl = dev.getOutputQueue("siatka", maxSize=4, blocking=False) if kolumny else None
        print(f"OAK-D -> {adres[0]}:{adres[1]} (Ctrl+C — koniec)")
        ost = time.time()
        while True:
            przeszkody = []
            det = q_det.tryGet()
            if det is not None:
                ost = time.time()
                for d in det.detections:
                    z = d.spatialCoordinates.z / 1000 - a.przod
                    if d.spatialCoordinates.z <= 0:
                        continue
                    przeszkody.append((max(0.0, z), d.spatialCoordinates.x / 1000 + a.bok, KLASY.get(d.label, "obiekt"), d.confidence))
            if q_sl is not None:
                s = q_sl.tryGet()
                if s is not None:
                    for loc in s.getSpatialLocations():
                        z = loc.spatialCoordinates.z / 1000
                        if 0.3 < z < 30:
                            przeszkody.append((max(0.0, z - a.przod), loc.spatialCoordinates.x / 1000 + a.bok, "obiekt", 0.6))
            wyslij(sock, adres, przeszkody, ok=time.time() - ost < 1.0)
            time.sleep(0.05)


def main():
    ap = argparse.ArgumentParser(description="Czujnik przeszkód OAK-D -> WAR Navigator (UDP)")
    ap.add_argument("--adres", default="127.0.0.1", help="komputer z Navigatorem (domyślnie ten sam)")
    ap.add_argument("--port", type=int, default=17777)
    ap.add_argument("--symulacja", action="store_true", help="bez kamery — udawana osoba co 30 s")
    ap.add_argument("--przod", type=float, default=0.0, help="ile metrów kamera jest ZA przodem maszyny (np. na dachu kabiny: 2.5)")
    ap.add_argument("--bok", type=float, default=0.0, help="przesunięcie kamery w prawo od osi maszyny (m, w lewo ujemne)")
    ap.add_argument("--fps", type=int, default=15)
    ap.add_argument("--kolumny", type=int, default=8, help="siatka głębi: liczba kolumn (0 — wyłączona, tylko ludzie/zwierzęta/pojazdy)")
    ap.add_argument("--pas", type=float, nargs=2, default=(0.35, 0.6), metavar=("GÓRA", "DÓŁ"),
                    help="siatka głębi: pas obrazu (0 = góra, 1 = dół) — tak, żeby nie widział ziemi przed maszyną")
    a = ap.parse_args()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    adres = (a.adres, a.port)
    try:
        symulacja(sock, adres) if a.symulacja else kamera(sock, adres, a)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
