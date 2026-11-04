# 2. Architektur

> Maßgebliche Fassung des Lastenhefts. Lesbare Fassung mit Diagrammen: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), wird nach jeder Änderung hier nachgezogen.

```text
Bedienung
  Desktop · WLAN-Maus · Netzwerk-API · Home Assistant · Mobile Apps
        │
Steuerungs-Schnittstelle
  Befehle · Abfragen · Ereignisse · Anmeldung
        │
Kern (läuft unabhängig von jeder Oberfläche)
  Befehlswarteschlange   Nothalt › Sicherheit › Automatik › Fahrplan › Hand › Komfort
        └─► Sicherheitsmanager   prüft jeden Befehl · Watchdog · Melder-Filter ·
                                 Plausibilität · Fehlerreaktionen · Nothalt
  Betriebsmanager        Automatik, Fahrplan, Prioritäten, Überholen, Aktionen, Licht
  Fahrmanagement         Reservierung, Wegsuche, Deadlock, Verriegelung
  Positionsmanager       Position in mm, Unsicherheit, Bremskurve
  Fahrzeugmanager        Loks, Wagen, Zugverbände, Lok- und Zugprofile
  Anlagenmanager         Gleisgraph, Blöcke, Weichen, Signale, Melder, Geräte
  Persistenz             Zustand sichern, Wiederanlauf, SQLite
  Ereignisbus            jede Änderung an Oberfläche, Diagnose, Protokoll und MQTT
        │
Hardware-Abstraktion
  Z21-Adapter · Simulator · ECoS, CS3, DCC-EX als Plugins
        │
Anlage
  Z21, Booster, 10808-Melder, Weichen- und Lokdecoder
```

Oberflächen enthalten keine Steuerlogik: Sie senden Befehle an die Steuerungs-Schnittstelle und zeigen Ereignisse an. Im Kern ordnet die Warteschlange jeden Befehl nach Priorität, der Sicherheitsmanager gibt ihn frei oder begrenzt ihn, erst dann geht er an die Zentrale. Jede Zustandsänderung läuft als Ereignis über den Ereignisbus (z. B. BLOCK\_OCCUPIED, ROUTE\_RESERVED, RAILCOM\_LOCO\_DETECTED, EMERGENCY\_STOP).

**Nebenläufigkeit (festgeschrieben)**

- Ein einziger **Steuerungs-Executor** führt alle Änderungen am Anlagenzustand nacheinander aus (Reservierungen, Positionen, Zugzustände). Zwei Züge können dadurch nie gleichzeitig dieselbe Weiche reservieren.
- Eigene Threads für UDP-Empfang, Senden und Hardwarekommunikation; sie legen Ereignisse nur in die Warteschlange des Executors.
- Nothalt umgeht die normale Reihenfolge: Er wird sofort gesendet, nicht erst im nächsten Rechentakt.
- Der **Watchdog** läuft in einem eigenen Thread außerhalb der Steuerschleife und löst Nothalt aus, wenn der Executor selbst hängt oder seinen Takt verpasst.
- Swing nur auf dem Event-Dispatch-Thread; die Oberfläche liest Momentaufnahmen und sendet Befehle, sie verändert nie direkt Zustände.
- Datenbankzugriffe asynchron in einem eigenen Thread, außerhalb des zeitkritischen Pfads.
- Zeitbasis für Bewegung ist ausschließlich System.nanoTime; Protokolleinträge erhalten zusätzlich die Uhrzeit (Instant).

**Kernmodule** (alle ohne Abhängigkeit von der Oberfläche):

| Modul | Aufgabe | Priorität |
| --- | --- | --- |
| SystemHealthManager | Gesamtzustand der Anlage, Ampel, Hardware-Watchdog | P1 |
| CommissioningManager | Selbsttest, Inbetriebnahme, Lernfahrt | P1 |
| CommandScheduler | Befehlswarteschlange, DCC-Budget, Priorisierung | P1 |
| MotionController | Trägheit, Bremskurven, Geschwindigkeitsvorgaben | P1 |
| TrainIntegrityManager | Zugschluss, verlorene Wagen | P1 |
| EventRecorder | Ereignisaufzeichnung, Blackbox, Wiedergabe | P1 |
| MaintenanceManager | Wartung, Kilometer, Betriebsstunden | P2 |
| TimetableManager | Fahrpläne, Umläufe, Dispatcher | P2 |
| ScenarioManager | Betriebs- und Lichtszenarien | P2 |
| EnergyManager | Stromverbrauch, Boosterlast | P2 |
| StatisticsManager | Betriebsstatistik, Soll/Ist-Auswertung | P2 |
| PluginManager | Erweiterungen, weitere Zentralen | P2 |

**Desktop-Oberfläche** (P2): frei konfigurierbare Stellpulte und mehrere Gleisplanansichten, Vollbild- und Nachtmodus, Farben je Zugart, im Gleisbild Zugnummer, echte Zuglänge, Geschwindigkeit, Richtung, reservierte Fahrstraßen und Positionsunsicherheit; Fahrstraßen-Vorschau und manuelles Stellen, Kontextmenüs für Block und Lok. **Zug verfolgen:** Ein Klick auf einen Zug zentriert die Ansicht auf ihn und zeigt Fahrweg, nächste Halte und Bremskurve.

**Produktbereiche:** Die Software heißt **RailCore** und gliedert sich in Produktbereiche. Alle teilen denselben Kern (RailCore Control) und dieselbe Datenbank; jeder Bereich ist ein eigenes Fenster bzw. eine eigene App, kein getrenntes Programm.

| Produktbereich | Inhalt | Kernmodule |
| --- | --- | --- |
| RailCore Control | Steuerung, Automatik, Handbetrieb, Sicherheit | CommandScheduler, MotionController, Fahrmanagement, Betriebsmanager, SystemHealthManager, TrainIntegrityManager |
| RailCore Studio | Gleisplan-Editor, Verkabelung, Stellpulte, Lernfahrt | Anlagenmanager, CommissioningManager |
| RailCore Fleet | Fahrzeuge, Zugverbände, Einmessen, Wartung | Fahrzeugmanager, MaintenanceManager |
| RailCore Decoder | Decoder-Werkstatt: Auslesen, Momentaufnahmen, Backups | Decoder-Werkstatt (Teil des Fahrzeugmanagers) |
| RailCore Timetable | Fahrpläne, Umläufe, Szenarien, Bildfahrplan | TimetableManager, ScenarioManager |
| RailCore Diagnostics | Diagnosezentrum, Blackbox, Wiedergabe, Statistik | EventRecorder, StatisticsManager, EnergyManager |
| RailCore Simulator | Simulation, Störungstests, Hardware-in-the-Loop | Simulator-Adapter |
| RailCore Connect | REST, MQTT, Home Assistant, Netzwerksicherheit | Steuerungs-Schnittstelle |
| RailCore Mobile | Smartphone-Apps (iOS, Android) | über RailCore Connect |
