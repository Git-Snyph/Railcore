# Architektur

> Status: Entwurf

Kurzüberblick für Entwickler. Maßgeblich sind Lastenheft Kapitel 2 (`docs/lastenheft/02-architektur.md`), `CLAUDE.md` und das Entscheidungsprotokoll.

## Grundregeln

- **Ein Steuerungs-Executor** (`control.ControlExecutor`) ändert den Anlagenzustand. Andere Threads (UDP, Watchdog, Datenbank, Oberfläche) legen nur Ereignisse oder Befehle in seine Warteschlange. Ereignisse werden sofort verarbeitet; der Takt (Standard 50 ms) dient nur zeitabhängigen Aufgaben; Brems- und Haltebefehle werden auf den vorausberechneten Zeitpunkt eingeplant.
- **Kern ohne Oberfläche:** `core`, `z21`, `layout`, `train`, `safety`, `diag` importieren kein Swing. Die Oberfläche liest Momentaufnahmen (`PlantSnapshot`) und sendet Befehle.
- **Hardware nur über `CommandStation`.** Zentralen-spezifischer Code bleibt im Adapter (`z21`, Simulator in `sim`). Fähigkeiten werden erkannt, nie vorausgesetzt.
- **Zeit nur über `util.TimeSource`** (monoton); in Tests `TimeSource.Manual`.
- **Fahrbefehl = Fahrstufe + Richtung** in einem Aufruf.
- **Alle Befehle über den Befehlsplaner** mit Vorrang und DCC-Budget; Nothalt geht sofort und an jeder Warteschlange vorbei.

## Pakete

| Paket | Inhalt |
| --- | --- |
| `core` | Schnittstelle `CommandStation`, Lokzustand, Richtung, Melderkontakte |
| `z21` | Z21-Adapter: Protokoll, Nachrichten; `z21.check` ist das Prüfprogramm |
| `control` | Steuerungs-Executor, Anlagenzustand und Momentaufnahme, Executor-Watchdog, Freigabe (`MovementAuthority`) |
| `train` | Fahrdynamik, Bremskurve, Positionsrechnung und -unsicherheit, Halteplanung |
| `layout` | Gleisplan-Modell: Blöcke, Ausgänge, Licht, Laden aus Datei |
| `safety` | Betriebszustand, Melderzustand (unbekannt/frei/belegt), Melderfilter, Kommunikations-Watchdog |
| `diag` | Journal |
| `sim` | Simulator: Welt, Loks mit abweichenden „wahren“ Profilen, Melder, simulierte Zentrale |
| `util` | `TimeSource`, eigene JSON-Verarbeitung |

## Geplant

- Ablaufdiagramm Ereignis → Executor → Befehlsplaner → Zentrale.
- Zustandsmodelle (Lastenheft Kapitel 3) mit Verweis auf die Klassen.
