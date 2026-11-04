# RailCore – Arbeitsanweisung für Claude

RailCore: eigene Modellbahn-Steuerung (Java 25, Desktop) für eine Roco Z21 mit Z21 Detector 10808 (CAN, RailCom). Ziel: bis zu 100 Züge vollautomatisch, wegbasiert und sicher, mit Handbetrieb unter Zugbeeinflussung.

Maßgebliche Unterlagen (Details: `docs/dokumentation/ARBEITSWEISE.md`):
- `docs/lastenheft/` – maßgebliche Fassung, kapitelweise (README.md); danach im claude.ai-Dokument nachziehen (Entscheidung 25).
- `docs/ENTSCHEIDUNGEN.md` – Entscheidungsprotokoll, vor Architekturänderungen lesen, neue Einträge oben.
- `docs/FEATURES.md` – Anforderungen.
- `docs/anleitung/`, `docs/wiki/` – Anleitung/Nachschlagewissen (Regeln RICHTLINIEN.md, Entscheidung 31); sichtbare Änderungen im selben PR nachziehen, nie Funktionen vor dem Bau als vorhanden beschreiben.
- `docs/dokumentation/ERSCHEINUNGSBILD.md` – Logo, Symbole, Hausfarben.

Sprache: Oberfläche, Kommentare, Doku, Commit-Nachrichten, Protokolle Deutsch; Code-Bezeichner Englisch.

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

- **Steuerungs-Executor** (ein Thread) ändert den Anlagenzustand; andere Threads legen nur Ereignisse/Befehle in seine Warteschlange.
- **Kern ohne Oberfläche:** `core`, `z21`, `layout`, `train`, `safety`, `diag` kein Swing; Swing nur im Event-Dispatch-Thread, Oberfläche liest Momentaufnahmen, sendet Befehle.
- **Alle Befehle über den CommandScheduler**: Priorität Nothalt > Sicherheit > Automatik > Fahrplan > Hand > Komfort, DCC-Budget, unveränderte Fahrstufen nie senden.
- **Zeit nur über `TimeSource`** (monoton); nie `System.currentTimeMillis`/`Instant.now` für Rechnungen; Tests: `TimeSource.Manual`.
- **Hardware nur über `CommandStation`**, zentralenspezifisch im Adapter; Fähigkeiten erkennen, nie voraussetzen.
- **Fahrbefehl = Fahrstufe + Richtung in einem Aufruf**, keine getrennten Setter. **Software Motion Control ist Standard** (CV3/CV4 = 0); Decoder Motion Control nur als Ausnahme je Lok (Entscheidung 8).
- **Stellvertreter-Adressen 9000–9999** nur für Handregler an der Z21, echte Decoder dort sind ein Prüffehler (Entscheidung 7). Die WLAN-Maus verbindet sich mit RailCore (Z21-kompatibler LAN-Server); ihre Befehle gehen nur nach Prüfung an die Zentrale (Entscheidung 21).
- **SQLite maßgeblich**, JSON nur Austausch/Sicherung; keine festen Kapazitätsgrenzen, alle konfigurierbar und getestet.

## Arbeiten mit echter Hardware

- **Niemals ohne ausdrückliche Bestätigung des Nutzers** Gleisspannung einschalten, Loks fahren, Weichen schalten oder CVs schreiben – auch nicht in Tests oder im Prüfprogramm.
- Neue Funktionen zuerst im Simulator, dann Hardware-in-the-Loop, erst danach echter Betrieb.
- Aufgaben mit Hardwarezugriff nie an Agenten abgeben, in der Hauptsitzung erledigen (Entscheidung 22).
- Vor jeder Hardware-Arbeit die Liste „geklärt/offen“ in `docs/Z21-PRUEFPROGRAMM.md` lesen; „an Hardware zu prüfen“ markierte Punkte nicht als gesichert behandeln.
- PowerShell startet den Wrapper nur als `.\gradlew`; Befehle für den Nutzer immer so angeben.

## Code und Tests

- Paket `at.schmoegl.railcore`; Gradle-Wrapper 9.8.2, JDK 25 Temurin; Tests über `selfTest` (`TestRunner`, ohne JUnit).
- Jede neue Logik mit Test: Protokoll gegen bekannte Bytefolgen, Sicherheit im Simulator (Fehlerkatalog Lastenheft 7). Kein Merge, wenn Tests fehlschlagen.
- Fremdbibliotheken sparsam, begründet im Entscheidungsprotokoll; neu in docs/LIZENZEN.md, nur Lizenzen für geschlossenen kommerziellen Vertrieb (z. B. Apache 2.0, MIT, BSD, EPL); GPL/AGPL nur nach Rückfrage.

## Sparsam arbeiten

- Eine abgegrenzte Aufgabe je Sitzung, nur betroffene Dateien lesen, Lastenheft kapitelweise.
- Bilder, Build-Ausgaben, Protokolle, Datenbanken, Archive nicht lesen (gesperrt in `.claude/settings.json`).
- Builds/Tests leise (`gradlew build -q`), volle Ausgabe nur bei Fehlern.
- Vor dem MobaDB-Import den Nutzer erinnern: Lesesperre `*.xlsx` in `.claude/settings.json` vorübergehend entfernen, danach wieder eintragen.

## Modellwahl

- Standardmodell Sonnet (`.claude/settings.json`).
- Sicherheitsrelevante Kernlogik (safety/, train/, z21/, control/, Reservierungen, Fahrstraßen, Positionsberechnung, Bremskurven, Befehlsplaner, Watchdog, Wiederanlauf, Z21-Prüfprogramm) an **railcore-kern** (Opus); Routine (Tests, Doku, Umbenennungen, Build-Konfiguration) an **railcore-routine** (Sonnet).
- Überwiegt Kernlogik eine Sitzung: Umstellung auf Opus empfehlen. Kernaufgaben mit Hardwarezugriff: Hauptsitzung auf Opus statt railcore-kern; Agenten nur, wo das Ergebnis zählt (Tests, Doku, Aufräumen, Simulator).

## Entscheidungen

- **Grundsätzliche Entscheidungen trifft der Nutzer:** Architektur, Sicherheit, Hardwarezugriff, neue Bibliotheken, Datenformate, jede Abweichung von CLAUDE.md/Lastenheft. Vorher 2–3 Optionen mit Empfehlung, erst nach Zustimmung umsetzen.
- Umsetzungsdetails entscheidet Claude Code selbst, begründet in der Commit-Nachricht. Im Zweifel fragen.
- In `docs/ENTSCHEIDUNGEN.md` nur Einträge mit Freigabe des Nutzers; unbelegte stehen auf „offen – bitte bestätigen“.

## Commits

- Kleine Commits, deutsche Nachricht (erste Zeile = was/warum); neue Arbeit auf eigenem Branch, nicht auf `main`.
- Sitzungsbeginn: `git fetch`; neue Commits auf origin/main dem Nutzer melden, nach Bestätigung übernehmen.
- `gh pr merge` nur nach grünem Build (`gradlew build -q`), grüner GitHub Action, Zustimmung des Nutzers.

## Stand

`docs/dokumentation/ARBEITSWEISE.md`, Abschnitt „Stand“.
