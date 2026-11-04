# RailCore – Arbeitsanweisung für Claude

RailCore ist eine eigene Modellbahn-Steuerung (Java 25, Desktop) für eine Roco Z21 mit Z21 Detector 10808 (CAN, RailCom).
Ziel: bis zu 100 Züge vollautomatisch, wegbasiert und sicher fahren, mit Handbetrieb unter Zugbeeinflussung.

Maßgebliche Unterlagen:
- `docs/lastenheft/` – **maßgebliche Fassung** des technischen Lastenhefts als Kapitel-Dateien; nur das für die Aufgabe nötige Kapitel lesen (Inhaltsverzeichnis in README.md).
- Lesbare Fassung mit Diagrammen: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM).
- Jede Änderung am Lastenheft zuerst in docs/lastenheft/, danach im claude.ai-Dokument nachziehen (Entscheidung 25).
- `docs/ENTSCHEIDUNGEN.md` – Entscheidungsprotokoll. Vor jeder Architekturänderung lesen; neue Entscheidungen oben eintragen.
- `docs/FEATURES.md` – Anforderungsübersicht
- `docs/anleitung/` (Anwender-Anleitung) und `docs/wiki/` (Nachschlagewissen für Anwender und Entwickler), je Sprache ein Ordner (`de`, später `en`); Schreibregeln in `docs/dokumentation/RICHTLINIEN.md` (Entscheidung 31). Jede für Anwender sichtbare Änderung zieht Anleitung bzw. Wiki im selben PR nach; Funktionen nie als vorhanden beschreiben, bevor sie gebaut sind.

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
- **Stellvertreter-Adressen 9000–9999** sind für den Handbetrieb reserviert; echte Decoder in diesem Bereich sind ein Prüffehler (Entscheidung 7). Sie gelten für Handregler an der Z21 (Z21-App, X-Bus, Zimo KLUG); die WLAN-Maus verbindet sich direkt mit RailCore über einen Z21-kompatiblen LAN-Server, ihre Befehle gehen nur nach Prüfung an die Zentrale (Entscheidung 21).
- **Datenhaltung:** SQLite ist maßgeblich, JSON nur Austausch/Sicherung.
- Keine fest einprogrammierten Kapazitätsgrenzen; Grenzen sind konfigurierbar und getestet.

## Arbeiten mit echter Hardware

- **Niemals ohne ausdrückliche Bestätigung des Nutzers** Gleisspannung einschalten, Loks fahren, Weichen schalten oder CVs schreiben – auch nicht in Tests oder im Prüfprogramm.
- Neue Funktionen zuerst im Simulator testen, dann Hardware-in-the-Loop, erst danach echter Betrieb.
- Aufgaben mit Zugriff auf echte Hardware (Z21-Verbindung, Prüfprogramm, Gleisspannung, Loks, Weichen, CVs) nie an Agenten abgeben, sondern in der Hauptsitzung erledigen, damit der Nutzer jeden Schritt live sieht und eingreifen kann (Entscheidung 22).
- Protokolldetails, die als „an Hardware zu prüfen“ markiert sind (R-Bus-Modulzählung, Antwortzeit des Echos, WLAN-Maus am RailCore-LAN-Server), nicht als gesichert behandeln. Geklärt (`docs/Z21-PRUEFPROGRAMM.md`): RailCom-Richtungsbits des 10808 zeigen die Aufgleisrichtung der Lok, nicht die Fahrtrichtung; die Z21 sendet trotz Abo keinen Lok-Broadcast bei Fremdsteuerung; Weichenlage nach RCN-213 (P=1 gerade, P=0 abzweigend), 10836-Adressierung und POM-Lesen am Zubehördecoder bestätigt; die Z21 bestätigt eigene Fahrbefehle mit einer Lokmeldung.
- PowerShell startet den Wrapper nur als `.\gradlew`; Befehle für den Nutzer immer so angeben.

## Code und Tests

- Paket: `at.schmoegl.railcore`. Bauen mit dem Gradle-Wrapper (`gradlew build`, Gradle 9.x – ältere Versionen unterstützen Java 25 nicht), JDK 25 (Eclipse Temurin). Wrapper: Gradle 9.8.2; Tests laufen über den eigenen `selfTest` (`TestRunner`, ohne JUnit).
- Jede neue Logik mit Test. Protokoll-Kodierung gegen bekannte Bytefolgen testen, Sicherheitsverhalten im Simulator (Fehlerkatalog im Lastenheft, Abschnitt 7).
- Kein Merge, wenn Tests fehlschlagen.
- Fremdbibliotheken sparsam und nur mit Begründung im Entscheidungsprotokoll.
- Jede neue Fremdbibliothek in docs/LIZENZEN.md eintragen; nur Lizenzen, die einen kommerziellen Vertrieb als geschlossene Software erlauben (z. B. Apache 2.0, MIT, BSD, EPL); GPL/AGPL nur nach Rückfrage.

