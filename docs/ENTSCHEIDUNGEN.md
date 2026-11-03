# Entscheidungsprotokoll – RailCore

Jede Entscheidung: Datum · was · warum · Folgen. Neue Einträge oben anfügen; überholte Entscheidungen nicht löschen, sondern als „ersetzt durch Nr. X" markieren.

| Nr. | Datum | Entscheidung | Begründung | Folgen |
| --- | --- | --- | --- | --- |
| 18 | 2026-11-03 | Java 25 LTS (Eclipse Temurin) statt Java 21; ersetzt den Java-Teil von Nr. 2 | Längere Unterstützung, virtuelle Threads ohne Blockade bei synchronized, generationaler ZGC mit kurzen Pausen, Wechsel im frühen Stadium ohne Aufwand | Gradle-Wrapper 9.x nötig; Build-Toolchain auf 25 |
| 17 | 2026-11-03 | Logo „RailCore – Railway Control & Operations“ (docs/bilder/railcore-logo.png); Hausfarben Nachtblau #08121C, Signalrot #EB1C24, Weiß | Einheitliches Erscheinungsbild für Programm, Installer und Apps | Dunkles FlatLaf-Thema in diesen Farben; Programmsymbol (Zugsilhouette mit Gleis, ohne Schrift) als PNG und ICO, transparente Ecken |
| 16 | 2026-11-03 | Projektname RailCore mit Produktbereichen (Control, Studio, Fleet, Decoder, Timetable, Diagnostics, Simulator, Connect, Mobile) | Klare Gliederung für Oberfläche, Code und spätere Apps | Ein gemeinsamer Kern und eine Datenbank; Java-Paket at.schmoegl.railcore |
| 15 | 2026-11-03 | Entwicklung in Claude Code mit privatem GitHub-Repository | Code liegt dauerhaft im Repository, wird gebaut und automatisch getestet | Simulator-Tests laufen bei jeder Änderung |
| 14 | 2026-11-03 | Zugschluss längenbasiert; Widerstandsachsen optional | Alle Fahrzeug-, Block-, Weichen- und Fahrstraßenlängen werden gepflegt; Umbau aller Wagen nicht nötig | Freigabe erst nach berechnetem Zugschluss + Sicherheitszuschlag; abgerissener Wagen ohne Stromaufnahme bleibt unerkannt (akzeptiertes Restrisiko) |
| 13 | 2026-11-03 | Züge dürfen Blöcke durchfahren, die kürzer sind als sie | Ergibt sich aus der längenbasierten Belegung | Belegt sind alle Abschnitte von Spitze bis Zugschluss; Längenprüfung gilt für Haltebereiche |
| 12 | 2026-11-03 | Weichenlage-Rückmeldung optional; Schaltartikel beim Verbinden in Grundstellung | Roco 10836 meldet keine Endlage; Endlagenkontakte wären Zusatzaufwand | Weichen unter Zügen oder in unbekannten Abschnitten werden dabei nicht geschaltet |
| 11 | 2026-11-03 | Physischer Not-Aus-Taster als Option, am Hardware-Watchdog | Not-Aus muss auch ohne PC wirken | Entriegeln am Taster, Freigabe in der Steuerung |
| 10 | 2026-11-03 | Hardware-Watchdog außerhalb des PCs (Mikrocontroller) | Die Z21 fährt bei PC-Absturz die letzte Fahrstufe weiter | Abschaltung zweistufig: Gleisspannung an der Z21, Relais an der Booster-Versorgung |
| 9 | 2026-11-03 | Befehlsrate dynamisch nach Z21- und Gleislast, keine feste Rate | DCC-Bandbreite ist für die ganze Anlage begrenzt | Brems- und Sicherheitsbefehle haben Vorrang |
| 8 | 2026-11-03 | Software Motion Control als Standard (CV3/CV4 = 0, Trägheit aus der Steuerung); Decoder Motion Control als Ausnahme je Lok | Steuerung kennt die Sollgeschwindigkeit exakt; keine doppelte Verzögerung | Modus je Lok in der Datenbank; Profil ungültig bei CV-Änderung |
| 7 | 2026-11-03 | Stellvertreter-Adressen 9000–9999 für Handbetrieb mit WLAN-Maus | Die Z21 kann Handregler-Befehle nicht abfangen; über eine leere Adresse bekommt die echte Lok nur geprüfte Befehle | Bereich ausschließlich dafür; Wagendecoder umadressieren; noch an Hardware zu prüfen |
| 6 | 2026-11-03 | Netzwerkzugänge standardmäßig aus, erste API nur lesend | Keine ungeschützte Fernsteuerung | Schreibrechte und Nothalt getrennt berechtigt |
| 5 | 2026-11-03 | SQLite maßgeblich, JSON nur Austausch/Sicherung | Transaktionen, Versionierung, Migrationen | Gleisplan versioniert mit Rückgängig |
| 4 | 2026-11-03 | Ein zentraler Steuerungs-Executor für alle Zustandsänderungen | Keine Race Conditions bei Reservierungen | Kommunikation in eigenen Threads, Swing nur auf dem EDT |
| 3 | 2026-11-03 | Wegbasierte Positionsberechnung; Rückmelder und RailCom sind Abgleichpunkte | Bremsen und Halten unabhängig von Brems-/Haltemeldern | Einmessen aller Loks/Züge nötig |
| 2 | 2026-11-03 | Java 21 (Java-Version ersetzt durch Nr. 18), Swing + FlatLaf, eigenständiges Desktop-Programm, keine Web-Oberfläche | Wunsch nach Programm statt Browser; stabil im Dauerbetrieb | Kern getrennt von Oberfläche, API für Home Assistant und Apps |
| 1 | 2026-11-03 | Eigene Steuerung statt iTrain-Weiterentwicklung | Keine belegte Erlaubnis zum Dekompilieren; iTrain-Lizenz verbietet es | Keine iTrain-Bestandteile im Projekt |
