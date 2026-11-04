# 8. Hardware-Abstraktion und Z21-Adapter

> Quelle: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), Stand 03.11.2026. Maßgeblich ist das Dokument in claude.ai; Änderungen dort planen und hier nachziehen.

Jede Zentrale ist ein Adapter hinter derselben Schnittstelle und meldet ihre **Fähigkeiten**; die Automatik nutzt nur, was vorhanden ist.

- **Befehle:** Fahren (Fahrstufe und Richtung in *einem* Befehl, wie DCC sie überträgt), Lok-Nothalt, Funktionen F0–F31, Zubehör schalten, Gleisspannung, Nothalt, CV lesen/schreiben (Programmiergleis, POM Lok, POM Zubehör).
- **Ereignisse:** Lebenszeichen, Gleisspannung, Lokzustand, Zubehörzustand, Melder, RailCom-Adressen, CV-Ergebnis, Booster-Status, Fehler.
- **Fähigkeiten:** RailCom, POM lesen, Booster-Diagnose, CAN-Melder, Anzahl Funktionen, Zubehör-Rückmeldung.

**Z21-Adapter**

| Thema | Festlegung |
| --- | --- |
| Verbindung | UDP 21105, Statusabfrage jede Sekunde als Lebenszeichen und Anmeldungserhalt |
| Firmware | beim Verbinden abfragen und als Fähigkeiten ablegen: CAN-Melder und RailCom ab 1.30 (sonst R-Bus), Funktionen F29–F31 erst ab 1.42; nichts wird vorausgesetzt, nur weil es in der Schnittstelle steht |
| Melder | CAN (10808) bevorzugt: Belegung, Spannung, Überlast, RailCom-Adressen je Abschnitt; R-Bus als Alternative |
| Geräteverwaltung | CAN-Geräte erkennen (NetID, Moduladresse, Status), mit Geräteinventar abgleichen |
| Booster | Strom, Spannung, Temperatur und Kurzschluss je Bezirk, soweit die Z21 sie liefert |
| Lokzustände | zwei Verfahren: Einzel-Abos für gezielt überwachte Adressen (höchstens 16 je Client) und Lok-Broadcast für alle Loks (Broadcast-Flag 0x00010000, Firmware-abhängig, beim Verbinden geprüft); Kennzahlen getrennt: aktive Zugverbände und gleichzeitig gesteuerte Lokdecoder (Z21-Grenze laut Roco 100) |
| Zubehör | Schaltimpuls 150 ms, danach Ausgang aus; Stellzeit je Weiche einstellbar; Adressierungsmodus des Decoders (Roco oder RCN-213) ausdrücklich modelliert, beim Roco 10836 laut Anleitung zu prüfen |
| Offen, an Hardware zu prüfen | Richtungsbits der RailCom-Adressen, Bitbelegung POM-Zubehör, Lok-Broadcast, Stellvertreter-Adresse mit WLAN-Maus, 10836-Adressierung, Antwortzeiten; geklärt durch das Z21-Prüfprogramm in Meilenstein 1 |

**Rohdatenmitschnitt:** Auf Wunsch zeichnet der Adapter alle UDP-Pakete mit Zeitstempel auf (inklusive CAN-Meldermeldungen), für Diagnose und um Fehler später im Simulator nachzuspielen.

Verbindliche Grundlage für die Hardwareanbindung: Roco Z21 LAN-Protokollspezifikation, Dokumentation des Z21 Maintenance Tools, Anleitungen Roco 10836 und 10808.

Weitere Adapter (ECoS, CS3, DCC-EX, LocoNet) und der Simulator implementieren dieselbe Schnittstelle; neue Adapter werden als Plugin geladen.
