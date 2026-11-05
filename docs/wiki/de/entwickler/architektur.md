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
| `train` | Fahrdynamik, Bremskurve, Positionsrechnung und -unsicherheit, Halteplanung; Zugposition und -ausdehnung auf dem Gleisgraph, Fahrauftrag aus einem Fahrweg (Schritt C1) |
| `layout` | Gleisplan-Modell: Blöcke, Ausgänge, Licht, Laden aus Datei |
| `layout.graph` | Gleisgraph (Schritte B1–B3): Gleisstückbibliothek mit Geometrie, daraus berechnete Knoten und Kanten in mm, Ort, Fahrweg, Weichenlagen als Eingabe; Auflagen (Meldeabschnitte, Blöcke, Punkte, Signale, Bezirke) und Gleisplan-Prüfung (`TrackLayout`); Gleisplan-Datei (JSON, vorläufig) und Lader (`TrackPlanLoader`) |
| `safety` | Betriebszustand, Melderzustand (unbekannt/frei/belegt), Melderfilter, Kommunikations-Watchdog |
| `diag` | Journal |
| `sim` | Simulator: Welt, Loks mit abweichenden „wahren“ Profilen, Melder, simulierte Zentrale |
| `util` | `TimeSource`, eigene JSON-Verarbeitung |

## Gleisgraph: `layout.graph`

Grundmodell nach Lastenheft Kapitel 4 und Entscheidung 40 (E2, E3, E10). Reines, unveränderliches Java-Modell ohne Zustand; Datei und Lader seit B3, Zugposition auf dem Graph seit C1 (siehe unten), noch ohne Anbindung an Steuerung oder Simulator (folgt in B4/C2/C3).

- **Gleisstückbibliothek** (`TrackPiece`, `TrackPieceCatalog`): gerade Stücke und Bögen (`TrackGeometry`: Länge bzw. Radius und Winkel, auch zusammengesetzt), Weiche, Kreuzung, Doppelkreuzungsweiche. Im Code stehen keine Herstellermaße. Bei Weichen, Doppelkreuzungsweichen und Kreuzungen liefert der Typ nur einen Vorschlag; maßgeblich sind die Werte je verlegtem Stück (Entscheidungen 41, 42). Gerade Stücke und Bögen dürfen beim Verlegen eine eigene Länge bekommen, die den Typwert überschreibt (vorläufig, Entscheidung 42).
- **Graph** (`TrackGraph.Builder`): verlegte Stücke werden über ihre Anschlüsse verbunden (Stoß, Trennstelle, Gleisende); Knoten und Kanten samt Längen berechnet der Builder. Offene Anschlüsse und andere Fehler werden gesammelt gemeldet (`TrackGraphException`).
- **Weiche** = Knoten an der Spitze mit zwei Zweigkanten eigener Länge und je Schenkel einem Grenzzeichen (Pflichtangabe, darf hinter dem Weichenende liegen); beides wird je verlegter Weiche eingetragen (`TurnoutBranches`), nicht aus Radius und Winkel berechnet. Die Stelllogik von Doppelkreuzungsweichen gilt bis zur Prüfung an der Hardware als vorläufig und erscheint als Hinweis (`TrackGraph.notices()`); Verbindungskanten ohne Länge sind vorläufig. Gegen die Spitze geht es nur bei passender Lage.
- **Ort** = (Kante, mm ab Kantenanfang); Kanten haben nur eine Bezugsrichtung, befahren werden sie gerichtet (`DirectedEdge`).
- **Fortsetzung** hängt allein von den übergebenen Weichenlagen ab (`TurnoutStates`); unbekannte Lage heißt kein Weg. Kehrschleife und Oval brauchen keinen Sonderfall.
- **Fahrweg** (`GraphPath`): Folge gerichteter Kanten; bildet die Wegkoordinate in mm auf einen Ort ab und zurück, lässt sich umkehren und nennt die nötigen Weichenlagen.
- **Doppelkreuzungsweiche:** Grenzzeichen je Außenanschluss (A1, A2, B1, B2), gemessen ab dem Anschluss nach außen, Pflichtangabe je verlegter DKW (`DoubleSlipClearances`; Bezugspunkt vorläufig).

