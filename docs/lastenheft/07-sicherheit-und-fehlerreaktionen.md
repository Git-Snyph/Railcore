# 7. Sicherheit und Fehlerreaktionen

> Maßgebliche Fassung des Lastenhefts. Lesbare Fassung mit Diagrammen: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), wird nach jeder Änderung hier nachgezogen.

Der Sicherheitsmanager prüft jeden Befehl vor dem Senden und überwacht laufend die Plausibilität. Jede Störung hat eine festgelegte Erkennung und Reaktion.

| Störung | Erkennung | Reaktion |
| --- | --- | --- |
| Verbindung zur Zentrale verspätet | 3 s ohne Lebenszeichen | Warnung, keine neuen Fahrfreigaben |
| Verbindung verloren | 5 s ohne Lebenszeichen | Zustand Störung; nach Wiederverbindung sofort Nothalt, Zustände neu einlesen, Freigabe durch Bediener |
| Geisterzug | Abschnitt belegt ohne erwarteten Zug | Nothalt im Umfeld (Standard) oder ganze Anlage (einstellbar), Abschnitt gesperrt |
| Ausbleibender Melder | Zug hätte Meldepunkt plus Unsicherheit überfahren | Zug halten, „Position unsicher“, Melder als verdächtig markieren |
| Melder bleibt belegt | Abschnitt belegt, obwohl Zug ihn laut Rechnung verlassen hat (Zeitgrenze) | Abschnitt bleibt gesperrt, Meldung „Wagen verloren oder Melder defekt“ |
| Flackernder Melder | Entprellung zählt Wechsel | ab Schwellwert Warnung, Diagnose zeigt Melder |
| RailCom-Widerspruch | andere Adresse als erwartet, oder dieselbe Adresse an zwei entfernten Stellen | Zug halten, Bediener entscheidet |
| Weichenlage passt nicht | Lagerückmeldung weicht nach Stellzeit ab | Fahrstraße nicht freigeben bzw. Zug vor der Weiche halten |
| Kurzschluss / Überlast | Meldung von Zentrale, Booster oder 10808 | Bezirk spannungslos (Hardware), alle Züge im Bezirk als unsicher, Automatik hält geordnet |
| Zug ohne Weg | Fahrweg endet (Prellbock, falsche Weiche) | Halt vor Ende der Freigabe; tritt nie ein, solange Reservierung korrekt |
| Handbefehl über Grenze | Wunschgeschwindigkeit über Bremskurve | auf erlaubten Wert begrenzt, Eingriff protokolliert |
| Interner Fehler | Ausnahme im Kern, Takt überschritten | Nothalt, Diagnosebericht |

**Handbetrieb mit WLAN-Maus über RailCore** *(Entscheidung 21; Zusammenspiel mit der Maus an Hardware zu prüfen)*

Die Z21 bietet keinen Weg, Befehle eines Handreglers vor der Ausführung abzufangen, und meldet Fahrbefehle anderer Clients nicht per Lok-Broadcast (Z21-Prüfprogramm, 04.11.2026). Deshalb verbindet sich die WLAN-Maus nicht mit der Z21, sondern mit RailCore: RailCore stellt einen Z21-kompatiblen LAN-Server (UDP 21105) bereit, die Ziel-IP der Maus zeigt auf den RailCore-Rechner.

1. Die Maus sendet den Fahrbefehl für die echte Lokadresse an RailCore.
2. RailCore prüft Wunschgeschwindigkeit und Funktionen gegen Bremskurve und Reservierung und sendet nur den erlaubten Wert über den Befehlsplaner an die Z21.
3. RailCore meldet der Maus den tatsächlich erlaubten Wert zurück, damit die Anzeige stimmt.

Die echte Lok erhält damit nie einen ungeprüften Handbefehl. Fällt RailCore aus, ist die Maus wirkungslos und der Zug steht (ausfallsicher); Not-Aus ohne PC über Hardware-Watchdog und Taster. Nachzubauen ist die Teilmenge des Z21-Protokolls, die die Maus nutzt: Anmeldung, Seriennummer, Version und HW-Info, Lokinfo, Fahren, Funktionen, Weichen, Stopp, Gleisspannung.

