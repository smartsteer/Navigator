# Plan prac — SmartSteer Navigator

Każdy etap kończy się zieloną budową w GitHub Actions (APK + MSI + testy).
Nocna sesja robi **jeden** etap na przebieg, odhacza go tutaj i dopisuje krótką notatkę.

- [x] 0. Szkielet KMP (Android + Windows), wygląd z projektu, symulator, pure pursuit, NMEA, PGN 254/253
- [x] 1. Automatyczne aktualizacje na Windows (priorytet właściciela): workflow publikuje przy każdej zielonej budowie na main GitHub Release z rosnącym numerem wersji (packageVersion/versionCode z numeru przebiegu) i instalatorem MSI; aplikacja przy starcie (i co kilka godzin) sprawdza najnowszy Release, pokazuje „Dostępna aktualizacja X — Zainstaluj”, pobiera MSI i uruchamia msiexec, potem się zamyka. Repozytorium jest publiczne (od 2026-11-09), więc Release pobiera się bez logowania — żadnych tokenów w aplikacji. Android: na razie tylko powiadomienie o nowej wersji z linkiem do APK.
- [x] 1b. Transport UDP (Android + Windows): nasłuch 9999 (NMEA i PGN z modułów), wysyłka 8888 do 192.168.5.255; PGN 254 co 100 ms; ekran Połączenia pokazuje na żywo, co przychodzi; przełącznik trybu Symulator / Sprzęt
- [x] 2. Tryb Sprzęt: pozycja z GPS (LocalPlane), kurs z VTG / dwóch anten, prędkość; tworzenie linii AB przyciskami A i B z bieżącej pozycji; autoster na prawdziwej linii
- [x] 3. Algorytmy prowadzenia przeniesione z AgOpenGPS (pure pursuit z członem całkującym, Stanley) + ustawienia; oznaczyć pochodzenie w kodzie
- [x] 4. Krzywa AB (nagrywana jazdą) i kontur
- [x] 5. Granica pola nagrywana objazdem, uwrocie, zapis i wczytywanie pól (pliki JSON w katalogu aplikacji)
- [x] 6. Sekcje przez PGN 239, czasy włączania/wyłączania z wyprzedzeniem, zapis mapy pokrycia
- [x] 7. Klient NTRIP (poprawki RTK przez internet tabletu → do odbiornika przez UDP)
- [x] 8. IMU: kompensacja przechyłu (wysokość anteny), kurs z IMU
- [x] 9. Auto‑zawroty przeniesione z AgOpenGPS (YouTurn) na prawdziwych granicach
- [x] 10. Import KML / ISOXML (granice, linie)
- [ ] 11. Integracja z WAR — **wymaga decyzji właściciela, nie robić w nocy**

## Zasady interfejsu (obowiązują przy każdym etapie)

