# 1. Ziel und Grundsätze

> Maßgebliche Fassung des Lastenhefts. Lesbare Fassung mit Diagrammen: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), wird nach jeder Änderung hier nachgezogen.

Ziel ist ein eigenständiges Desktop-Programm, das bis zu 100 Züge gleichzeitig vollautomatisch, wegbasiert und sicher über die Anlage fährt, mit Handbetrieb unter Zugbeeinflussung. Die Betriebslogik ist unabhängig von Zentrale, Oberfläche und späteren Apps.

**Verbindliche Grundsätze**

1. **Im Zweifel halt.** Jede Unsicherheit führt zum Anhalten, nie zum Weiterfahren. Aus einer fehlenden Rückmeldung wird nie ein sicherer Zustand abgeleitet.
2. **Keine Bewegung ohne Reservierung.** Ein Zug fährt nur in Ressourcen, die ihm reserviert sind, und das gilt auch für den Handbetrieb.
3. **Rückmelder sind Messpunkte, nicht die Positionsquelle.** Die Position wird kontinuierlich in Millimetern berechnet; Rückmelder und RailCom gleichen sie ab.
4. **Sicherheitsgrenzen sind nicht übersteuerbar.** Kein Hand-, API- oder Fahrplanbefehl überschreitet eine Bremskurve oder fährt in nicht reservierte Abschnitte.
5. **Ein Neustart nimmt nichts als frei an.** Zustände sind nach dem Start „unbekannt“, bis sie geprüft sind.
6. **Alles ist nachvollziehbar.** Jede Zustandsänderung, jeder Eingriff und jede Störung wird mit Zeitstempel protokolliert.
7. **Software-Nothalt ergänzt, ersetzt aber nicht** den elektrischen Schutz der Booster. Die Anlage wird nicht unbeaufsichtigt betrieben.

**Rahmenbedingungen**

| Thema | Festlegung |
| --- | --- |
| Sprache, Plattform | Java 25 LTS (Eclipse Temurin), Windows (Linux und Raspberry Pi möglich) |
| Oberfläche | Swing mit FlatLaf, Live-Gleisbild mit Java2D, keine Web-Oberfläche |
| Auslieferung | Installer mit mitgeliefertem Java (jpackage) |
| Datenhaltung | SQLite ist maßgeblich; JSON nur als Austausch- und Sicherungsformat |
| Maßstab | H0 (1:87), 128 Fahrstufen |
| Mengengerüst | Keine fest einprogrammierten Grenzen, aber konfigurierbare und getestete Kapazitäten: 100 aktive Zugverbände, 200 gleichzeitig gesteuerte Lokdecoder (Software-Testziel), 2.000 Blöcke, 10.000 Fahrstraßen, 1.024 Meldeabschnitte, 2.048 Zubehöradressen, 2.000 Fahrzeuge. Was auf der echten Z21 möglich ist, hängt von deren Decodergrenze ab (laut Roco 100 gleichzeitig gesteuerte Lokdecoder bei der schwarzen Z21) und wird gemessen. Heute 18 Module (144 Abschnitte) |
| Takt | Positionsrechnung mindestens 20× pro Sekunde mit monotoner Zeit (System.nanoTime), Lebenszeichen der Zentrale jede Sekunde; Reaktionszeiten sind gemessene Zielwerte, keine Echtzeitgarantie |

**Hardware-Ist-Stand** (aus MobaDB): Roco Z21 schwarz 10820, zwei Dual Booster 10807 (Bezirke ZS1, ZS2, Fahrstrom), CAN-Hub 10804, Pro Link, Z21 WLAN-Maus, Zimo KLUG; 18 Z21 Detector 10808 mit RailCom am CAN-Bus (Kontakt = (Modul − 1) · 8 + Eingang, z. B. RM017/1 = 129); Roco Weichendecoder 10836 (Adressen 1–16); überwiegend ZIMO-Lokdecoder, dazu ESU LokPilot 5.
