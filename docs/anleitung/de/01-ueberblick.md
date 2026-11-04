# 1. Überblick

> Status: Entwurf

RailCore steuert eine Modellbahn mit einer Roco Z21 und dem Rückmelder Z21 Detector 10808 (CAN, RailCom). Ziel sind bis zu 100 Züge, vollautomatisch oder von Hand, immer unter Zugbeeinflussung.

## Was RailCore anders macht

- **Wegbasierte Position:** Die Steuerung rechnet die Position jedes Zuges in Millimetern aus Fahrstufe, eingemessenem Profil und Zeit. Rückmelder sind Messpunkte, an denen die Rechnung abgeglichen wird – ein Zug hält am Bahnsteig, ohne dass dort ein eigener Brems- oder Haltemelder liegt.
- **Zielgenaues Halten:** Am Bahnsteig bremst der Zug zweistufig auf ein Annäherungstempo und hält dann. Wie genau ein Haltepunkt erreichbar ist, berechnet RailCore aus der Lage der Melder davor und zeigt es an.
- **Handbetrieb unter Aufsicht:** Auch wer von Hand fährt, fährt nur in reservierte Abschnitte.

## Sicherheitsgrundsätze

Diese Regeln gelten immer und lassen sich weder von Hand noch über Fahrplan oder Schnittstellen abschalten:

1. **Im Zweifel halt.** Fehlt eine Rückmeldung oder ist etwas unklar, hält der Zug.
2. **Keine Bewegung ohne Reservierung** – auch nicht im Handbetrieb.
3. **Nach einem Neustart ist nichts frei.** Melder gelten als „unbekannt“, Züge als „unbestätigt“, bis sie bestätigt sind.
4. **Ein Abschnitt wird erst frei**, wenn das berechnete Zugende ihn mit Sicherheitszuschlag verlassen hat.
5. **Nothalt** wird sofort gesendet und nie automatisch aufgehoben; Sie quittieren ihn selbst.
6. Jeder Eingriff und jede Störung steht im Journal.

## Aufbau dieser Anleitung

Die Kapitel folgen der Reihenfolge der Einrichtung: installieren, Z21 verbinden, Anlage anlegen, Fahrzeuge erfassen, einmessen, fahren. Nachschlagewissen (Decoder, Fehlerbilder, FAQ, Glossar) steht im [Wiki](../../wiki/de/Home.md).
