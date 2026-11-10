#!/usr/bin/env python3
"""Ikony aplikacji z branding/ikona-navigator.svg (układ jak ikona WAR Admin: znak WAR + żółte kółko ze strzałką).

Użycie:  python3 tools/make_icons.py

Potrzebuje Pillow i Playwright z Chromium (SVG renderuje przeglądarka).
Tworzy:
  composeApp/icons/app.ico                                   — instalator i skróty Windows
  composeApp/src/desktopMain/resources/icon.png              — ikona okna i paska zadań
  composeApp/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml   — ikona adaptacyjna Androida
  composeApp/src/androidMain/res/mipmap-*/ic_launcher_foreground.png — jej warstwa przednia (108 dp)
  composeApp/src/androidMain/res/mipmap-*/ic_launcher.png    — zwykła ikona (zapas)
  composeApp/src/androidMain/res/values/ic_launcher_background.xml   — kolor tła (#050807, jak WAR)
Ikona adaptacyjna wypełnia cały kafelek launchera (kółko, zaokrąglony kwadrat…) — tak samo jak WAR i WAR Admin,
zamiast małego obrazka na białym tle.
"""
import io
import os
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
SVG = ROOT / "branding" / "ikona-navigator.svg"
BG = (5, 8, 7, 255)          # #050807 — tło ikon WAR
SKALA = 4                    # render 432 × 4


def render() -> Image.Image:
    from playwright.sync_api import sync_playwright
    px = 432 * SKALA
    html = ROOT / "build" / "ikona.html"
    html.parent.mkdir(exist_ok=True)
    html.write_text(f'<html><body style="margin:0;background:transparent"><img src="{SVG.as_uri()}" '
                    f'style="width:{px}px;height:{px}px;display:block"></body></html>')
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page(viewport={"width": px, "height": px})
        pg.goto(html.as_uri())
        png = pg.screenshot(omit_background=True)
        b.close()
    html.unlink()
    return Image.open(io.BytesIO(png)).convert("RGBA")


def kafelek(fg: Image.Image, size: int, wycinek=(72, 360), promien=0.22) -> Image.Image:
    """Ikona na ciemnym zaokrąglonym kwadracie (Windows, okno, zwykła ikona Androida): środek 72 dp z 108 dp."""
    a, b = (x * SKALA for x in wycinek)
    tlo = Image.new("RGBA", fg.size, BG)
    tlo.alpha_composite(fg)
    t = tlo.crop((a, a, b, b)).resize((size, size), Image.LANCZOS)
    maska = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(maska).rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=int(size * 4 * promien), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(t, (0, 0), maska.resize((size, size), Image.LANCZOS))
    return out


def main():
    fg = render()
    big = kafelek(fg, 1024)

    ico_dir = ROOT / "composeApp" / "icons"
    ico_dir.mkdir(parents=True, exist_ok=True)
    big.save(ico_dir / "app.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

    res = ROOT / "composeApp" / "src" / "desktopMain" / "resources"
    res.mkdir(parents=True, exist_ok=True)
    big.resize((256, 256), Image.LANCZOS).save(res / "icon.png")

    ares = ROOT / "composeApp" / "src" / "androidMain" / "res"
    for name, dp in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}.items():
        d = ares / ("mipmap-" + name)
        d.mkdir(parents=True, exist_ok=True)
        fg.resize((int(108 * dp), int(108 * dp)), Image.LANCZOS).save(d / "ic_launcher_foreground.png")
        kafelek(fg, int(48 * dp), promien=0.18).save(d / "ic_launcher.png")
    (ares / "mipmap-anydpi-v26").mkdir(exist_ok=True)
    (ares / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        '</adaptive-icon>\n')
    (ares / "values").mkdir(exist_ok=True)
    (ares / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        '    <color name="ic_launcher_background">#050807</color>\n</resources>\n')
    print("Gotowe.")


if __name__ == "__main__":
    main()
