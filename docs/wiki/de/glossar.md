# Glossar

> Status: Entwurf

Verbindlich für Anleitung, Wiki, Oberfläche und Übersetzungen. Neue Begriffe hier zuerst eintragen.

| Deutsch | English | Bedeutung |
| --- | --- | --- |
| Abfahrvorlauf | departure lookahead | Wie viel reserviert sein muss, bevor ein stehender Zug abfährt; nie größer als der Reservierungsvorlauf |
| Abgleich | position correction | Setzen der gerechneten Position auf den Meldepunkt, wenn ein Melder anspricht |
| Annäherungstempo | approach speed | Langsames Tempo auf der letzten Strecke vor einem Bahnsteighalt (Standard 5 km/h Vorbild) |
| Aufgleisrichtung | track orientation | Wie die Lok elektrisch zur Schiene steht; das zeigen die RailCom-Richtungsbits des 10808, nicht die Fahrtrichtung |
| Automatik ohne Fahrplan | automatic operation without timetable | Regeln je Bahnhof und Zugart (Halt, Wartezeit, Richtungswechsel, Gleiswahl), die nach jedem Halt das nächste Ziel als Fahrauftrag erzeugen |
| Befehlsplaner | command scheduler | Teil der Steuerung, der alle Befehle nach Vorrang und DCC-Budget an die Zentrale gibt |
| Bezirk (Booster-Bezirk) | power district | Gleisbereich, den ein Booster bzw. ein Ausgang der Zentrale mit Fahrstrom versorgt; Kurzschluss schaltet nur ihn ab |
| Bremskurve | braking curve | Höchste zulässige Geschwindigkeit an jeder Stelle vor einem Ziel |
| DCC-Budget | DCC budget | Zahl der Befehle pro Sekunde, die das Gleis aufnehmen kann |
| Durchrutschweg | overlap | Gesicherte Strecke hinter einem Halt für den Fall, dass ein Zug zu weit fährt |
| Einmessen | calibration | Messen von Geschwindigkeit je Fahrstufe, Reaktionszeit und Auslaufweg einer Lok |
| Fahrauftrag | movement order | Einziger Auftrag, den das Fahrmanagement ausführt: Zug, Ziel, Halteposition, Betriebsart |
| Fahrweg (im Gleisgraph) | track path | Folge befahrener Kanten mit Fahrrichtung; bildet die Wegkoordinate in mm auf einen Ort im Gleisgraph ab |
| Freigabe (Ende der Freigabe) | movement authority (end of authority) | Strecke, die ein Zug befahren darf; dahinter muss er stehen |
| Gleisgraph | track graph | Knoten und Kanten mit Längen in mm, aus den verlegten Gleisstücken berechnet; Grundlage für Wegsuche und Position |
| Gleisstück | track piece | Physisches Gleisteil mit Geometrie (gerade, Bogen, Weiche, Kreuzung, Doppelkreuzungsweiche) aus der Gleisstückbibliothek |
| Grenzzeichen | clearance point | Stelle auf einem Weichenschenkel, ab der ein Fahrzeug den anderen Schenkel nicht mehr behindert; Pflichtangabe je Schenkel |
| Haltepunkt | stopping point | Stelle, an der die Zugspitze halten soll |
| Hand mit Fahrweg | manual with route | Betriebsart: Bediener bestimmt das Tempo, die Steuerung reserviert den Fahrweg aus einem Fahrauftrag und stellt Weichen und Signale |
| Journal | journal | Protokoll aller Zustandsänderungen, Eingriffe und Störungen |
| kritischer Abschnitt | critical section | Aus der Topologie abgeleiteter oder von Hand gekennzeichneter Block, der nur zusammen mit einem vollständigen Haltebereich reserviert wird, nie teilweise |
| Meldeabschnitt | detection section | Gleisabschnitt, dessen Belegung ein Rückmelder meldet |
| Meldepunkt | detection point | Stelle, an der ein Melder anspricht und die Position abgeglichen wird |
| Melderabfrage | detector status query | Abfrage aller Melderzustände bei der Zentrale, auch unveränderter; Grundlage für das Erkennen stummer Melder |
| Modellzeit | model time | Eigene, aus der `TimeSource` fortgeschriebene Uhr für Fahrplan, Aktionen und Betriebsbeginn/-schluss; nie Grundlage für Sicherheitsrechnungen |
| Nothalt | emergency stop | Sofortiger Halt, wird nie automatisch aufgehoben |
| Position unsicher (Zug) | position unsafe (train) | Kennzeichnung nach Kurzschluss oder Spannungsausfall: der Zug darf nur bremsen und halten, bis der Bediener sie aufhebt |
| Positionsunsicherheit | position uncertainty | Wie weit die gerechnete Position von der wahren abweichen kann; wächst mit der Strecke seit dem letzten Abgleich |
| Quittieren | acknowledge | Bedienhandlung, die einen Nothalt aufhebt und zum Wiederanlauf führt |
| Reservierung | reservation | Zuteilung von Abschnitten an genau einen Zug |
| Reservierungsvorlauf | reservation lookahead | Wie viele Blöcke über die Bremsweg-Reservierung hinaus angefordert werden; Unterschreiten verhindert die Fahrt nicht |
| Rückmelder, Melder | detector | Gerät, das Belegung (und mit RailCom die Lokadresse) meldet |
| Sicherheitsabstand, größerer (Zug) | increased safety margin (train) | Kennzeichnung, wenn eine Lok anders fuhr als gesendet; bleibt, bis der Bediener sie aufhebt |
| Software Motion Control | software motion control | Standard: Anfahren und Bremsen rechnet die Steuerung, der Decoder verzögert nicht (CV3/CV4 = 0) |
| Decoder Motion Control | decoder motion control | Ausnahme je Lok: der Decoder verzögert selbst |
| Stellvertreter-Adresse | proxy address | Adresse 9000–9999 für Handregler an der Z21; RailCore fährt daraus geprüft den echten Zug |
| Trennstelle | isolating joint | Stoß zweier Gleisstücke mit elektrischer Trennung, z. B. zwischen Meldeabschnitten |
| unbekannt (Melder) | unknown (detector) | Zustand nach dem Start, bis der Melder eine Meldung geschickt hat; gilt nie als frei |
| unbestätigt (Zug) | unconfirmed (train) | Zustand nach dem Start, bis die Position des Zuges bestätigt ist |
| Wiederanlauf | restart / recovery | Rückkehr in den Betrieb nach Nothalt oder Neustart |
| Zufallsquelle | random source | Austauschbare, protokollierte Quelle für Zufallsentscheidungen (wie `TimeSource`), damit Blackbox-Wiedergabe und Simulator dasselbe Ergebnis liefern |
| Zugausdehnung | train extent | Belegung von der Zugspitze bis zum Zugschluss: Kanten, Meldeabschnitte, Blöcke, Weichen und Bezirke |
| Zugbeeinflussung | train protection | Überwachung, die Fahrbefehle begrenzt oder den Zug hält |
| Zuglauf | train run (template) | Wiederverwendbare Vorlage aus Einträgen (Block/Bahnhof, Richtung, Halt, Aktionen), die nacheinander Fahraufträge erzeugt |
| Zugverband | consist | Lok mit Wagen, gemeinsam eingemessen |
