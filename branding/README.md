# Logo i ikony

Ikona aplikacji powstaje z pliku `branding/logo.png` (kwadrat, min. 512×512, przezroczyste tło):

    python3 tools/make_icons.py

Skrypt tworzy ikonę instalatora Windows (`composeApp/icons/app.ico`), ikonę okna
(`composeApp/src/desktopMain/resources/icon.png`) i ikony Androida (`mipmap-*/ic_launcher.png`).
Gdy `logo.png` nie ma, rysuje tymczasową ikonę.