**Handregler an der Z21 über Stellvertreter-Adresse** *(Z21-App, X-Bus-Geräte, Zimo KLUG; Lesen geklärt, Rückschreiben an Hardware zu prüfen)*: Der Handregler fährt eine Adresse im Bereich 9000–9999, auf der garantiert kein Decoder sitzt; die Z21 führt den Befehl aus, es bewegt sich nichts. Weil die Z21 Handregler-Befehle weder per Lok-Broadcast noch an Abonnenten meldet, fragt RailCore die Stellvertreter-Adressen regelmäßig ab (z. B. alle 250 ms; im Z21-Prüfprogramm waren Fahrstufe, Richtung und Funktionen vollständig sichtbar), prüft den Wunsch, sendet nur den erlaubten Wert an die echte Lok und schreibt den begrenzten Wert auf die Stellvertreter-Adresse zurück. Der Bereich 9000–9999 ist ausschließlich dafür reserviert; die Prüfung meldet jeden echten Decoder darin als Fehler (Wagendecoder brauchen einen anderen Bereich). Wird die echte Adresse trotzdem direkt angesteuert, greift die Nachkorrektur mit größerem Sicherheitsabstand und einer Warnung.

**Freigabe nach Nothalt:** Ein Nothalt wird nie automatisch aufgehoben. Die Freigabe verlangt eine eigene Bedienhandlung, die angezeigte Ursache und die geprüften Zugpositionen; sie kann nicht über dieselbe Taste erfolgen, die den Nothalt ausgelöst hat.

**Befehlsverwaltung:** Alle Befehle laufen durch eine Warteschlange mit Prioritäten: 1 Nothalt, 2 Sicherheitsbremsung, 3 Automatik, 4 Fahrplan, 5 Handbetrieb, 6 Komfort (Licht, Sound). Ein niedrigerer Befehl überschreibt nie die Grenze eines höheren.

**Diagnose-Werkzeug**

- **Gleisplan prüfen** (vor jeder Aktivierung und auf Knopfdruck): Blöcke ohne Melder, doppelte Adressen, unerreichbare Knoten, Sackgassen ohne Ausweg, Fahrstraßen ohne Flankenschutz, Blöcke kürzer als die Züge, die sie anfahren sollen, Stellvertreter-Bereich 9000–9999 frei von echten Decodern, Melder und Decoder ohne Gerät in der Verkabelung.
- **Betrieb überwachen** (laufend): Verbindung und Antwortzeiten der Z21, Booster-Status, Melderstatistik (Flackern, Aussetzer, hängende Melder), Positionsabweichung je Zug beim Abgleich, Reservierungen und Wartezeiten, Auslastung des Steuerungs-Executors und der Gleis-Bandbreite. Grenzwerte lösen Hinweise oder Warnungen aus.
- Ergebnisse als Diagnoseansicht in der Oberfläche und als exportierbarer Bericht.

**Blackbox und Wiedergabe:** Alle Ereignisse, Befehle, Zugpositionen und Melderzustände werden fortlaufend mit Zeitstempel aufgezeichnet (Ringspeicher der letzten Stunden plus dauerhafte Sicherung bei jeder Störung). Die Aufzeichnung lässt sich im Live-Gleisbild abspielen, anhalten, verlangsamt und Schritt für Schritt ansehen. Bei einem Nothalt wird automatisch der Zeitraum davor gesichert. Dieselben Aufzeichnungen dienen als Testfälle für den Simulator.

**Hardware-Watchdog außerhalb des PCs** *(P1)*: Ein eigener Mikrocontroller im Netzwerk (z. B. ESP32) erhält von der Steuerung jede Sekunde ein Lebenszeichen. Bleibt es länger als 2 s aus (einstellbar), schaltet er zweistufig ab: zuerst „Gleisspannung aus“ direkt an die Z21, zusätzlich über ein Relais die Versorgung der Booster-Fahrstromausgänge (Niederspannungsseite). Wieder eingeschaltet wird nur von Hand nach Prüfung. Der interne Watchdog-Thread bleibt als erste Stufe bestehen.

**Kein physischer Not-Aus-Taster** *(entschieden, Entscheidung 36)*: Der Software-Nothalt über die Steuerung (LAN_X_SET_STOP, danach Gleisspannung aus an der Z21) reicht aus; ein zusätzlicher Taster am Hardware-Watchdog wird nicht gebaut. Der Hardware-Watchdog bleibt unverändert: Lebenszeichen-Ausfall schaltet zweistufig ab (siehe oben).

