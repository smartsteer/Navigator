# Plan prac — SmartSteer Navigator

Każdy etap kończy się zieloną budową w GitHub Actions (APK + MSI + testy).
Nocna sesja robi **jeden** etap na przebieg, odhacza go tutaj i dopisuje krótką notatkę.

- [x] 0. Szkielet KMP (Android + Windows), wygląd z projektu, symulator, pure pursuit, NMEA, PGN 254/253
- [ ] 1. Transport UDP (Android + Windows): nasłuch 9999 (NMEA i PGN z modułów), wysyłka 8888 do 192.168.5.255; PGN 254 co 100 ms; ekran Połączenia pokazuje na żywo, co przychodzi; przełącznik trybu Symulator / Sprzęt
- [ ] 2. Tryb Sprzęt: pozycja z GPS (LocalPlane), kurs z VTG / dwóch anten, prędkość; tworzenie linii AB przyciskami A i B z bieżącej pozycji; autoster na prawdziwej linii
- [ ] 3. Algorytmy prowadzenia przeniesione z AgOpenGPS (pure pursuit z członem całkującym, Stanley) + ustawienia; oznaczyć pochodzenie w kodzie
- [ ] 4. Krzywa AB (nagrywana jazdą) i kontur
- [ ] 5. Granica pola nagrywana objazdem, uwrocie, zapis i wczytywanie pól (pliki JSON w katalogu aplikacji)
- [ ] 6. Sekcje przez PGN 239, czasy włączania/wyłączania z wyprzedzeniem, zapis mapy pokrycia
- [ ] 7. Klient NTRIP (poprawki RTK przez internet tabletu → do odbiornika przez UDP)
- [ ] 8. IMU: kompensacja przechyłu (wysokość anteny), kurs z IMU
- [ ] 9. Auto‑zawroty przeniesione z AgOpenGPS (YouTurn) na prawdziwych granicach
- [ ] 10. Import KML / ISOXML (granice, linie)
- [ ] 11. Integracja z WAR — **wymaga decyzji właściciela, nie robić w nocy**

## Notatki z przebiegów

