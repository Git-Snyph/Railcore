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

## Verdrahtung: `control.ControlRuntime`

`ControlRuntime` verbindet eine beliebige Zentrale (`CommandStation`: Z21-Adapter oder Simulator) mit Steuerungs-Executor und Befehlsplaner und ist danach der einzige Weg zu ihr.

```text
Oberfläche / API ──Befehl──► Warteschlange ─► ControlExecutor ─► Schranke ─► CommandScheduler ─► Zentrale
Oberfläche / API ──Nothalt──────────────────────────────────────────────────────────────────────► Zentrale
Zentrale (Empfangs-Thread) ──Ereignis──► Warteschlange ─► ControlExecutor ─► PlantState ─► PlantSnapshot
```

- **Befehle** (Fahren, Funktionen, Weichen, Abfragen) nur auf dem Steuerungs-Thread über `ControlExecutor.commands()`.
- **Nothalt** (`ControlRuntime.emergencyStop`) aus jedem Thread, sofort an die Zentrale.
- **Ereignisse** der Zentrale (Lebenszeichen, Verbindung, Gleisspannung, Kurzschluss, Melder, RailCom, Lokmeldungen, Weichenmeldungen, Fähigkeiten) legt der Empfangs-Thread nur in die Warteschlange; allein der Executor ändert `PlantState`.
- **Verbinden:** „verbunden“ erst, wenn die Zentrale antwortet. Melder unbekannt, Züge unbestätigt; jede bekannte Lok (auch ein angemeldeter, noch nie angesprochener Zug) erhält einmal Halt, nie eine Fahrstufe (Entscheidung 28). Beim ersten Verbinden nach dem Programmstart zusätzlich Nothalt für die ganze Anlage; er muss quittiert werden und führt danach in den Wiederanlauf (Entscheidung 33). Die Anlage geht dabei direkt von „Verbinde“ nach „Nothalt“ mit dem Grund „Programmstart“ (Entscheidung 34; dieser Übergang gilt nur für diesen Nothalt, jeder andere Nothalt beim Verbinden führt nach „Störung“).
- **Verbindungsverlust** (Kommunikations-Watchdog, 5 s): Nothalt, Störung, Melder unbekannt, Meldungen der Zentrale (Lokzustände, RailCom, Weichenlagen, Fähigkeiten) verworfen, Handregler unbekannt. Nach der Wiederverbindung erneut Nothalt und Halt an alle bekannten Loks; Freigabe nur durch den Bediener.
- **Fähigkeiten** (`core.StationCapabilities`) erkennt der Adapter; an der Z21 aus der Firmware: CAN-Melder und RailCom ab 1.30, F29–F31 ab 1.42. Ohne Erkennung wird nichts genutzt (z. B. F29–F31 abgewiesen, keine CAN-Melder abonniert). R-Bus wird nicht genutzt.
- **Handregler** auf Stellvertreter-Adressen 9000–9999 (`HandControllerConfig`, Standard alle 250 ms) fragt der Executor als zeitabhängige Aufgabe ab (`HandControllerPoller`). Die Abfrage läuft über den Befehlsplaner in der Klasse Hand mit Kosten 0 (kein DCC-Paket); ohne Antwort gilt der Wert nach 750 ms als veraltet.
- **Trennen und Beenden** senden vorher Nothalt (keine Gleisspannung aus), solange die Zentrale verbunden ist (Entscheidung 33). Die folgende Trennmeldung löst keinen zweiten Nothalt an die schon getrennte Zentrale aus (Journal: INFO `EMERGENCY_STOP_BEFORE_DISCONNECT`); bei einer unerwarteten Trennung versucht die Steuerung den Nothalt weiterhin und meldet ein Scheitern.

Der Z21-Adapter spricht die Z21 über `z21.Z21Link` (UDP); in Tests ersetzt eine simulierte Z21 die Verbindung, der Adapter läuft dann ohne eigenen Thread mit `TimeSource.Manual`.

## Pakete

| Paket | Inhalt |
| --- | --- |
| `core` | Schnittstelle `CommandStation`, Fähigkeiten (`StationCapabilities`), Lokzustand, Richtung, Melderkontakte |
| `z21` | Z21-Adapter: Treiber, Paketverbindung (`Z21Link`), Protokoll, Nachrichten; `z21.check` ist das Prüfprogramm |
| `control` | Verdrahtung (`ControlRuntime`), Steuerungs-Executor, Handregler-Abfrage, Anlagenzustand und Momentaufnahme, Executor-Watchdog, Freigabe (`MovementAuthority`), Befehlsplaner (`CommandScheduler`, siehe [Befehlsplaner](befehlsplaner.md)), Zugsteuerung (`TrainDriver`) |
| `train` | Fahrdynamik, Bremskurve, Positionsrechnung und -unsicherheit, Halteplanung |
| `layout` | Gleisplan-Modell: Blöcke, Ausgänge, Licht, Laden aus Datei |
| `layout.graph` | Gleisgraph (Grundmodell, Schritt B1): Gleisstückbibliothek mit Geometrie, daraus berechnete Knoten und Kanten in mm, Ort, Fahrweg, Weichenlagen als Eingabe |
| `safety` | Betriebszustand, Melderzustand (unbekannt/frei/belegt), Melderfilter, Kommunikations-Watchdog |
| `diag` | Journal |
| `sim` | Simulator: Welt, Loks mit abweichenden „wahren“ Profilen, Melder, simulierte Zentrale |
| `util` | `TimeSource`, eigene JSON-Verarbeitung |

## Gleisgraph: `layout.graph`

Grundmodell nach Lastenheft Kapitel 4 und Entscheidung 40 (E2, E3, E10). Reines, unveränderliches Java-Modell ohne Zustand; noch ohne Datei, Lader und Anbindung an Steuerung oder Positionsrechnung (folgt in B2–B4 und C).

- **Gleisstückbibliothek** (`TrackPiece`, `TrackPieceCatalog`): gerade Stücke und Bögen (`TrackGeometry`: Länge bzw. Radius und Winkel, auch zusammengesetzt), Weiche, Kreuzung, Doppelkreuzungsweiche. Maße kommen aus dem Katalog, im Code stehen keine Herstellermaße.
- **Graph** (`TrackGraph.Builder`): verlegte Stücke werden über ihre Anschlüsse verbunden (Stoß, Trennstelle, Gleisende); Knoten und Kanten samt Längen berechnet der Builder. Offene Anschlüsse und andere Fehler werden gesammelt gemeldet (`TrackGraphException`).
- **Weiche** = Knoten an der Spitze mit zwei Zweigkanten eigener Länge (`TurnoutBranches`) und je Schenkel einem Grenzzeichen (Pflichtangabe, darf hinter dem Weichenende liegen). Gegen die Spitze geht es nur bei passender Lage.
- **Ort** = (Kante, mm ab Kantenanfang); Kanten haben nur eine Bezugsrichtung, befahren werden sie gerichtet (`DirectedEdge`).
- **Fortsetzung** hängt allein von den übergebenen Weichenlagen ab (`TurnoutStates`); unbekannte Lage heißt kein Weg. Kehrschleife und Oval brauchen keinen Sonderfall.
- **Fahrweg** (`GraphPath`): Folge gerichteter Kanten; bildet die Wegkoordinate in mm auf einen Ort ab und zurück, lässt sich umkehren und nennt die nötigen Weichenlagen.

## Geplant

- Ausführliches Ablaufdiagramm Ereignis → Executor → Befehlsplaner → Zentrale.
- Zustandsmodelle (Lastenheft Kapitel 3) mit Verweis auf die Klassen.
