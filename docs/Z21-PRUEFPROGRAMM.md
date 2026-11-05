# Z21-Prüfprogramm

Klärt an der echten Z21 die offenen Hardware-Fragen (Lastenheft Kap. 8) und erkennt die Fähigkeiten der Zentrale. Entscheidung 20.

## Start

In PowerShell mit `.\gradlew`, in der Eingabeaufforderung (cmd) mit `gradlew`.

```
.\gradlew z21Check --console=plain --args="--host 192.168.0.111"              # Stufe 1, passiv
.\gradlew z21Check --console=plain --args="--host 192.168.0.111 --dauer 120"  # längere Beobachtung
.\gradlew z21Check --console=plain --args="--host 192.168.0.111 --stufe aktiv"
.\gradlew z21Check --console=plain --args="--hilfe"                           # alle Optionen
```

Ergebnis: Prüfbericht `berichte/z21-pruefbericht-<Datum>-<Zeit>.txt`, vollständiges Journal mit allen Rohpaketen unter `logs/`.

## Stufe 1 – passiv (Standard)

Sendet nur Abfragen und Abos (Positivliste in `CommandClass`): Seriennummer, HW-Info, Firmware, Freischaltcode, Status, Systemzustand, Broadcast-Flags setzen und zurücklesen, R-Bus, CAN-Melder, Lokinfo der Stellvertreter-Adresse, Weicheninfo. Danach werden Melder-, RailCom- und Lokmeldungen mitgeschnitten; die Stellvertreter-Adresse wird dabei regelmäßig abgefragt (`--abfrage-ms`, Standard 250 ms, 0 = aus), weil die Z21 Handregler-Befehle nicht von sich aus meldet. Während der Beobachtung bietet es sich an,

- Melder zu belegen und Loks mit RailCom auf überwachte Abschnitte zu stellen (RailCom-Adressen und Richtungsbits),
- eine Lok mit einem Handregler zu bewegen (Lok-Broadcast, F29–F31-Byte),
- die Stellvertreter-Adresse an der WLAN-Maus aufzurufen – nur, wenn dort sicher kein Decoder ist.

Zusätzlich wird das Broadcast-Flag 0x00020000 (CAN-Booster) gesetzt und die Zustandsmeldung der Booster (Header 0xCA, je Booster und Ausgang etwa einmal pro Sekunde) mitgeschnitten. Das ist nur Beobachtung: Booster-Schaltbefehle (0xCB, 0xB2) stehen nicht auf der Positivliste und werden vom Prüfprogramm nie gesendet.

## Stufe 2 – aktiv

Jeder Eingriff wird im Klartext angekündigt und nur nach der Eingabe `JA` (Großbuchstaben) ausgeführt. Jede andere Eingabe, eine Zeitüberschreitung, eine fehlende Rückmeldung oder mehr als `--verbindung-ms` Stille bricht die Stufe ab: ein aktiver Weichenausgang wird abgeschaltet, an die Stellvertreter-Adresse geht sofort ein Nothalt.

1. Gleisspannung einschalten (nur wenn aus; Nothalt, Kurzschluss oder unbekannter Zustand werden nicht aufgehoben)
2. Weiche auf Abzweig, danach auf Gerade (Impuls 150 ms); der Bediener gibt an, welche Weiche sich bewegt hat → 10836-Adressierung
3. POM-Zubehör: CV lesen (es wird nichts geschrieben)
4. Fahren auf der Stellvertreter-Adresse mit Fahrstufe 1 (höchstens 10) für 2 s, danach Fahrstufe 0 und Nothalt; der Bediener meldet, ob sich etwas bewegt hat und ob die Maus-Anzeige die Fahrstufe übernommen hat
5. Gleisspannung wieder ausschalten, falls das Programm sie eingeschaltet hat

## Grenzen