### Auflagen und Prüfung: `TrackLayout` (Schritt B2)

`TrackLayout.builder(graph)` legt auf den Graph: Meldeabschnitte (`DetectionSection`: Kontakt, Bereiche auch über Weichen, Ansprech-/Freimeldeverzögerung, Bezirk), Blöcke (`Block`: Abschnitte von Ende A nach B, Richtungen, Zugarten, Zuglängen), Haltebereiche (`HoldingArea`, Entscheidung 40/E6), Haltepunkte, Geschwindigkeitswechselpunkte, Signale am Blockende (`BlockSignal`), Booster-Bezirke (`BoosterDistrict`, mit Bereichen für Gleis ohne Melder, E7; Quelle der Bezirksmeldung an Hardware zu prüfen) und Weichenadressen. Meldepunkte werden aus den Abschnittsgrenzen je Fahrtrichtung abgeleitet und lassen sich überschreiben (`ReportPointOverride`).

- **Richtung:** Kanten haben keine globale Ausrichtung. Ein Block legt sein Ende A als Ort mit Richtung in den Block fest (`DirectedLocation`); daraus berechnet `TrackLayout` Länge, Enden und Ausrichtung je Kante (`BlockGeometry`). Punkte tragen ihre Richtung bezogen auf die Kante.
- **Prüfung** (`findings()`, `LayoutFinding` mit Art und Schweregrad): Der Bau wirft bei inhaltlichen Fehlern keine Ausnahme, damit der ganze Bericht angezeigt werden kann; jeder Fehler sperrt die Automatik (`permitsAutomatic()`), Hinweise werden nur angezeigt. Fehler: Block ohne Melder, überlappende Abschnitte oder Bezirke, doppelte Kontakte, Adressen oder Kennungen, unbekannte Verweise, ungültige Lagen, Kehrschleife ohne Kehrschleifenbezirk, Abschnitt in zwei Blöcken, Blockgeometrie, Haltebereich nicht zusammenhängend. Hinweise: weniger als 3 Abschnitte je Block (einstellbar), unbekannte Länge, Gleis ohne Melder, Gleis ohne Bezirk, Weiche ohne Belegtabschnitt, Sackgasse ohne Ausweg, unerreichbarer Knoten, Block ohne Ausfahrt, DKW mit ungeprüfter Stelllogik.
- **Kehrschleife:** Polaritätsprüfung über alle Übergänge des Graphs (erkennt auch Gleisdreiecke); aufgelöst nur durch Gleis, das ganz in einem Kehrschleifenbezirk liegt. Trennstellen zählen nicht als Auflösung.
- **Indizes** enthalten nur gültige Angaben (bei doppelten Kennungen gilt die erste): Abschnitt → Block, Bezirk → Abschnitte, Kontakt → Abschnitt, Ort → Abschnitte/Blöcke/Bezirke, Bereich → überschneidende Abschnitte/Blöcke/Bezirke (Berühren zählt nicht), Signal → Lage.

## Zugposition auf dem Gleisgraph: `train` (Schritt C1)

> Status: Entwurf – reines Modell, noch nicht an Steuerungs-Executor, Simulator oder `TrainDriver` angeschlossen (folgt in C2/C3).

