# RailCore – Arbeitsanweisung für Claude

RailCore ist eine eigene Modellbahn-Steuerung (Java 21, Desktop) für eine Roco Z21 mit Z21 Detector 10808 (CAN, RailCom).
Ziel: bis zu 100 Züge vollautomatisch, wegbasiert und sicher fahren, mit Handbetrieb unter Zugbeeinflussung.

Maßgebliche Unterlagen:
- Technisches Lastenheft: „RailCore – Technisches Lastenheft“ (claude.ai: https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM)
- `docs/ENTSCHEIDUNGEN.md` – Entscheidungsprotokoll. Vor jeder Architekturänderung lesen; neue Entscheidungen oben eintragen.
- `docs/FEATURES.md` – Anforderungsübersicht

Sprache: Oberfläche, Kommentare, Dokumentation, Commit-Nachrichten und Protokolltexte auf Deutsch. Bezeichner im Code auf Englisch.

## Verbindliche Sicherheitsgrundsätze (nie aufweichen)

1. **Im Zweifel halt.** Jede Unsicherheit führt zum Anhalten. Aus fehlenden Rückmeldungen wird nie ein sicherer Zustand abgeleitet.
2. **Keine Bewegung ohne Reservierung** – auch nicht im Handbetrieb.
3. **Rückmelder sind Messpunkte**, die Position wird wegbasiert in mm gerechnet.
4. **Sicherheitsgrenzen sind nicht übersteuerbar** – weder per Hand, Fahrplan, API noch MQTT.
5. **Ein Neustart nimmt nichts als frei an.** Melder starten als „unbekannt“, Züge als „unbestätigt“.
6. **Freigabe eines Abschnitts** erst, wenn der berechnete Zugschluss ihn plus Sicherheitszuschlag verlassen hat (Entscheidung 14).
7. **Nothalt** umgeht jede Warteschlange und wird sofort gesendet; er wird nie automatisch aufgehoben.
8. Jede Zustandsänderung, jeder Eingriff und jede Störung wird im Journal protokolliert.

Wenn eine Aufgabe einem dieser Grundsätze widerspricht: nicht umsetzen, sondern den Konflikt benennen und nachfragen.

## Architekturregeln

- **Ein zentraler Steuerungs-Executor** ändert den Anlagenzustand (Reservierungen, Positionen, Zugzustände). Andere Threads (UDP, Watchdog, Datenbank, Oberfläche) legen nur Ereignisse bzw. Befehle in dessen Warteschlange.
- **Kern ohne Oberfläche:** Nichts unter `core`, `z21`, `layout`, `train`, `safety`, `diag` darf Swing importieren. Swing nur auf dem Event-Dispatch-Thread; die Oberfläche liest Momentaufnahmen und sendet Befehle.
- **Alle Befehle über den CommandScheduler** mit Priorität (Nothalt > Sicherheit > Automatik > Fahrplan > Hand > Komfort) und DCC-Budget; unveränderte Fahrstufen nie senden.
- **Zeit:** für Bewegung ausschließlich `TimeSource` (monoton, `System.nanoTime`); nie `System.currentTimeMillis` oder `Instant.now` für Rechnungen. In Tests `TimeSource.Manual`.
- **Hardware nur über `CommandStation`.** Zentralen-spezifischer Code bleibt im jeweiligen Adapter (`z21`, später weitere). Fähigkeiten (Firmware, F29–F31, CAN, Lok-Broadcast) nie voraussetzen, sondern erkennen.
- **Fahrbefehl = Fahrstufe + Richtung in einem Aufruf** (wie DCC); keine getrennten Setter.
- **Software Motion Control ist Standard** (CV3/CV4 = 0, Trägheit aus der Steuerung); Decoder Motion Control nur als Ausnahme je Lok (Entscheidung 8).
- **Stellvertreter-Adressen 9000–9999** sind für den Handbetrieb reserviert; echte Decoder in diesem Bereich sind ein Prüffehler (Entscheidung 7).
- **Datenhaltung:** SQLite ist maßgeblich, JSON nur Austausch/Sicherung.
- Keine fest einprogrammierten Kapazitätsgrenzen; Grenzen sind konfigurierbar und getestet.

## Arbeiten mit echter Hardware

- **Niemals ohne ausdrückliche Bestätigung des Nutzers** Gleisspannung einschalten, Loks fahren, Weichen schalten oder CVs schreiben – auch nicht in Tests oder im Prüfprogramm.
- Neue Funktionen zuerst im Simulator testen, dann Hardware-in-the-Loop, erst danach echter Betrieb.
- Protokolldetails, die als „an Hardware zu prüfen“ markiert sind (RailCom-Richtungsbits, POM-Zubehör, 10836-Adressierung, Stellvertreter-Adresse), nicht als gesichert behandeln.

## Code und Tests

- Paket: `at.schmoegl.railcore`. Bauen mit Gradle (`gradle build`), JDK 21.
- Jede neue Logik mit Test. Protokoll-Kodierung gegen bekannte Bytefolgen testen, Sicherheitsverhalten im Simulator (Fehlerkatalog im Lastenheft, Abschnitt 7).
- Kein Merge, wenn Tests fehlschlagen.
- Fremdbibliotheken sparsam und nur mit Begründung im Entscheidungsprotokoll.

## Erscheinungsbild

Logo: `docs/bilder/railcore-logo.png`. Programmsymbol: `docs/bilder/railcore-icon.png` (1024 px), `docs/bilder/railcore.ico` (Windows/Installer), Fenstersymbole `src/main/resources/icons/railcore-<Größe>.png` (16–256 px). Hausfarben: Nachtblau `#08121C` (Hintergrund), Signalrot `#EB1C24` (Akzent), Weiß (Schrift). Rot in der Oberfläche sparsam – es ist auch die Farbe für Nothalt und Störungen.

## Commits

- Kleine, in sich geschlossene Commits mit deutscher Nachricht (erste Zeile = was und warum).
- Neue Arbeit auf einem eigenen Branch, nicht direkt auf `main`.

## Stand

Meilenstein 1 (Kern und Sicherheitsbasis) in Arbeit. Nächster Schritt: Z21-Prüfprogramm zur Klärung der offenen Hardware-Fragen; danach Umstellung auf den zentralen Executor, Simulator und Positionsverfolgung.