- Grafika ma być **najbardziej intuicyjna ze wszystkich systemów** prowadzenia na rynku: rolnik ma ogarnąć ekran bez instrukcji, w rękawicy, w słońcu i w trzęsącej kabinie.
- Kolorystyka zostaje bez zmian (ciemny motyw, zielony akcent #8BE36B, bursztyn #FFB547 do ostrzeżeń — obiekt Ss w Theme.kt).
- Najważniejsze akcje (autoster, sekcje, linia) zawsze pod ręką na ekranie Mapa, bez wchodzenia w menu; ustawienia i diagnostyka osobno.
- Duże przyciski (min. 52 dp), czytelne liczby, polskie nazwy bez żargonu, stan zawsze opisany słowem, nie tylko kolorem.
- Nowe ekrany zgodne z projektem graficznym (ten sam układ: górny pasek z lightbarem, menu po lewej, panel akcji po prawej).

## Notatki z przebiegów

- 2026-11-10 — etap 1: każda zielona budowa na main publikuje GitHub Release v0.1.<nr przebiegu> (MSI + APK); aplikacja sprawdza nowe wydanie 5 s po starcie i co 4 h, na Windows pobiera MSI i instaluje przez msiexec (potem sama się uruchamia), na Androidzie daje link do APK; przy włączonym autosterze instalacja zablokowana.
  W polu sprawdzić: zainstalować ręcznie MSI z tego wydania, po następnym wydaniu kliknąć „Zainstaluj” — czy Windows pyta o zgodę, czy nowa wersja startuje sama i czy ustawienia zostają. APK z CI jest podpisany kluczem debug z każdej budowy osobno — aktualizacja na tablecie może wymagać odinstalowania starej wersji.
- 2026-11-10 — stały klucz podpisu APK (composeApp/debug.keystore, tylko wersje testowe). Jednorazowo trzeba odinstalować wersję 0.1.11 z tabletu; kolejne APK instalują się już na starsze bez utraty danych.
- 2026-11-10 — etapy 1b i 2 (razem, bo tryb Sprzęt wymaga sieci): UDP 9999/8888, przełącznik SYMULATOR/SPRZĘT i podgląd pakietów na ekranie Połączenia; pozycja z GGA lub PANDA/PAOGI, kurs z dwóch anten (PAOGI/HDT) > VTG > z ruchu; linia AB przyciskami A/B (min. 20 m), najbliższy równoległy przejazd co szerokość maszyny, jazda w obie strony; autoster tylko przy żywym GPS i odpowiedzi PGN 253, wyłącza się po 1 s bez GPS lub modułu.
  W polu sprawdzić: komputer/tablet z adresem 192.168.5.x, ekran Połączenia → SPRZĘT — czy widać GGA/VTG i PGN 253; zapisać A i B, czy odchyłka i strona (lewo/prawo) się zgadzają; pierwsze włączenie autosteru na wolnej prędkości. Jeszcze nie ma: przesunięcia anteny względem osi i szerokości maszyny w ustawieniach (na razie 0 m i 3 m), zapisu linii po restarcie.
- 2026-11-10 — etap 3: pure pursuit z członem całkującym i Stanley przeniesione z AgOpenGPS (CABLine.cs, CGuidance.cs, CVehicle.cs); ekran Ustawienia (ciągnik, antena, maszyna, prowadzenie, moduł skrętu PGN 252, płytka PGN 251, zerowanie czujnika kąta); profile pojazdów w plikach JSON z kopią .last (Windows: Dokumenty\SmartSteer\Pojazdy). Poprawione kodowanie odległości w PGN 254 (2 cm/jedn., +127, 255 = wyłączony).
  W polu sprawdzić: wpisać wymiary i antenę, „Wyślij do płytki” i czy płytka przyjmuje ustawienia (np. odwrócenie czujnika kąta); wyzerować czujnik kąta przy kołach prosto; porównać pure pursuit i Stanley na tej samej linii; przy człon całkującym > 0 czy znika stała odchyłka na zboczu.
- 2026-11-10 — etap 8 (IMU) przeniesiony z AgOpenGPS/AgIO: przechył z PGN 253, PANDA/PAOGI i zewnętrznego IMU (PGN 211/212) z zerem, odwróceniem i filtrem; przesunięcie pozycji anteny na zboczu (sin przechyłu × wysokość anteny); fuzja kursu IMU z GPS; kompensacja zbocza w prowadzeniu; zakładka Ustawienia → IMU. Poprawione: PANDA podaje kurs i przechył ×10 (jak czyta AgIO).
  W polu sprawdzić: na płaskim „Wyzeruj przechył”; prawe koła niżej = przechył dodatni (inaczej „Odwróć przechył”); wpisać wysokość anteny i sprawdzić na zboczu, czy przejazdy w obie strony leżą w tym samym miejscu; na postoju kurs ma stać, a nie skakać.
- 2026-11-10 — etap 4: krzywa nagrywana jazdą (wygładzenie, przedłużenie końców, równoległe przejazdy z pominięciem pętli we wnętrzu łuku, prowadzenie pure pursuit/Stanley po łamanej) i kontur (pasy nagrywane podczas jazdy, prowadzenie wzdłuż poprzedniego przejazdu) — z AgOpenGPS CABCurve.cs i CContour.cs; przełącznik AB / Krzywa / Kontur na Mapie. Ikona aplikacji (Windows, okno, Android) z branding/logo.png przez tools/make_icons.py — na razie tymczasowa, czeka na logo WAR.
  W polu sprawdzić: nagrać krzywą (min. 20 m), czy sąsiednie przejazdy idą równo co szerokość maszyny także w ciasnym łuku; autoster wyłącza się na końcu krzywej; kontur — po zawróceniu łapie poprzedni przejazd i trzyma odstęp.
- 2026-11-10 — etapy 5 i 6: pola (zakładanie w miejscu ciągnika, otwieranie, autozapis co 30 s; granica nagrywana objazdem z lewej/środka/prawej krawędzi maszyny, uwrocie co szerokość przejazdu, linia AB i krzywa zapisane z polem; pliki JSON + pokrycie w Dokumenty\SmartSteer\Pola) oraz sekcje: zaczep i maszyna ciągniona (z AgOpenGPS Position.designer.cs), do 16 sekcji, włączanie/wyłączanie z wyprzedzeniem, wyłączanie poza granicą, na uwrociu, przy małej prędkości i wg przełącznika roboczego; PGN 239 co 100 ms (sekcje też w PGN 254); mapa pokrycia z uczciwymi hektarami (bez liczenia zakładek).
  W polu sprawdzić: założyć pole, objechać granicę, ustawić uwrocie; czy moduł maszyny przełącza sekcje; czy przy wjeździe na obrobione sekcje gasną z właściwym wyprzedzeniem (dobrać czasy); po zamknięciu i otwarciu pola wraca granica, linia i pokrycie. Jeszcze nie ma: zapisu konturu z polem, osobnych szerokości sekcji, ustawień modułu maszyny (PGN 238).
- 2026-11-10 — etap 7 (NTRIP) + test silnika i wykresy: klient NTRIP 1.0 (logowanie, pozycja GGA do castera VRS, ponawianie połączenia) przekazuje RTCM do płytki na UDP 2233 jak AgIO (NTRIPComm.Designer.cs); Ustawienia → NTRIP, stan na ekranie Połączenia. Test silnika „Free drive” z AgOpenGPS (Ustawienia → Moduł skrętu, ±1°/±5°, wyłącza się powyżej 3 km/h i po wyjściu z zakładki). Wykresy 30 s: kąt zadany/rzeczywisty i odchyłka w panelu „Na żywo”.
  W polu sprawdzić: wpisać dane castera, czy status zmienia się na „Odbieram poprawki” i odbiornik przechodzi w RTK FIX; test silnika — czy koła idą w stronę przycisku i kąt na wykresie nadąża za zadanym.
- 2026-11-10 — etap 9: auto-zawrót na granicy pola (wzorowany na CYouTurn.cs): zaczyna się, gdy linia AB/krzywa dochodzi do linii zawrotu (granica − odstęp), kształt „U” albo „omega” o promieniu R (domyślnie 8,1 m jak w AgOpenGPS), cofany aż zmieści się w granicy, na przejazd co ustawioną liczbę; kierunek zmienia się sam po każdym zawrocie, przycisk „Następny w prawo/lewo” i „Przerwij zawrót”; gdy zawrót się nie mieści — autoster staje przed granicą zamiast wyjeżdżać z pola.
  W polu sprawdzić: na wolnej prędkości, z granicą i uwrociem co najmniej 2 szerokości — czy zawrót zaczyna się w dobrym miejscu, nie wychodzi za granicę i ciągnik łapie sąsiedni przejazd; dobrać promień do ciągnika z maszyną.
- 2026-11-10 — sieć płytki do wyboru (domyślnie 192.168.5), „Szukaj modułów” (PGN 202/203) i „Przestaw moduły na sieć” (PGN 201) jak w AgIO, samouczek łączenia w aplikacji (Połączenia) i docs/LACZENIE.md.
- 2026-11-10 — etap 10: import KML i ISOXML (granica, AB, krzywa) z folderu Dokumenty\SmartSteer\Import; raport zabiegu GeoJSON do Dokumenty\SmartSteer\Raporty (sam przy zamknięciu pola) — wymiana z WAR przez pliki, opis w docs/WAR.md. Etap 11 (API WAR) czeka na opis interfejsu.
  W polu sprawdzić: Połączenia → samouczek, wszystkie kroki ✓; wczytać pole z KML/ISOXML i porównać granicę z rzeczywistością; po pracy zamknąć pole i sprawdzić raport w folderze Raporty.