**Ausfall der Rückmelder-Versorgung:** Der 10808 wird aus der Gleisspannung versorgt und meldet ohne sie nichts. Deshalb: Ausfall eines einzelnen Melders und eines ganzen CAN-Bereichs erkennen; alle betroffenen Abschnitte auf „unbekannt“ setzen und den letzten gültigen Zustand mit Zeitstempel anzeigen; die Rückkehr der Versorgung gilt nie als Freimeldung, freigegeben wird erst nach erneuter Abfrage und Plausibilitätsprüfung.

**Zugschluss und Zugvollständigkeit** *(P1, längenbasiert)*: Grundlage ist die gepflegte Länge aller Fahrzeuge, Blöcke, Weichen und Fahrstraßen. Widerstandsachsen sind nicht verpflichtend, sondern ein Zusatznutzen.

- Die Steuerung berechnet aus Zugspitze und Zuglänge laufend, wo der Zugschluss ist. Ein Zug, der mit dem Ende noch im vorherigen Block oder in einer Weiche steht, belegt und blockiert diese, bis er sie vollständig verlassen hat.
- Freigegeben wird ein Abschnitt erst, wenn der berechnete Zugschluss ihn plus **Sicherheitszuschlag** verlassen hat: Positionsunsicherheit plus 5 % der Zuglänge, mindestens 50 mm (einstellbar).
- Ist der letzte Wagen stromaufnehmend (Widerstandsachse, Schlussbeleuchtung), muss zusätzlich die Freimeldung des Abschnitts kommen; widerspricht sie der Rechnung, hält der Zug („Wagen verloren?“).
- Je Wagen wird geführt: Länge über Puffer, stromführend ja/nein, Widerstandsachse.
- **Bewusst akzeptiertes Restrisiko:** Ohne stromaufnehmenden Wagen am Zugende erkennt die Steuerung einen abgerissenen Wagen nicht; er bleibt unbemerkt im Gleis. Ein Zug mit gepflegter Zugschluss-Erkennung wird in der Oberfläche entsprechend gekennzeichnet.

**Anlagen-Selbsttest und Inbetriebnahme** *(P1)*: Vor jeder Freigabe für den Fahrbetrieb prüft die Steuerung: Zentrale erreichbar, Firmware passend, Booster betriebsbereit, alle erwarteten CAN-Geräte vorhanden, Melder antworten, Weichen plausibel, Gleisplan gültig, keine widersprüchlichen Belegungen, alle Zugpositionen bestätigt, Hardware-Watchdog aktiv. Das Ergebnis erscheint als Ampel „Anlagenzustand“ auf Knopfdruck. Dazu kommen ein Inbetriebnahme-Assistent für die Ersteinrichtung, die Erkennung neuer CAN-Geräte und unbekannter Adressen, ein Abgleich von Soll-Verkabelung und Geräteinventar, eine **Lernfahrt** (ein Zug fährt ab, die Steuerung prüft die Reihenfolge der Melder und findet vertauschte Kabel), ein Wartungsmodus zum Sperren einzelner Bereiche und ein Testfahrmodus für einzelne Züge ohne Vollautomatik.

**Schaltartikel beim Verbinden in Grundstellung:** Eine physische Weichenlage-Rückmeldung ist möglich (Endlagenkontakte an einem Rückmeldemodul), aber nicht verpflichtend. Standard: Beim Verbinden mit der Zentrale schaltet die Steuerung alle Weichen, Signale und Schaltartikel einmal in ihre festgelegte Grundstellung; ab dann gilt die gespeicherte Lage als bekannt („angefordert“). Regeln:

- Weichen in belegten oder „unbekannten“ Abschnitten und unter einem bekannten Zug werden **nicht** geschaltet, sondern als „Lage ungeprüft“ markiert und erst gestellt, wenn sie frei sind.
- Geschaltet wird nacheinander mit Pause (Standard 200 ms), damit Decoder und Booster nicht überlastet werden.
- Signale gehen auf Halt, bevor irgendeine Weiche geschaltet wird.
