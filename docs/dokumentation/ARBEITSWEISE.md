# Arbeitsweise – Erläuterungen zu CLAUDE.md

Dieses Dokument erläutert und ergänzt die Regeln aus `CLAUDE.md`, damit die Arbeitsanweisung selbst kurz bleibt. Es ändert keine Regel – im Zweifel gilt `CLAUDE.md`.

## Maßgebliche Unterlagen

- `docs/lastenheft/` ist in Kapitel-Dateien aufgeteilt (Inhaltsverzeichnis in `docs/lastenheft/README.md`), damit nur das für die jeweilige Aufgabe nötige Kapitel gelesen werden muss.
- Die lesbare Fassung mit Diagrammen ist das Dokument „RailCore – Technisches Lastenheft“ in claude.ai: https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM. Jede Änderung am Lastenheft zuerst in `docs/lastenheft/`, danach dort nachziehen (Entscheidung 25).
- `docs/ENTSCHEIDUNGEN.md` ist das Entscheidungsprotokoll: vor jeder Architekturänderung lesen, neue Entscheidungen oben eintragen.
- `docs/anleitung/` (Anwender-Anleitung) und `docs/wiki/` (Nachschlagewissen für Anwender und Entwickler) sind je Sprache in einem Ordner (`de`, später `en`) organisiert; Schreibregeln in `docs/dokumentation/RICHTLINIEN.md` (Entscheidung 31). Jede für Anwender sichtbare Änderung zieht Anleitung bzw. Wiki im selben Pull Request nach; eine Funktion wird dort nie als vorhanden beschrieben, bevor sie gebaut ist.

## Stellvertreter-Adressen und WLAN-Maus

Die Stellvertreter-Adressen 9000–9999 gelten für Handregler, die sich direkt mit der Z21 verbinden (Z21-App, X-Bus-Geräte, Zimo KLUG): Die Z21 führt den Fahrbefehl auf dieser Adresse aus, es bewegt sich aber nichts, weil dort kein Decoder sitzt. RailCore fragt diese Adressen regelmäßig ab und fährt daraus geprüft die echte Lok (Entscheidung 7).

Die WLAN-Maus verbindet sich dagegen nicht mit der Z21, sondern direkt mit RailCore über einen Z21-kompatiblen LAN-Server; ihre Befehle gehen erst nach Prüfung gegen Bremskurve und Reservierung an die echte Zentrale (Entscheidung 21). Das ist nötig, weil die Z21 keinen Weg bietet, Befehle eines Handreglers vor der Ausführung abzufangen.

## CommandStation und Fähigkeiten der Zentrale

Zentralen-spezifischer Code bleibt im jeweiligen Adapter (`z21`, später weitere). Fähigkeiten wie Firmware-Version, F29–F31, CAN-Melder oder Lok-Broadcast werden nie vorausgesetzt, sondern aus den Antworten der Zentrale erkannt (siehe `core.StationCapabilities`). Das Z21-Prüfprogramm (`docs/Z21-PRUEFPROGRAMM.md`) prüft das an der echten Hardware.

## Was zählt als sicherheitsrelevante Kernlogik (Modellwahl)

`safety/`, `train/`, `z21/`, `control/`, Reservierungen, Fahrstraßen, Positionsberechnung, Bremskurven, Befehlsplaner, Watchdog, Wiederanlauf, Z21-Prüfprogramm. Diese Themen gehen an den Agenten **railcore-kern** (Opus).

## MobaDB-Import (Excel)

Bevor der Import beginnt: den Nutzer darauf hinweisen, dass `Read(./**/*.xlsx)` in `.claude/settings.json` gesperrt ist und für den Import vorübergehend entfernt (danach wieder eingetragen) werden muss.

## Befehle unter Windows

PowerShell startet den Gradle-Wrapper nur als `.\gradlew`, nicht als `gradlew`; in der Eingabeaufforderung (cmd) genügt `gradlew`. Befehle für den Nutzer deshalb immer mit `.\gradlew` angeben.

## Stand

Meilenstein 1 (Kern und Sicherheitsbasis) in Arbeit. Z21-Prüfprogramm ist fertig und an der echten Z21 passiv und aktiv gelaufen (`docs/Z21-PRUEFPROGRAMM.md`, Entscheidung 20); offene Punkte dort in der Zusammenfassung. Steuerungs-Executor und Befehlsplaner sind gebaut und über `ControlRuntime` an Z21 und Simulator angeschlossen (Entscheidungen 23–34). Nächster Schritt: Simulator um Störungen aus dem Fehlerkatalog erweitern, danach Gleisgraph und Positionsverfolgung.