- Ergebnisse ohne Rückmeldung stehen als „unbekannt“, nie als in Ordnung. Punkte mit „an Hardware zu prüfen“ sind in ihrer Deutung nicht gesichert.
- Wird das Programm hart beendet (z. B. Fenster geschlossen, Gradle-Abbruch), läuft der Shutdown-Hook unter Umständen nicht. Die Z21 sendet dann die letzte Fahrstufe an die Stellvertreter-Adresse weiter – deshalb Fahrstufe klein halten und auf dieser Adresse keinen Decoder betreiben.

## Zusammenfassung – geklärt und offen

**Geklärt:** RailCom-Richtungsbits des 10808 zeigen die Aufgleisrichtung der Lok, nicht die Fahrtrichtung; die Z21 sendet trotz Abo keinen Lok-Broadcast bei Fremdsteuerung; Handregler auf Stellvertreter-Adresse per Abfrage lesbar (Verzögerung höchstens Abfrageabstand), Rückschreiben auf die Anzeige möglich; Weichenlage nach RCN-213 (P=1 gerade, P=0 abzweigend) und 10836-Adressierung bestätigt; POM-Lesen am Zubehördecoder über Decoderadresse (Weiche − 1) / 4; die Z21 bestätigt eigene Fahrbefehle mit einer Lokmeldung; Antwortzeiten 1–22 ms.

**Noch offen, an Hardware zu prüfen:** R-Bus-Modulzählung (Versatz um ein Modul, R-Bus wird deshalb nicht genutzt), genaue Antwortzeit des Echos auf Fahrbefehle (Grundlage für die Budget-Nachführung, Entscheidung 27), Zusammenspiel der WLAN-Maus mit dem RailCore-LAN-Server (noch nicht gebaut), Booster 10807 über CAN (siehe unten).

### Booster 10807: einzeln schaltbar, Diagnose über CAN (Entscheidung 46, Option C)

