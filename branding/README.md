# Logo i ikony

Ikona aplikacji powstaje z pliku `branding/ikona-navigator.svg` — układ jak ikona WAR Admin: znak WAR,
a w prawym dolnym rogu żółte kółko ze strzałką nawigacji (w WAR Admin — zębatka). Tło #050807 jak w WAR.

    python3 tools/make_icons.py        # potrzebuje Pillow i Playwright (Chromium)

Skrypt tworzy ikonę instalatora Windows (`composeApp/icons/app.ico`), ikonę okna
(`composeApp/src/desktopMain/resources/icon.png`) i ikonę adaptacyjną Androida (`mipmap-anydpi-v26/ic_launcher.xml`
+ `mipmap-*/ic_launcher_foreground.png`) — wypełnia cały kafelek launchera, tak jak WAR i WAR Admin.

## Logo WAR (wektorowe, SVG)

Te same pliki co w WAR (Wirtualny Asystent Rolnika):

- `war-znak.svg` — sam znak (dymek z kłosem), bez napisu
- `war-logo.svg` — znak z napisem WAR, pionowo
- `war-naglowek.svg` — znak z napisem w poziomie (nagłówki, ekran startowy)
- `var-logo.svg`, `var-naglowek.svg` — wersja zagraniczna (VAR)
- `ikona.svg` — ikona aplikacji: znak WAR z grubszymi liniami (czytelny w 48 px) na ciemnym, zaokrąglonym tle.
  Z niej powstał `logo.png` (1024×1024); ikony aplikacji robi się teraz z `ikona-navigator.svg`.
- `war-navigator-naglowek.svg`, `war-navigator-logo.svg` — napis „WAR Navigator” (poziomo / pionowo) w stylu WAR:
  znak, „WAR” zielony Poppins Bold, „Navigator” biały Poppins Medium; tekst jako krzywe. Wersje `var-navigator-*` — VAR.
  Źródło: skrypt `tools/logo/navigator.py` w repozytorium WAR.
