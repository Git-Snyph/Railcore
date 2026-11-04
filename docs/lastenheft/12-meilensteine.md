# 12. Meilensteine

> Quelle: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), Stand 03.11.2026. Maßgeblich ist das Dokument in claude.ai; Änderungen dort planen und hier nachziehen.

Jeder Meilenstein endet mit einem eigenständig testbaren Ergebnis; der nächste beginnt erst nach bestandener Abnahme.

| Nr. | Meilenstein | Inhalt | Abnahme |
| --- | --- | --- | --- |
| 1 | Kern und Sicherheitsbasis *(in Arbeit)* | Z21-Prüfprogramm an echter Hardware, Ereignisbus, Befehlswarteschlange, zentraler Executor, Watchdog, Simulator | offene Hardware-Fragen geklärt; jede Störung im Simulator führt zur festgelegten Reaktion |
| 2 | Gleisplan und Position | Gleisgraph, Meldeabschnitte, Blöcke, Melde- und Haltepunkte, wegbasierte Position mit Abgleich | simulierte Position bleibt innerhalb des Unsicherheitsbands |
| 3 | Datenbank und Einmessen | SQLite, MobaDB-Import, Zugverbände, Loks einmessen, Anlage vermessen, Decoder-Werkstatt | echte Lok eingemessen, Haltefehler höchstens 20 mm |
| 4 | Fahrmanagement | Reservierung, Verriegelung, Flankenschutz, Durchrutschweg, Bremskurve, Wiederanlauf | zwei Züge auf der echten Anlage, nie im selben Abschnitt |
| 5 | Oberfläche | Live-Gleisbild, Gleisplan-Editor, Stellpulte, Diagnoseansicht, Verkabelungsebene | Anlage vollständig im Editor erfasst und live dargestellt |
| 6 | Vollautomatik | Prioritäten, Überholen, Deadlock-Erkennung, Schattenbahnhof mit Nachrücken, Signale, WLAN-Maus | 24 h Simulator-Dauerlauf mit 100 Zügen ohne Konflikt |
| 7 | Betrieb | Fahrpläne, Rangieren, Drehscheibe, Licht, Aktionen, Betriebsprotokoll | ein Fahrplantag auf der echten Anlage |
| 8 | Schnittstellen | REST mit Live-Ereignissen, MQTT mit Home-Assistant-Discovery, Anmeldung und TLS | Home Assistant schaltet Licht und startet die Automatik |
| 9 | Mobile Apps | iOS und Android: Fahren, Stellpult, Live-Gleisbild | Zug per Handy unter Zugbeeinflussung gefahren |

Die Sicherheitsbasis liegt in Meilenstein 1, Reservierung und Verriegelung in 4, also vor der Vollautomatik. Bereits im Datenmodell vorgesehen, aber später umgesetzt: Benutzerrollen, Umlaufplanung, Anschlusszüge, Wetter und Bahnhofsdurchsagen, weitere Zentralen.

**Offene Risiken, vor der Vollautomatik zu lösen**

| Risiko | Klärung |
| --- | --- |
| Zugbeeinflussung mit WLAN-Maus | WLAN-Maus über den RailCore-LAN-Server an der echten Maus testen (Entscheidung 21); Stellvertreter-Adresse nur noch für Handregler an der Z21 |
| Physische Weichenlage und Zugschluss | entschieden: Lagekontakte und Widerstandsachsen optional; Weichen in Grundstellung beim Verbinden, Zugschluss aus gepflegten Längen mit Sicherheitszuschlag (Meilenstein 2 und 4) |
| Reaktionszeiten und Fehlerverhalten | Messung mit Perzentilen an echter Z21, Fehlerkatalog im Simulator (Meilenstein 1 und 4) |

**Zusätzlich eingeplant (P2):** Rohdatenmitschnitt und zeitliche Darstellung aller Ereignisse (Meilenstein 5), Betriebsstatistik und Verspätungsauswertung (Meilenstein 7), mehrere Ansichten und Stellpulte (Meilenstein 5).

**Spätere Ausbaustufen (P2 und danach), im Datenmodell vorgesehen:**

- **Erweiterte Fahrplanung:** dynamische Fahrpläne, Umlaufplanung, Verspätungsmanagement mit verspätungsabhängigen Prioritäten, Anschlusszüge, Mindestaufenthalte, automatische Zugbereitstellung, Lokwechsel und Zugwende, Soll/Ist-Vergleich, Bildfahrplan; ein Betriebsdispatcher plant die nächsten Abläufe voraus.
- **Szenario-Manager:** Normalbetrieb, Hauptverkehrszeit, Güterverkehr, Baustellen-, Störungs-, Museums- und Nachtbetrieb; jedes Szenario legt Fahrpläne, Zugbildung, Prioritäten und Licht fest.
- **Energieüberwachung** je Booster-Bezirk, **Sound** (Bahnhofsdurchsagen aus dem Fahrplan, situationsabhängige Geräusche), **Besucherbetrieb** mit eingeschränkten Rechten, **Führerstandsanzeige** für den Handbetrieb.
- Ideen ohne feste Planung: digitaler Anlagenzwilling als 3D-Ansicht, Wettereffekte, KI-gestützte Auswertung der Diagnose.
