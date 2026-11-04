# 10. Simulator und Tests

> Maßgebliche Fassung des Lastenhefts. Lesbare Fassung mit Diagrammen: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), wird nach jeder Änderung hier nachgezogen.

Der Simulator ist ein vollwertiger Adapter: Er bildet Zentrale, Booster, Melder, RailCom, Weichen und Signale nach und bewegt Züge mit eigenen, absichtlich vom eingemessenen Profil abweichenden „wahren“ Profilen. So muss die Positionsrechnung auch im Test korrigieren.

**Störungen, die der Simulator auf Knopfdruck oder per Skript auslöst:** Verbindungsabbruch und Paketverlust, Kurzschluss je Bezirk, Melderausfall (bleibt frei), klemmender Melder (bleibt belegt), Flackern, Geisterzug, falsche RailCom-Adresse, Schlupf, Weiche stellt nicht, verlorener Wagen, Verspätung, gleichzeitige Fahrstraßenanforderungen.

**Betriebsarten**

- **Vollsimulation:** alles virtuell, auch schneller als Echtzeit für Dauerläufe.
- **Hardware-in-the-Loop:** echte Z21 und echte Melder, einzelne Züge oder Bereiche simuliert.
- **Schattenbetrieb:** echte Anlage fährt von Hand, die Steuerung rechnet nur mit und vergleicht.

**Abnahmekriterien je Meilenstein** (automatisierte Tests)

| Bereich | Kriterium |
| --- | --- |
| Positionsgenauigkeit | 95 % der Halte an Bahnsteig-Haltepunkten innerhalb ±10 mm bei eingemessenem Zug, sofern die für den Haltepunkt berechnete Genauigkeit ±10 mm erlaubt; an allen anderen Haltepunkten innerhalb der dort berechneten Genauigkeit (Nothalt und ungeplante Halte ausgenommen), Abweichung beim Abgleich ≤ Unsicherheitsband |
| Sicherheit | jeder Fehler aus dem Fehlerkatalog führt im Simulator zur festgelegten Reaktion; kein Test darf zwei Züge im selben Abschnitt erzeugen |
| Dauerlauf | 100 Züge, 24 h simulierte Betriebszeit, kein Deadlock, keine Kollision, keine Speicherzunahme |
| Reaktionszeit | gemessene Zielwerte mit Perzentilen (z. B. Melderereignis bis Fahrbefehl: 95 % unter 50 ms; Nothalt: 99 % unter 20 ms nach Erkennung), dokumentiert je Version; keine Echtzeitgarantie unter Windows und Java |
| Wiederanlauf | Abbruch an beliebiger Stelle, Neustart stellt alle Züge korrekt oder als „unbestätigt“ wieder her |
| Befehlslast | Befehlsverzögerung an echter Z21 bei 10, 25, 50 und 100 aktiven Loks gemessen und dokumentiert; Bremsbefehle bleiben auch bei Volllast innerhalb des Zielwerts |
| Wiedergabe | eine Blackbox-Aufzeichnung (z. B. die letzten 60 s vor einem Halt an einer Weiche) läuft im Simulator exakt nach und führt zum selben Ergebnis |
