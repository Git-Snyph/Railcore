# Glossar

> Status: Entwurf

Verbindlich für Anleitung, Wiki, Oberfläche und Übersetzungen. Neue Begriffe hier zuerst eintragen.

| Deutsch | English | Bedeutung |
| --- | --- | --- |
| Abgleich | position correction | Setzen der gerechneten Position auf den Meldepunkt, wenn ein Melder anspricht |
| Annäherungstempo | approach speed | Langsames Tempo auf der letzten Strecke vor einem Bahnsteighalt (Standard 5 km/h Vorbild) |
| Aufgleisrichtung | track orientation | Wie die Lok elektrisch zur Schiene steht; das zeigen die RailCom-Richtungsbits des 10808, nicht die Fahrtrichtung |
| Befehlsplaner | command scheduler | Teil der Steuerung, der alle Befehle nach Vorrang und DCC-Budget an die Zentrale gibt |
| Bremskurve | braking curve | Höchste zulässige Geschwindigkeit an jeder Stelle vor einem Ziel |
| DCC-Budget | DCC budget | Zahl der Befehle pro Sekunde, die das Gleis aufnehmen kann |
| Durchrutschweg | overlap | Gesicherte Strecke hinter einem Halt für den Fall, dass ein Zug zu weit fährt |
| Einmessen | calibration | Messen von Geschwindigkeit je Fahrstufe, Reaktionszeit und Auslaufweg einer Lok |
| Freigabe (Ende der Freigabe) | movement authority (end of authority) | Strecke, die ein Zug befahren darf; dahinter muss er stehen |
| Haltepunkt | stopping point | Stelle, an der die Zugspitze halten soll |
| Journal | journal | Protokoll aller Zustandsänderungen, Eingriffe und Störungen |
| Meldeabschnitt | detection section | Gleisabschnitt, dessen Belegung ein Rückmelder meldet |
| Meldepunkt | detection point | Stelle, an der ein Melder anspricht und die Position abgeglichen wird |
| Nothalt | emergency stop | Sofortiger Halt, wird nie automatisch aufgehoben |
| Positionsunsicherheit | position uncertainty | Wie weit die gerechnete Position von der wahren abweichen kann; wächst mit der Strecke seit dem letzten Abgleich |
| Quittieren | acknowledge | Bedienhandlung, die einen Nothalt aufhebt und zum Wiederanlauf führt |
| Reservierung | reservation | Zuteilung von Abschnitten an genau einen Zug |
| Rückmelder, Melder | detector | Gerät, das Belegung (und mit RailCom die Lokadresse) meldet |
| Software Motion Control | software motion control | Standard: Anfahren und Bremsen rechnet die Steuerung, der Decoder verzögert nicht (CV3/CV4 = 0) |
| Decoder Motion Control | decoder motion control | Ausnahme je Lok: der Decoder verzögert selbst |
| Stellvertreter-Adresse | proxy address | Adresse 9000–9999 für Handregler an der Z21; RailCore fährt daraus geprüft den echten Zug |
| unbekannt (Melder) | unknown (detector) | Zustand nach dem Start, bis der Melder eine Meldung geschickt hat; gilt nie als frei |
| unbestätigt (Zug) | unconfirmed (train) | Zustand nach dem Start, bis die Position des Zuges bestätigt ist |
| Wiederanlauf | restart / recovery | Rückkehr in den Betrieb nach Nothalt oder Neustart |
| Zugbeeinflussung | train protection | Überwachung, die Fahrbefehle begrenzt oder den Zug hält |
| Zugverband | consist | Lok mit Wagen, gemeinsam eingemessen |
