# Simulator

> Status: Entwurf

Der Simulator (Paket `sim`) ist ein vollwertiger Adapter hinter `CommandStation`. Maßgeblich: Lastenheft Kapitel 10.

## Bausteine

| Klasse | Aufgabe |
| --- | --- |
| `SimWorld` | Simulierte Anlage und Zeit |
| `SimLoco` | Lok mit eigenem, absichtlich vom eingemessenen Profil abweichendem „wahren“ Profil – die Positionsrechnung muss also auch im Test korrigieren |
| `SimDetector` | Melder mit Verzögerung und Streuung |
| `SimCommandStation` | Simulierte Zentrale, mit Störungsauslösern |
| `SimFaultScript` | Störungsskript: Störungen zeilenweise mit Zeitpunkt |
| `Distribution` | Zufallsverteilungen für Streuung |

## Störungen auslösen

Schritt A aus Entscheidung 35: Störungen, die keinen Gleisgraph brauchen. Alle laufen über die Simulationsuhr (`TimeSource.Manual`) und sind damit deterministisch. Ab Werk ist jede Störung aus; ohne Störung zieht der Simulator keine zusätzlichen Zufallswerte, bestehende Läufe bleiben gleich.

| Störung | Aufruf an `SimCommandStation` bzw. `SimWorld` | Skriptbefehl |
| --- | --- | --- |
| Verbindung verspätet: keine Meldung, auch kein Lebenszeichen; danach alles Zurückgehaltene auf einmal | `delayReplies(ms)` | `verspaetung <ms>` |
| Verbindung verloren: beide Richtungen tot, nichts wird nachgeliefert | `loseLink(ms)` | `verbindung-weg <ms>` |
| Paketverlust in beide Richtungen, auch Nothalt (Anteil 0–1, eigener Seed) | `setPacketLoss(anteil, seed)` | `paketverlust <anteil> [seed]` |
| Kurzschluss, Überlast, Aus, Ein je Booster-Bezirk; Loks im Bezirk halten, Melder dort verlieren die Versorgung | `assignDistrict(…)`, `districtPower(bezirk, zustand)` | `bezirk <nr> kurzschluss\|ueberlast\|aus\|ein` |
| Gleisspannung der Zentrale (aus/Kurzschluss: alle Melder ohne Versorgung) | `reportTrackPower(zustand)` | `gleisspannung ein\|aus\|kurzschluss` |
| Flackernder Melder (endet im Ausgangszustand bei gerader Zahl) | `flicker(melder, wechsel, abstandMs)` | `flackern <melder> <wechsel> <ms>` |
| Ausfall der 10808-Versorgung, einzelner Melder oder ganzer CAN-Bereich; bei Rückkehr Einschaltmeldung | `cutDetectorSupply(…)`, `restoreDetectorSupply(…)` | `versorgung-aus <melder>[,<melder>…]\|modul:<m>`, `versorgung-ein …` |
| Lok fährt anders als gesendet (Fremdsteuerung einer echten Adresse) | `foreignDrive(adresse, stufe, richtung)` | `fremdsteuerung <adresse> <stufe> vor\|rueck` |
| Hänger der Steuerung (Takt überschritten): Welt läuft weiter, nur der Watchdog prüft | `SimWorld.stallController(ms)` | `haenger <ms>` |
| Interner Fehler (Ausnahme in der Steuerung) | `SimWorld.injectInternalError(text)` | `interner-fehler [text]` |

Melder schreibt man als Nummer oder `Modul:Eingang` (`2:3`). Zeit im Skript in ms ab `schedule(world, station)`; `#` leitet einen Kommentar ein. Fehler im Skript werden beim Einlesen mit Zeilennummer abgewiesen.

```
0     paketverlust    0.1  7
1000  verspaetung     3500
2000  bezirk          1 kurzschluss
2500  bezirk          1 ein
3000  versorgung-aus  modul:2
8000  versorgung-ein  modul:2
9000  fremdsteuerung  3 90 vor
```

Weitere Schalter der simulierten Zentrale (ab Werk aus): `setCapabilities` meldet Fähigkeiten beim Verbinden (z. B. die Melderabfrage), `setAnswerQueries` beantwortet Lokabfragen aus ihrem Speicher, `setLocoBroadcast` meldet Fremdsteuerung sofort (die Z21 tut das nicht). Die Melderabfrage beantwortet sie für alle versorgten Melder, auch unveränderte; `defineContacts` legt dafür die Melder an.

Für Hänger und internen Fehler muss der `SimWorld.Controller` des Tests `watchdogCheck()` (Prüfung des Executor-Watchdogs) und `injectInternalError()` umsetzen; ein Beispiel steht in `FaultCatalogSimTest`.

## Reaktionen und Abnahme

`FaultCatalogSimTest` prüft je Störung die Reaktion aus Lastenheft Kapitel 7 (Abnahme „jede Störung führt zur festgelegten Reaktion“, Lastenheft Kapitel 10). Die Reaktionen selbst stehen in der Steuerung (`ControlExecutor`, `DetectorSupervision`, `CommandRules`); eine Übersicht für Anwender steht unter [Fehlerbilder](../anwender/fehlerbilder.md).

Ohne Gleisgraph kennt die Steuerung nicht, welche Melder und Züge in welchem Bezirk liegen. Ein Bezirksfehler betrifft deshalb vorläufig **alle** Melder und Züge (sicherste Variante); die Zuordnung kommt mit dem Gleisgraph (Schritt B).

## Geplant

- Störungen, die Gleisgraph und Positionsverfolgung brauchen (Schritt D): Melderausfall (bleibt frei), klemmender Melder, Geisterzug, falsche RailCom-Adresse, Schlupf, Weiche stellt nicht, verlorener Wagen, gleichzeitige Fahrstraßenanforderungen.
- Betriebsarten Vollsimulation, Hardware-in-the-Loop, Schattenbetrieb.
- Wiedergabe von Blackbox-Aufzeichnungen.
