# Logo i ikony

Ikona aplikacji powstaje z pliku `branding/logo.png` (kwadrat, min. 512×512, przezroczyste tło):

    python3 tools/make_icons.py

Skrypt tworzy ikonę instalatora Windows (`composeApp/icons/app.ico`), ikonę okna
(`composeApp/src/desktopMain/resources/icon.png`) i ikony Androida (`mipmap-*/ic_launcher.png`).
Gdy `logo.png` nie ma, rysuje tymczasową ikonę.

## Logo WAR (wektorowe, SVG)

Te same pliki co w WAR (Wirtualny Asystent Rolnika):

- `war-znak.svg` — sam znak (dymek z kłosem), bez napisu
- `war-logo.svg` — znak z napisem WAR, pionowo
- `war-naglowek.svg` — znak z napisem w poziomie (nagłówki, ekran startowy)
- `var-logo.svg`, `var-naglowek.svg` — wersja zagraniczna (VAR)
- `ikona.svg` — ikona aplikacji: znak WAR z grubszymi liniami (czytelny w 48 px) na ciemnym, zaokrąglonym tle.
  Z niej powstał `logo.png` (1024×1024) i ikony z `tools/make_icons.py`.
