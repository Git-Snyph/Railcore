# 9. Datenmodell und Persistenz

> Quelle: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), Stand 03.11.2026. Maßgeblich ist das Dokument in claude.ai; Änderungen dort planen und hier nachziehen.

Eine SQLite-Datei je Anlage ist die maßgebliche Datenhaltung, mit vier klar getrennten Bereichen. Stammdaten und Konfiguration ändert der Bediener, Betriebszustand schreibt nur der Kern, Historie wird nur angehängt.

| Bereich | Entitäten |
| --- | --- |
| Stammdaten | Fahrzeuge (Loks, Triebwagen, Wagen, Sonderwagen) mit Inventar (Kaufdatum, Preis, OVP, Anleitung), Decoder und CV-Sätze, Geräte (Zentrale, Booster, Melder, Decoder) mit Kanälen, Wartung |
| Konfiguration | Gleisgraph, Meldeabschnitte, Blöcke, Punkte, Weichen, Fahrstraßen, Signale, Streckenprofile, Bahnhöfe, Schattenbahnhöfe, Drehscheiben, Verkabelung, Zugverbände, Zugprofile (Railjet, Cityjet, Regional, Güter, Sonder), Betriebsregeln, Fahrpläne, Aktionen, Licht, Benutzer |
| Betriebszustand | Zugpositionen mit Unsicherheit, aktive Reservierungen, Weichen- und Signallagen, Melderzustände, Zugzustände, Lok- und Zugprofile (Messwerte) |
| Historie | Ereignisse, Störungen, Betriebsprotokoll (Fahrten, Eingriffe, Fahrstraßen), Messläufe, Betriebssitzungen |

**Zugverband:** Lok(s) plus Wagen in Reihenfolge, auch mit mehreren Decodern je Verband (Doppeltraktion, Wagendecoder, Steuerwagen); Fahrzeug und Decoder werden getrennt verwaltet, damit ein Decoder umgebaut oder eine Adresse geändert werden kann, ohne das Fahrzeug neu anzulegen; Länge wird aus den Fahrzeuglängen berechnet, führendes und schiebendes Fahrzeug, Steuerwagen, Kategorie, Priorität, Mindest-/Höchstlänge für Gleise, Fahrzeugstatus (verfügbar, Wartung, defekt, abgestellt). Offsets vorne/hinten (MobaDB) geben an, wo die erste und letzte stromabnehmende Achse sitzt, und fließen in die Melderauswertung ein.

**Fahrzeug- und Zugmanagement:** Zugbildungsvorlagen und automatische Zugzusammenstellung nach Regeln, Fahrzeugverfügbarkeit, Zugverbandhistorie, Gewicht und Achslast als Stammdaten (Grundlage für das Masse-Modell), Höchstgeschwindigkeit je Fahrzeug und je Zugverband, automatische Prüfung der Zuglänge gegen Bahnsteige und Blöcke. **Wartung:** Kilometerzähler (Modell und Vorbild) und Betriebsstunden je Lok aus der Positionsrechnung, Wartungsintervalle mit Erinnerung (Schmieren, Haftreifen, Radreinigung), Wartungsprotokoll, Verschleiß- und Reparaturhistorie; Fahrzeuge in Wartung werden für den Betrieb gesperrt.

**Decoder-Werkstatt: Auslesen, Speichern, Sichern**

- **Auslesen:** vollständig auf dem Programmiergleis oder per POM-Lesen über RailCom auf dem Hauptgleis. Zuerst wird der Decoder erkannt (CV8 Hersteller, CV7 Version, bei ZIMO zusätzlich Decoder-ID CV250–253 und Typ), dann werden die bekannten CVs des Typs gelesen. Erweiterte CV-Bereiche (z. B. ZIMO-Soundparameter über Seitenumschaltung) werden mit berücksichtigt. Ein vollständiger Sound-Decoder kann auf dem Programmiergleis mehrere Minuten dauern; Fortschritt und Abbruch sind sichtbar.
- **Speichern:** Jeder Lesevorgang wird als **Momentaufnahme** mit Datum, Quelle (Programmiergleis/POM), Decoder-ID und Firmware in der Datenbank abgelegt, je CV Wert und Lesestatus (gelesen, nicht lesbar, Fehler). Ältere Momentaufnahmen bleiben erhalten (Verlauf).
- **Vergleichen:** zwei Momentaufnahmen oder Momentaufnahme gegen Soll-Profil (z. B. CV3/CV4 = 0, Funktionsbelegung nach Standard); Abweichungen werden markiert.
- **Schreiben und Wiederherstellen:** vor jedem Schreiben automatisch eine Momentaufnahme; Wiederherstellen einer beliebigen Momentaufnahme mit anschließendem Kontroll-Lesen.
- **Backup-Dateien:** Export und Import je Decoder oder für alle Decoder als Datei (eigenes JSON-Format mit Decoderdaten und CV-Liste, zusätzlich CSV zum Ansehen in Excel). Automatisches Backup aller Decoder in einen wählbaren Ordner, z. B. nach jeder Änderung und wöchentlich.
- **Zuordnung:** Momentaufnahmen hängen am Decoder, nicht am Fahrzeug; wird ein Decoder umgebaut, wandert sein Verlauf mit.

**Persistenz und Wiederanlauf**

- Betriebszustand wird bei jeder relevanten Änderung, mindestens alle 2 s, transaktional gespeichert; ein Absturz verliert höchstens diesen Stand.
- Beim Start sind alle Züge „unbestätigt“ und alle Melder „unbekannt“. Die Steuerung liest Melder, RailCom und Weichen ein und vergleicht mit dem gespeicherten Stand. Erst wenn jeder Zug bestätigt oder vom Bediener zugeordnet ist, wird die Automatik freigegeben.
- Automatische Sicherung der Datenbank täglich und vor jedem Programm-Update; Schema-Migrationen mit Versionsnummer.
- Import aus MobaDB.xlsx; Export und Import der kompletten Anlagenkonfiguration (Gleisplan, Fahrzeuge, Profile, Regeln) als JSON-Paket, etwa zur Sicherung oder zum Umzug auf einen anderen Rechner.
- Transaktions- und Wiederherstellungstests: Abbruch mitten im Schreiben, beschädigte Datei, Wiederherstellen aus Sicherung.

**Automatische Zugerkennung beim Start:** Nach dem Einschalten fragt die Steuerung alle 10808 nach Belegung und RailCom-Adressen ab. Jede erkannte Lok wird ihrem Zugverband und Abschnitt zugeordnet und mit dem gespeicherten Stand verglichen:

- Übereinstimmung → Zug bestätigt, Position aus gespeichertem Stand innerhalb des Abschnitts übernommen.
- Lok an anderer Stelle als gespeichert → Hinweis, Position aus RailCom übernommen, Zug bleibt „unbestätigt“ bis zur Quittung.
- Belegung ohne RailCom-Adresse (z. B. Wagen, Lok ohne RailCom) → Bediener ordnet zu.
- Gespeicherter Zug nicht gefunden → als vermisst gemeldet, seine Abschnitte bleiben gesperrt.

Damit läuft der Wiederanlauf im Normalfall ohne Handarbeit; die Automatik wird erst freigegeben, wenn alle Züge bestätigt sind.
