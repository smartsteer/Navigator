#!/usr/bin/env python3
"""Ikony aplikacji z jednego pliku logo.

Użycie:  python3 tools/make_icons.py [branding/logo.png]

Źródło: kwadratowy PNG (min. 512×512, najlepiej przezroczyste tło).
Bez argumentu bierze branding/logo.png, a gdy go nie ma — rysuje tymczasową ikonę.
Tworzy:
  composeApp/icons/app.ico                       — instalator i skróty Windows
  composeApp/src/desktopMain/resources/icon.png  — ikona okna i paska zadań
  composeApp/src/androidMain/res/mipmap-*/ic_launcher.png — Android
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BG = (12, 16, 14, 255)       # Ss.Ground — ciemne tło kabiny
ACCENT = (139, 227, 107, 255)  # Ss.Accent


def placeholder(size=1024):
    """Tymczasowa ikona (do podmiany na logo): zielony okrąg i linia prowadzenia."""
    im = Image.new("RGBA", (size, size), BG)
    d = ImageDraw.Draw(im)
    s = size / 24
    w = int(1.6 * s)
    d.ellipse([3 * s, 3 * s, 21 * s, 21 * s], outline=ACCENT, width=w)
    d.line([12 * s, 3 * s, 12 * s, 21 * s], fill=ACCENT, width=w)
    d.line([7 * s, 21 * s, 12 * s, 12 * s, 17 * s, 21 * s], fill=ACCENT, width=w, joint="curve")
    return im


def on_background(logo, size, pad=0.12):
    """Logo wyśrodkowane na kwadracie z marginesem; przezroczyste tło zostaje (ładniej na pulpicie)."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * (1 - 2 * pad))
    l = logo.copy()
    l.thumbnail((inner, inner), Image.LANCZOS)
    canvas.paste(l, ((size - l.width) // 2, (size - l.height) // 2), l)
    return canvas


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "branding", "logo.png")
    if os.path.exists(src):
        logo = Image.open(src).convert("RGBA")
        print("Logo:", src)
    else:
        logo = placeholder()
        print("Brak", src, "— tymczasowa ikona")

    big = on_background(logo, 1024)

    ico_dir = os.path.join(ROOT, "composeApp", "icons")
    os.makedirs(ico_dir, exist_ok=True)
    big.save(os.path.join(ico_dir, "app.ico"), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

    res = os.path.join(ROOT, "composeApp", "src", "desktopMain", "resources")
    os.makedirs(res, exist_ok=True)
    big.resize((256, 256), Image.LANCZOS).save(os.path.join(res, "icon.png"))

    # Android: tło z motywu, żeby ikona była czytelna na każdym launcherze
    for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        d = os.path.join(ROOT, "composeApp", "src", "androidMain", "res", "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        tile = Image.new("RGBA", (px, px), BG)
        fg = on_background(logo, px, pad=0.14)
        tile.paste(fg, (0, 0), fg)
        tile.save(os.path.join(d, "ic_launcher.png"))
    print("Gotowe.")


if __name__ == "__main__":
    main()