## Erscheinungsbild

Logo: `docs/bilder/railcore-logo.png`. Programmsymbol: `docs/bilder/railcore-icon.png` (1024 px), `docs/bilder/railcore.ico` (Windows/Installer), Fenstersymbole `src/main/resources/icons/railcore-<Größe>.png` (16–256 px). Hausfarben: Nachtblau `#08121C` (Hintergrund), Signalrot `#EB1C24` (Akzent), Weiß (Schrift). Rot in der Oberfläche sparsam – es ist auch die Farbe für Nothalt und Störungen.

## Sparsam arbeiten

- Eine abgegrenzte Aufgabe pro Sitzung; nur die Dateien lesen, die die Aufgabe betrifft.
- Bilder, Build-Ausgaben, Protokolle, Datenbanken und Archive nicht lesen (gesperrt in `.claude/settings.json`).
- Builds und Tests leise ausführen (`gradlew build -q`), vollständige Ausgaben nur bei Fehlern.
- **Erinnerung an den Nutzer:** Bevor der MobaDB-Import (Excel) beginnt, den Nutzer darauf hinweisen, dass `Read(./**/*.xlsx)` in `.claude/settings.json` gesperrt ist und für den Import vorübergehend entfernt (und danach wieder eingetragen) werden muss.
- Lastenheft nur kapitelweise aus docs/lastenheft/ lesen.

## Modellwahl

- Standardmodell ist Sonnet (`.claude/settings.json`).
- Sicherheitsrelevante Kernlogik (safety/, train/, z21/, Reservierungen, Fahrstraßen, Positionsberechnung, Bremskurven, Befehlsplaner, Watchdog, Wiederanlauf, Z21-Prüfprogramm) an den Agenten **railcore-kern** (Opus) übergeben.
- Routine (Tests, Doku, Umbenennungen, Build-Konfiguration) an **railcore-routine** (Sonnet) übergeben.
- Ist eine ganze Sitzung überwiegend Kernlogik, dem Nutzer zu Beginn empfehlen, die Sitzung auf Opus umzustellen.
- Für Kernaufgaben mit Hardwarezugriff die Hauptsitzung auf Opus umstellen statt railcore-kern zu verwenden. Agenten nur für Aufgaben, bei denen das Ergebnis zählt (Tests, Doku, Aufräumen, reine Simulator-Logik).

## Entscheidungen

- **Grundsätzliche Entscheidungen trifft immer der Nutzer:** Architektur, Sicherheit, Hardwarezugriff, neue Bibliotheken, Datenformate und jede Abweichung von CLAUDE.md oder Lastenheft. Vorher 2–3 Optionen mit Empfehlung vorlegen und erst nach Zustimmung umsetzen.
- Umsetzungsdetails innerhalb der Regeln entscheidet Claude Code selbst und begründet sie in der Commit-Nachricht.
- Im Zweifel fragen.
- In `docs/ENTSCHEIDUNGEN.md` nur Einträge mit Freigabe des Nutzers (Spalte „Freigegeben“); Einträge ohne belegte Freigabe stehen auf „offen – bitte bestätigen“.

## Commits

- Kleine, in sich geschlossene Commits mit deutscher Nachricht (erste Zeile = was und warum).
- Neue Arbeit auf einem eigenen Branch, nicht direkt auf `main`.
- Zu Beginn jeder Sitzung git fetch ausführen; gibt es auf origin/main neue Commits, den Nutzer informieren und nach Bestätigung übernehmen.
- Pull Requests mit `gh pr merge` nur nach grünem Build (`gradlew build -q`) und ausdrücklicher Zustimmung des Nutzers mergen.

## Stand

Meilenstein 1 (Kern und Sicherheitsbasis) in Arbeit. Z21-Prüfprogramm ist fertig und an der echten Z21 passiv und aktiv gelaufen (`docs/Z21-PRUEFPROGRAMM.md`, Entscheidung 20); offen sind nur R-Bus-Zählung, Antwortzeit des Echos und die WLAN-Maus am RailCore-LAN-Server. Steuerungs-Executor und Befehlsplaner sind gebaut und über `ControlRuntime` an Z21 und Simulator angeschlossen (Entscheidungen 23–34). Plan bis Meilenstein 2 (Entscheidung 35): A Störungen im Simulator ohne Gleisgraph → B Gleisgraph (JSON vorläufig, synthetische Testanlagen) → C Positionsverfolgung auf dem Graph → D Störungen, die Graph und Position brauchen; je Schritt ein PR.
