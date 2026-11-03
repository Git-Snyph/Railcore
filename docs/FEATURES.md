# Bahnsteuerung – Anforderungen und Meilensteine

Stand: 03.11.2026. Eigene Modellbahn-Steuerung (Java 21), Ablage `D:\Claude\itrain`.

## Hardware (Ist-Stand laut MobaDB)
- Zentrale: Roco Z21 (schwarz) 10820, 2× Dual Booster 10807 (ZS1/ZS2, Fahr), CAN-Hub 10804, Pro Link, Z21 WLAN-Maus, Zimo KLUG
- Rückmelder: 18× Z21 Detector 10808 mit RailCom (CAN), Kontakt = (Modul−1)·8 + Eingang (z. B. RM017/1 = 129)
- Weichen: Roco Weichendecoder 10836 (WD001: 1–8, WD002: 9–16), Weichen W001… mit Adresse = Nummer
- Decoder überwiegend ZIMO (MN330, MS450, MS440, MX630), außerdem ESU LokPilot 5
- Maßstab H0, 128 Fahrstufen

## Technik
- Eigenständiges Desktop-Programm (keine Web-Oberfläche): Java 21, Swing + FlatLaf, Live-Gleisbild mit Java2D
- Auslieferung als Windows-Programm mit Installer (jpackage, Java mitgeliefert)
- Kern (Steuerung, Automatik) intern getrennt von der Oberfläche; API/MQTT greifen auf den Kern zu

## Anforderungen
### Zentralen
- [x] Z21 über UDP: Fahren, Funktionen F0–F31, Weichen, Gleisspannung, Nothalt
- [x] R-Bus-Rückmelder
- [x] CAN-Belegtmelder (10808) inkl. RailCom-Lokadressen je Abschnitt
- [x] CV lesen/schreiben: Programmiergleis, POM Lok, POM Zubehör
- [x] Schnittstelle für weitere Zentralen (Plugin-fähig)
- [ ] Weitere Zentralen (ECoS, CS3, DCC-EX …)

### Fahrzeuge und Datenbank
- [ ] Eigene Datenbank: Loks, Triebwagen, Personen-/Güter-/Sonderwagen, Gesamtzüge, Rückmelder, Weichen(-decoder), Steuerung, Inventar (Kaufdatum, Preis, OVP, Anleitung, Wartung)
- [ ] Import aus MobaDB.xlsx
- [ ] Betriebsregeln: Adressbereiche je Kategorie, Standard-Funktionsbelegung F0–F28, Prioritäten P1–P6, Haltezeiten, Geschwindigkeitsklassen, Zugnummern-Schema, Zugbildung/Wagenreihung, Doppeltraktion/Vorspann/Schublok, Lokwechsel
- [ ] ZIMO-Modul: Decoder erkennen (CV8 = 145), CVs mit Klartext, Sichern/Wiederherstellen

### Einmessen und Vermessen
- [ ] Loks/Züge einmessen: Geschwindigkeit je Fahrstufe (mm/s, beide Richtungen), Beschleunigung, Bremsweg
- [ ] Anlage vermessen mit eingemessenem Zug: Blocklängen und Melderpositionen
- [ ] Abgleich mit CV3/CV4 (Anfahr-/Bremsverzögerung)

### Gleisplan und Betrieb
- [ ] Blöcke: Pflicht 1 Rückmelder, empfohlen 3; Weichen mit Belegt- und Lagerückmeldung
- [ ] Fahrstraßen mit Weichenstellungen, Signale mit Signalbildern
- [ ] Wegbasierte Live-Position (unabhängig von Melde-/Halteblöcken), Rückmelder und RailCom als Abgleich
- [ ] Bremsen nach Berechnung, zielgenaues Halten
- [ ] Live-Gleisbild mit Zügen in echter Länge
- [ ] Vollautomatik: Anlage starten, alle Züge fahren selbstständig; Geisterzug-Erkennung → Nothalt
- [ ] Handbetrieb im Automatikbetrieb (z. B. Z21 WLAN-Maus) mit Zugbeeinflussung: Bremskurve bis rotem Signal/Ende der Freigabe, Geschwindigkeitsüberwachung, Reservierung vor dem Handzug
- [ ] Schattenbahnhof mit Nachrücken (z. B. 3 Blöcke je Gleis)
- [ ] Drehscheibe
- [ ] Fahrpläne mit Modellzeit
- [ ] Lichtsteuerung (Gruppen, Tag/Nacht nach Modellzeit, Szenen)
- [ ] Aktionen: Auslöser → Bedingungen → Befehle
- [ ] Verkabelung und Geräte im Gleisplan darstellen (Zentrale, Booster, Rückmeldemodule, Decoder), Adressprüfung

### Schnittstellen nach außen
- [ ] REST-API + Live-Ereignisse (SSE)
- [ ] MQTT mit Home-Assistant-Discovery
- [ ] iOS- und Android-App zum Steuern (später; nutzt die Netzwerk-Schnittstelle des Kerns)

## Meilensteine
1. Kern: Zentralen-Schnittstelle, Z21, Simulator, Gleisplan-Modell, wegbasierte Position *(in Arbeit)*
2. Datenbank + Excel-Import, Einmessen, Anlagenvermessung, ZIMO-Modul
3. Live-Gleisbild, Gleisplan-Editor, Verkabelungsebene
4. Vollautomatik, Schattenbahnhof, Signale, Aktionen
5. Fahrpläne, Licht, Drehscheibe, REST/MQTT/Home Assistant
6. Mobile Apps (iOS/Android)
