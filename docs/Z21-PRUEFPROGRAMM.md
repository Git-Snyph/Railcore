# Z21-Prüfprogramm

Klärt an der echten Z21 die offenen Hardware-Fragen (Lastenheft Kap. 8) und erkennt die Fähigkeiten der Zentrale. Entscheidung 20.

## Start

```
gradlew z21Check --console=plain --args="--host 192.168.0.111"              # Stufe 1, passiv
gradlew z21Check --console=plain --args="--host 192.168.0.111 --dauer 120"  # längere Beobachtung
gradlew z21Check --console=plain --args="--host 192.168.0.111 --stufe aktiv"
gradlew z21Check --console=plain --args="--hilfe"                           # alle Optionen
```

Ergebnis: Prüfbericht `berichte/z21-pruefbericht-<Datum>-<Zeit>.txt`, vollständiges Journal mit allen Rohpaketen unter `logs/`.

## Stufe 1 – passiv (Standard)

Sendet nur Abfragen und Abos (Positivliste in `CommandClass`): Seriennummer, HW-Info, Firmware, Freischaltcode, Status, Systemzustand, Broadcast-Flags setzen und zurücklesen, R-Bus, CAN-Melder, Lokinfo der Stellvertreter-Adresse, Weicheninfo. Danach werden Melder-, RailCom- und Lokmeldungen mitgeschnitten; die Stellvertreter-Adresse wird dabei regelmäßig abgefragt (`--abfrage-ms`, Standard 250 ms, 0 = aus), weil die Z21 Handregler-Befehle nicht von sich aus meldet. Während der Beobachtung bietet es sich an,

- Melder zu belegen und Loks mit RailCom auf überwachte Abschnitte zu stellen (RailCom-Adressen und Richtungsbits),
- eine Lok mit einem Handregler zu bewegen (Lok-Broadcast, F29–F31-Byte),
- die Stellvertreter-Adresse an der WLAN-Maus aufzurufen – nur, wenn dort sicher kein Decoder ist.

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
