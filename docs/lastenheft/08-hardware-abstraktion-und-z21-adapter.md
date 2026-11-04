# 8. Hardware-Abstraktion und Z21-Adapter

> Maßgebliche Fassung des Lastenhefts. Lesbare Fassung mit Diagrammen: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), wird nach jeder Änderung hier nachgezogen.

Jede Zentrale ist ein Adapter hinter derselben Schnittstelle und meldet ihre **Fähigkeiten**; die Automatik nutzt nur, was vorhanden ist.

- **Befehle:** Fahren (Fahrstufe und Richtung in *einem* Befehl, wie DCC sie überträgt), Lok-Nothalt, Funktionen F0–F31, Zubehör schalten, Gleisspannung, Nothalt, CV lesen/schreiben (Programmiergleis, POM Lok, POM Zubehör).
- **Ereignisse:** Lebenszeichen, Gleisspannung, Lokzustand, Zubehörzustand, Melder, RailCom-Adressen, CV-Ergebnis, Booster-Status, Fehler.
- **Fähigkeiten:** RailCom, POM lesen, Booster-Diagnose, CAN-Melder, Anzahl Funktionen, Zubehör-Rückmeldung.

**Z21-Adapter**

| Thema | Festlegung |
| --- | --- |
| Verbindung | UDP 21105, Statusabfrage jede Sekunde als Lebenszeichen und Anmeldungserhalt |
| Firmware | beim Verbinden abfragen und als Fähigkeiten ablegen: CAN-Melder und RailCom ab 1.30 (sonst R-Bus), Funktionen F29–F31 erst ab 1.42; nichts wird vorausgesetzt, nur weil es in der Schnittstelle steht |
| Melder | CAN (10808) bevorzugt: Belegung, Spannung, Überlast, RailCom-Adressen je Abschnitt mit Aufgleisrichtung; R-Bus als Alternative (Z21 spiegelt CAN-Belegung in die R-Bus-Daten, Modulzählung um eins versetzt – vor Nutzung zu klären) |
| Geräteverwaltung | CAN-Geräte erkennen (NetID, Moduladresse, Status), mit Geräteinventar abgleichen |
| Booster | Strom, Spannung, Temperatur und Kurzschluss je Bezirk, soweit die Z21 sie liefert |
| Lokzustände | zwei Verfahren: Einzel-Abos für gezielt überwachte Adressen (höchstens 16 je Client) und Lok-Broadcast für alle Loks (Broadcast-Flag 0x00010000, Firmware-abhängig, beim Verbinden geprüft; Firmware 1.43 nimmt das Flag an, sendet bei Fahrbefehlen anderer Clients aber keine Lokmeldungen – darauf wird nicht gebaut; auch Einzel-Abos melden Änderungen durch Handregler nicht – Stellvertreter-Adressen werden deshalb regelmäßig abgefragt); Kennzahlen getrennt: aktive Zugverbände und gleichzeitig gesteuerte Lokdecoder (Z21-Grenze laut Roco 100) |
| Zubehör | Schaltimpuls 150 ms, danach Ausgang aus; Stellzeit je Weiche einstellbar; Adressierungsmodus des Decoders (Roco oder RCN-213) ausdrücklich modelliert, beim Roco 10836 laut Anleitung zu prüfen |
| Offen, an Hardware zu prüfen | Bitbelegung POM-Zubehör, 10836-Adressierung, Rückschreiben auf die Anzeige von Handreglern an der Z21, Zusammenspiel der WLAN-Maus mit dem RailCore-LAN-Server, R-Bus-Modulzählung. Geklärt durch das Z21-Prüfprogramm (04.11.2026): RailCom-Richtungsbits = Aufgleisrichtung, nicht Fahrtrichtung; kein Lok-Broadcast bei Fremdsteuerung; Handregler auf Stellvertreter-Adresse per Abfrage lesbar (Verzögerung höchstens Abfrageabstand); Antwortzeiten 1–22 ms. |

**Rohdatenmitschnitt:** Auf Wunsch zeichnet der Adapter alle UDP-Pakete mit Zeitstempel auf (inklusive CAN-Meldermeldungen), für Diagnose und um Fehler später im Simulator nachzuspielen.

Verbindliche Grundlage für die Hardwareanbindung: Roco Z21 LAN-Protokollspezifikation, Dokumentation des Z21 Maintenance Tools, Anleitungen Roco 10836 und 10808.

Weitere Adapter (ECoS, CS3, DCC-EX, LocoNet) und der Simulator implementieren dieselbe Schnittstelle; neue Adapter werden als Plugin geladen.