**Laut Dokumentation** (Z21-LAN-Protokoll-Spezifikation V1.13 vom 06.11.2023, Kap. 2.16, 10.2 und 11.2; [z21.eu, englische Fassung](https://www.z21.eu/media/Kwc_Basic_DownloadTag_Component/root-en-main_47-1652-959-downloadTag-download/default/d559b9cf/1558675126/z21-lan-protokoll-en.pdf)):

- Die Booster 10806, 10807 und 10869 sind am CAN-Bus eine **eigene Geräteklasse**, nicht die des 10808: eigene CAN-NetworkID 0xC101–0xC1FF (10808: Meldungen unter LAN_CAN_DETECTOR 0xC4), eigenes Broadcast-Flag 0x00020000 (10808: 0x00080000), eigene LAN-Header. Gilt nur, wenn der Booster **über CAN** an der Z21 hängt, nicht über B-Bus; Z21-Firmware ab 1.41 (unsere Z21: 1.43).
- **Diagnose über die Z21 (CAN):** LAN_CAN_BOOSTER_SYSTEMSTATE_CHGD (0xCA), etwa einmal pro Sekunde je Booster und Ausgang: NetworkID, Ausgang (1, beim 10807 auch 2), Zustandsbits (Bremsgenerator, Kurzschluss, Gleisspannung aus, Ausgang vom Nutzer abgeschaltet, RailCom-Lücke), **Spannung (mV) und Strom (mA) je Ausgang**. **Keine Temperatur** in dieser Meldung.
- **Einzeln schalten über die Z21 (CAN):** LAN_CAN_BOOSTER_SET_TRACKPOWER (0xCB) schaltet alle Ausgänge eines Boosters ab/zu; **je Ausgang** erst ab Z21-Firmware 1.42 **und Booster-Firmware 1.11**. Wiedereinschalten nur, wenn die Z21 selbst ein gültiges Gleissignal sendet; die Einstellung wird **nicht dauerhaft gespeichert** (ein abgeschalteter Ausgang ist nach einem Neustart also vermutlich wieder eingeschaltet – an Hardware zu prüfen).
- **Direkt am Booster über zLink** (10838 Z21 pro LINK, WLAN, eigene IP): LAN_BOOSTER_SYSTEMSTATE_GETDATA/_DATACHANGED (0xBB/0xBA) mit Strom, geglättetem Strom, **Temperatur je Verstärker**, Versorgungs- und Ausgangsspannung, Kurzschluss-, Übertemperatur- und Unterspannungsbits; LAN_BOOSTER_SET_POWER (0xB2) je Ausgang. Die Spezifikation empfiehlt dort möglichst nur einen Client je pro LINK (höchstens vier) und mindestens 50 ms Pause zwischen Anfragen.

**Umsetzung im Prüfprogramm:** Stufe 1 setzt das Flag 0x00020000 und meldet „Broadcast-Flag CAN-Booster“ sowie „CAN-Booster“ (empfangene Meldungen je NetworkID und Ausgang, zuletzt gemeldete Spannung, Strom, Zustandsbits). Ohne Meldung: „unbekannt“, nie „nicht vorhanden“. Geschaltet wird nichts. Im Betriebstreiber wird die Meldung nur dekodiert und **nicht ausgewertet** (Diagnosemeldung).

**Noch offen, an Hardware zu prüfen:**
- Hängen die beiden 10807 über CAN oder B-Bus an der Z21? Kommen 0xCA-Meldungen an (nächster passiver Lauf)?
- Welche NetworkID gehört zu welchem Booster, welcher Ausgang zu welchem Bezirk (ZS1/ZS2)? Grundlage für die Quelle der Bezirksmeldung (Entscheidung 40/E7).
- Booster-Firmware (ab 1.11 für einzelnes Schalten je Ausgang); stimmen Spannung, Strom und Zustandsbits mit der Wartungs-App überein?
- Einzelschalten (0xCB) erst nach eigener Freigabe durch den Nutzer und nur in Stufe 2 mit „JA“; noch nicht gebaut, Richtung aber bestätigt (Entscheidung 49) – wird umgesetzt, sobald Firmware-Voraussetzungen (Z21 ≥ 1.42, Booster ≥ 1.11) an der Hardware geprüft sind. Grundsätzlich gilt: Ein abgeschalteter Ausgang lässt Züge stehen, ist aber kein Nothalt-Ersatz, und weil die Abschaltung nicht gespeichert wird, darf RailCore nach einem Neustart von Booster, Z21 oder RailCore nie annehmen, dass ein Ausgang noch aus ist (Grundsatz 5).
- zLink/pro LINK (Temperatur): nur, falls ein pro LINK an einem Booster steckt; eigener Kanal neben der Z21, für Diagnosezwecke bestätigt (Entscheidung 49), noch nicht gebaut – zunächst nur lesend.

### RZTec Speedbox II (externes Messgerät zum Einmessen, Lastenheft Kap. 6)

**Laut Herstellerunterlagen** ([Bedienungsanleitung Speedbox II, Version 1.26, 05.08.2023](http://www.rztec.de/Downloads/SpeedboxManuals/Speedbox%20II%201.26%20de.pdf); [Download-Seite rztec.de](https://www.rztec.de/Downloads/)):

- Anschluss nur **USB**; am PC ein **virtueller COM-Port** über einen USB-Seriell-Wandler (FTDI oder CH340, je nach Gerät, Aufdruck auf der Verpackung). Strom ebenfalls über USB. Von Bluetooth oder WLAN ist in Anleitung und Download-Seite keine Rede.
- Misst die Geschwindigkeit vorbeifahrender Fahrzeuge (Anzeige km/h oder mph, auf den Maßstab umgerechnet) und die **Länge** von Lok oder Zug; der Maßstab wird von der Hersteller-Software eingestellt.
- Angebunden in TrainController Gold (ab 9), Win-Digipet (ab 2018-1) und iTrain (ab 5) jeweils als eigene Schnittstelle mit COM-Port – also **kein Teil der Zentrale**, sondern ein eigenes Gerät neben ihr.
- **Das serielle Protokoll (Baudrate, Befehle, Meldungsformat) ist nicht öffentlich dokumentiert**, weder in der Anleitung noch auf der Download-Seite. Bekannt aus der Anleitung ist nur, dass die Box sich beim Verbinden mit „PCBox“ meldet.

**Auf Eis gelegt (Entscheidung 49):** Ohne Protokollbeschreibung vom Hersteller (info@rztec.de) wird nichts angebunden (kein Mitschneiden und Nachbauen fremder Protokolle als Grundlage für Messwerte). Für RailCore wäre die Speedbox kein `CommandStation`-Adapter, sondern ein eigener Messgeräte-Adapter (nur lesend, kein Befehl an Loks), dessen Werte ausschließlich in das Einmessen fließen würden.

Details und Rohdaten je Lauf siehe unten.

## Ergebnisse an der Anlage

### 04.11.2026 – erster passiver Lauf
- Z21 schwarz (Variante 2013), Seriennummer 155703, Firmware 1.43; alle Broadcast-Flags übernommen; Antwortzeiten 1–22 ms.
- CAN-Detector 10808: Module 7–10 und 14–17 antworten. Module 1–6 und 11–13 sind noch nicht angeschlossen (Auskunft Nutzer).
- RailCom-Adressen 8888 und 8000 (Modul 14, Eingänge 2 und 5) sind echte Lokadressen – Adressdekodierung damit an Hardware bestätigt. Deutung der Richtungsbits (Rohwerte 2 und 3) weiter offen.
- **Offen, zurückgestellt: R-Bus-Versatz um ein Modul.** Die Z21 spiegelt die CAN-Belegung in die R-Bus-Daten; das Muster von CAN-Modul 14 erscheint als R-Bus-Modul 15, das von Modul 15 als Modul 16. R-Bus-Daten dürfen nirgends genutzt werden, bevor die Zählung geklärt und mit einem Test abgesichert ist.
- Beobachtung und WLAN-Maus nicht geprüft: Lauf ohne Konsole, die Beobachtung brach sofort ab (Fehler, behoben: geschlossene Eingabe beendet die Beobachtung nicht mehr). Nächster Lauf in einem eigenen PowerShell-Fenster.

### 04.11.2026 – zweiter passiver Lauf (90 s, eigene Konsole)
- Beobachtung lief die volle Zeit. 29 bestätigt, 2 unbekannt, keine Abweichung.
- Fahrbetrieb mit iTrain (LAN-Client der Z21): Lok 8800 vorwärts, dann rückwärts.
- **RailCom-Richtungsbits:** Von elf Loks melden 8888 und 7000 den Wert 2, alle anderen 3. 8888 und 7000 stehen nicht andersherum auf dem Gleis, ihre zuletzt eingestellte Fahrtrichtung war aber rückwärts (Anzeige in iTrain). Das spricht für **zuletzt eingestellte Fahrtrichtung**, nicht Aufgleisrichtung. Widerspruch: 8800 meldete nach dem Wechsel auf rückwärts weiter 3. Ungeklärt, ob nach dem Wechsel keine neue RailCom-Meldung kam, die Lok nicht mehr auf einem überwachten Abschnitt stand oder die Deutung anders ist. Das Prüfprogramm zeigt deshalb künftig den Verlauf der Bits je Lok, die letzte RailCom-Meldung je Abschnitt und eine Statistik aller empfangenen Datensätze samt Rohbeispielen der Lokmeldungen.
- **Lok-Broadcast:** Trotz angenommenem Abo (Flag 0x00010000) kam während der Fahrt keine einzige Lokmeldung. Ursache offen: Z21 sendet nicht an diesen Client, oder das Prüfprogramm erkennt die Meldung nicht (Rohpakete im Journal unter `logs/`, DEBUG). Fahrtrichtung laut Zentrale war damit nicht vergleichbar.
- **RailCom-Daten der Zentrale** (LAN_RAILCOM_DATACHANGED) kamen für 1000, 2000 und 6000.
- **RailCom-Adressen je Abschnitt:** Die Liste zeigt Adressen auch für inzwischen freie Abschnitte (6000 auf Modul 15/3) und zwei Adressen in einem Abschnitt (1000 und 8888 auf Modul 14/2). Zu klären, ob die Z21 alte Adressen hält oder das Prüfprogramm nur sammelt statt den letzten Stand zu zeigen. Für den Betrieb gilt: Eine Adresse ist einem Abschnitt nur zugeordnet, solange sie dort aktuell gemeldet wird.
- R-Bus-Versatz unverändert sichtbar (Modul 15 = 0xDF, Modul 16 = 0x04), weiter zurückgestellt.

### 04.11.2026 – dritter und vierter Lauf (mit Datensatz-Statistik)
- Dritter Lauf (120 s): 8800 mit iTrain von Modul 14/8 nach Modul 17/1 und zurück, Fahrtrichtung im Stand umgeschaltet. Vorher 8888 und 7000 in iTrain auf vorwärts gestellt.
- **Lok-Broadcast: Die Z21 sendet keine Lokmeldungen.** Statistik: genau ein Datensatz `0x40 X=0xEF` (Antwort auf die eigene Abfrage 9001). Die Dekodierung ist damit nicht die Ursache. Firmware 1.43 sendet trotz angenommenem Flag 0x00010000 keine Lokmeldungen, wenn ein anderer LAN-Client (iTrain) fährt. RailCore darf sich darauf nicht verlassen.
- **RailCom-Richtungsbits = Aufgleisrichtung (geklärt).** 8888 und 7000 meldeten nach Umstellen auf vorwärts weiter 2; 8800 blieb bei allen Richtungswechseln auf 3; 1000 (3) und 8888 (2) standen im selben Abschnitt. Vierter Lauf (30 s): 8888 um 180° umgedreht auf denselben Abschnitt gestellt → meldet jetzt 3. Die Bits zeigen also die elektrische Orientierung der Lok zur Schiene, nicht die Fahrtrichtung. Welcher Wert welcher Richtung im Gleisplan entspricht, ist noch festzulegen.
- **RailCom-Adresse bleibt nach Verlassen stehen.** Nach der Fahrt 17/1 → 14/8 kam für 17/1 keine RailCom-Meldung ohne Adresse; erst die Abfrage zu Beginn des nächsten Laufs zeigte den Abschnitt leer. Für den Betrieb: Eine RailCom-Adresse gilt nur, solange der Abschnitt belegt gemeldet ist.
- RailCom-Daten der Zentrale (LAN_RAILCOM_DATACHANGED) kamen nur für 1000, 2000 und 6000, nicht für die gefahrene 8800. Ursache offen, für den Betrieb derzeit ohne Bedeutung.
- Der 10808 meldet RailCom nur bei Änderung der Adresse im Abschnitt (82 Meldungen in 120 s, davon vermutlich 64 aus der Anfangsabfrage: 8 Module × 8 Eingänge).

### 04.11.2026 – fünfter und sechster Lauf (Wartungs-App, WLAN-Maus auf 9001)
- Fünfter Lauf (60 s): 8800 mit der Z21-Wartungs-App von Modul 15/8 in einen anderen Block und zurück nach Modul 15/8. Wieder **keine Lokmeldung** (nur die Antwort auf die eigene Abfrage 9001). Damit gilt für zwei verschiedene Clients (iTrain, Wartungs-App): Die Z21 meldet Fahrbefehle anderer Clients nicht weiter.
- Modul 14/2 war belegt (1000 und 8888), die letzte RailCom-Meldung des Abschnitts aber leer. Für den Betrieb ist „belegt, Adresse unbekannt“ ein eigener Zustand; er gilt nie als frei und nie als bestimmter Zug.
- Sechster Lauf (60 s): WLAN-Maus an der Z21 auf Stellvertreter-Adresse 9001 gefahren. Das Prüfprogramm hatte 9001 zu Beginn per Lokinfo abgefragt (Einzel-Abo). **Trotzdem keine Lokmeldung**, Verbindung dabei einwandfrei (Status, Systemzustand und CAN-Meldungen laufend). Die Z21 meldet also auch abonnierte Adressen nicht, wenn ein Handregler sie fährt.
- Folge: Das Stellvertreter-Konzept für Handregler an der Z21 funktioniert nicht über Meldungen der Z21. Das Prüfprogramm fragt die Stellvertreter-Adresse deshalb jetzt regelmäßig ab; ein weiterer Lauf mit der WLAN-Maus auf 9001 soll zeigen, ob die Antwort den Zustand des Handreglers enthält und mit welcher Verzögerung.

### 04.11.2026 – siebter Lauf (WLAN-Maus auf 9001, Abfrage alle 250 ms)
- **Abfrage funktioniert:** 240 von 240 Lokinfo-Abfragen beantwortet. Jede Änderung der Maus war in der nächsten Antwort sichtbar: Fahrstufe 1–10 und zurück in beiden Richtungen, Richtungswechsel, F0 ein/aus. Verzögerung höchstens Abfrageabstand (250 ms) plus Antwortzeit.
- Ohne eigene Abfrage kam wieder keine Lokmeldung.
- **Geklärt:** Handregler an der Z21 auf einer Stellvertreter-Adresse lassen sich durch regelmäßiges Abfragen verfolgen; Einzel-Abo und Lok-Broadcast liefern dafür nichts. Offen: Rückschreiben des begrenzten Werts auf die Anzeige des Handreglers (aktive Prüfung) und die Last bei mehreren Stellvertreter-Adressen.

### 04.11.2026 – aktive Stufe (drei Läufe, Weiche 15 an 10836 WD02, Adressen 9–16)
- **Weichenlage war vertauscht (behoben):** RailCore sendete „Gerade“ als P=0 und „Abzweig“ als P=1. An der Anlage stellte „Gerade“ die Weiche auf Abzweig, „Abzweig“ bewegte eine gerade stehende Weiche nicht; Z21-App und iTrain schalten richtig. Richtig ist RCN-213: **P=1 gerade, P=0 abzweigend**; die Rückmeldung ZZ=10 bedeutet gerade, ZZ=01 abzweigend. Nach der Korrektur stellte W015 in beide Richtungen richtig (Lauf 13:00).
- **10836-Adressierung geklärt:** Weichenadresse 15 (auf dem Draht 14) schaltet W015; Impuls 150 ms reicht.
- **POM-Zubehör geklärt:** CV 8 über Decoderadresse 3 = (Weiche − 1) / 4 gelesen, Antwort nach rund 70 ms: 161 (0xA1, NMRA-Herstellerkennung Roco/Modelleisenbahn GmbH). Es wurde nichts geschrieben.
- **Fahren auf 9001:** Die Z21 bestätigt den eigenen Fahrbefehl mit einer Lokmeldung (Echo vorhanden); Fahrstufe 0 und Nothalt kamen an, auf 9001 ist kein Decoder. Die Antwortzeit des Echos ist noch nicht gemessen – die Budget-Nachführung (Entscheidung 27) bleibt aus, bis sie gemessen ist.
- **Anzeige der WLAN-Maus:** Eine Maus an der Z21 auf 9001 zeigt die von RailCore gesendete Fahrstufe – Rückschreiben eines begrenzten Werts auf die Anzeige ist möglich.
- R-Bus weiter um ein Modul versetzt (CAN 14 → R-Bus 15, 15 → 16, 16 → 17), weiter zurückgestellt.
- Bedienung: Die Bestätigung verlangt genau `JA`; „Ja“ bricht ab (so gewollt).