- **`TrainPosition`**: Zugspitze als Ort mit Fahrtrichtung (`DirectedLocation`), Decoder-Richtung zur Spitze hin (Ausrichtung der Lok), Unsicherheit des letzten Abgleichs und Strecke seither; σ wie im `PositionEstimator` mit den `PositionUncertainty`-Parametern des Zuges. Nach Neustart „unbestätigt“ (Grundsatz 5). Vorrücken auf dem Graph nur über bekannte, passende Weichenlagen; Richtungswechsel macht den Zugschluss zur Spitze. Hin- und Rückrechnung zwischen Wegkoordinate eines `GraphPath` und Ort.
- **`TrainExtent`** (berechnet mit `TrainExtent.Resolver`, einmal je Gleisplan): von der Spitze über die Zuglänge zurück bis zum Zugschluss; belegte Bereiche, Meldeabschnitte, Blöcke (auch mehrere, Entscheidung 40/E6), Weichen (Zweige und Bereich bis zum Grenzzeichen, DKW mit beiden Antrieben), Kreuzungen und Bezirke, jeweils von der Spitze zum Schluss. Liegt hinter dem Zug eine Weichenspitze mit unbekannter Lage, gelten alle Zweige als belegt („mehrdeutig“); liegt eine Weiche unter dem Zug bekanntermaßen falsch, ist das ein Widerspruch; passt der Zug nicht hinter die Spitze (Prellbock), ist die Ausdehnung unvollständig. Optional Zuschläge vorn und hinten, z. B. k · σ der Position.
- **`DriveOrderFactory`**: baut aus Position und Fahrweg (`GraphPath` ab der Zugspitze) einen unveränderten `DriveOrder` (Wegkoordinate 0 = Spitze). Meldepunkte aus den Abschnittsgrenzen je Fahrtrichtung (`TrackLayout.reportPoints`) plus Abstand der ersten stromabnehmenden Achse (vorläufig 20 mm, bis MobaDB-Daten vorliegen), Melderverzögerung = Ansprechverzögerung des Abschnitts mit vorläufiger Streuung 10 ms. Weggelassen werden Meldepunkte hinter dem Freigabeende und solche, deren Abschnitt dann noch vom eigenen Zug belegt ist. Ziel: Haltepunkt (Bahnsteig), Signal oder Freigabeende (Strecke). Das Freigabeende liegt vor dem ersten Gleisstück einer Weiche, deren verlangte Lage nicht anliegt (auch unbekannt); ein Ziel dahinter wird durch einen Halt auf der Strecke davor ersetzt. Abgewiesen werden Gleisplan mit Fehlern, unbestätigte Position und nicht eindeutige Ausdehnung.
- Der blockbasierte `train.TrackPath` ist entfallen (wurde nirgends verwendet, Entscheidung 35).

### Gleisplan-Datei und Lader (Schritt B3)

> Status: Entwurf – Format vorläufig (Entscheidung 35), Version 2 (Version 1 wird weiter gelesen und beim Lesen auf Version 2 gehoben); bis Meilenstein 3 maßgeblich, danach Austauschformat neben SQLite.

- **Modell** `TrackPlan`: genau der Inhalt der Datei (Katalog, verlegte Stücke mit Instanzwerten, Verbindungen, Auflagen). Graph und Gleisplan sind daraus berechnet und lassen sich nicht verlustfrei zurückschreiben, das Modell schon (`TrackPlanJson.write`, `parse(write(p)).equals(p)`).
- **Aufbau** (Schlüssel englisch, Werte von Aufzählungen wie im Code, z. B. `STRAIGHT`, `A_TO_B`): `formatVersion` (Pflicht), `name`, `recommendedSectionsPerBlock`, `catalog` (Typen: `plain` mit `geometry` `straight`/`arc`/`compound`, `turnout`, `crossing`, `doubleSlip` mit Stelllogik und `hardwareVerified`), `pieces` (Kennung, `type`, Instanzwerte je Art: `lengthMm` bei Gerade/Bogen, `turnout` + `branches` bei der Weiche, `dimensions` bei der Kreuzung, `turnoutA`/`turnoutB` + `routeLengthsMm` + `clearancesMm` bei der DKW; bei jedem Stück `lengthUnknown`, Entscheidung 43 Punkt 2), `connections` (`joint`, `isolating`, `end` mit Anschlüssen als `"stück.ANSCHLUSS"`), `sections`, `blocks`, `holdingAreas`, `districts` (mit `reversingLoop`), `signals`, `points` (`stop`, `speed`, `report` = überschriebener Meldepunkt), `turnoutAddresses`. Orte als `{"edge", "offsetMm", "forward"}`; Bereiche als `{"edge", "fromMm", "toMm"}` oder kurz als Kantenkennung für die ganze Kante. Kanten heißen wie im `TrackGraph.Builder` (z. B. `w.STRAIGHT`, `d.A1_B2`).
- **Optionale Angaben ab Version 2** (Entscheidung 46, Option A), reine Datenhaltung für die spätere Gleiswahl (Meilenstein 4), ohne Einfluss auf Graph und Prüfung: auf Anlagenebene `gauge` (Spurweite) und `catenary` (Oberleitung `PRESENT`/`ABSENT`) als Standardwerte, je Stück `gauges` (Liste, überschreibt die Spurweite der Anlage, mehrere bei Mehrschienengleis) und `catenary` (überschreibt die der Anlage); `TrackPlan.gaugesOf` und `catenaryOf` liefern die wirksamen Werte. Spurweite als Bezeichnung nach NEM 010, Ausgabe 2011 (`NemGauge`, z. B. `"H0"`, `"H0m"`, `"0e"`, `"IIm"`, mit mm-Wert und Maßstab für spätere Geometrie- und Entgleisungsprüfungen) oder als `{"gauge": "CUSTOM", "customGaugeMm": 10.5}` für Spurweiten außerhalb NEM 010 (z. B. H0n3 nach NMRA, Gartenbahn-Bezeichnung „G“ entspricht IIm). Je Block `noReversing` und `noStopping` (Listen von Richtungen) und `platformSide` (`LEFT`/`RIGHT`/`BOTH`/`NONE`, gesehen in Richtung A → B), je Haltepunkt `platformSide` (gesehen in seiner Fahrtrichtung). Fehlt eine Angabe am Stück, gilt der Anlagenwert; fehlt auch dieser, gilt „keine Angabe“ (`UNSPECIFIED` bzw. leer). Standardwerte werden nicht geschrieben und dürfen nicht ausdrücklich als `UNSPECIFIED` in der Datei stehen.
- **Streng:** unbekannte Felder, doppelte Schlüssel, falsche Typen, nicht ganze Zahlen, wo ganze verlangt sind, unbekannte Aufzählungswerte und eine nicht unterstützte Formatversion sind Fehler, damit ein Tippfehler nie stillschweigend einen Standardwert ergibt. Erlaubte Richtungen eines Blocks sind Pflicht. In einer Datei der Version 1 sind die Felder ab Version 2 unbekannt; unbekannte Spurbezeichnungen (genaue Schreibweise nach NEM 010, z. B. `H0` mit Null), `CUSTOM` ohne oder eine NEM-Spur mit eigenem mm-Wert, eine leere oder doppelte Spurweitenliste und eine eigene Spurweite ≤ 0 sind Fehler.
- **Lader** `TrackPlanLoader`: drei Stufen – Datei lesen und prüfen (Syntax, Format, Verweise auf Katalog, Stücke, Anschlüsse), Graph bauen, Gleisplan bauen und prüfen. Ergebnis ist immer ein gemeinsamer Bericht `TrackPlanReport` mit Ladefehlern (`Phase` FILE, SYNTAX, FORMAT, REFERENCE, GRAPH, mit Fundstelle wie `pieces[3] (w1).branches`) und den Prüfbefunden; Automatik nur ohne Ladefehler und ohne Prüf-Fehler. Verweise auf Kanten, Abschnitte und Blöcke prüft `TrackLayout`, weil Kanten erst mit dem Graph entstehen.
- **Synthetische Testanlagen** unter `src/test/resources/anlagen/`: Oval, Bahnhof mit Weichen und DKW, Schattenbahnhof mit Weichenharfe, Kehrschleife mit und ohne Kehrschleifenbezirk.
- Noch nicht enthalten: Lage in der Ebene und Schließprüfung der Geometrie, Fahrstraßen, Anbindung an Steuerung und Simulator (B4).

## Geplant

- Ausführliches Ablaufdiagramm Ereignis → Executor → Befehlsplaner → Zentrale.
- Zustandsmodelle (Lastenheft Kapitel 3) mit Verweis auf die Klassen.
