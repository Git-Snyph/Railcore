# Befehlsplaner

> Status: Entwurf

Der Befehlsplaner (`control.CommandScheduler`) gibt alle Befehle an die Zentrale – nach Vorrang und innerhalb des DCC-Budgets. Er läuft im Steuerungs-Thread und nutzt dessen Zeitplan (Entscheidungen 27, 28, 30, 32). Nothalt läuft an ihm vorbei und geht sofort hinaus.

## Vorrangklassen

Nothalt > Sicherheit > Automatik > Fahrplan > Hand > Komfort (`CommandPriority`).

| Befehl | Klasse |
| --- | --- |
| Halt, Eingriff der Überwachung (verlangt weniger als die zuletzt **gesendete** Stufe) | Sicherheit |
| Planmäßige Bremsstufe der Halteplanung | Automatik, vor Anfahren und Beschleunigen; wartet sie länger als `escalateAfterMillis` (Standard 150 ms), wird sie zur Sicherheit aufgestuft |
| Anfahren, Beschleunigen | Automatik |
| Funktionen, Komfort | Komfort |

Innerhalb einer Klasse: Halt vor Absenkung vor Erhöhung, dann älteste zuerst. In der Sicherheitsklasse stehen echte Sicherheitsbefehle vor aufgestuften Bremsstufen.

## Regeln

- **Zusammenfassen:** Je Lok zählt nur der neueste Wert; unveränderte Fahrstufen werden nie gesendet.
- **Halte** dürfen das Budget um 2 Befehle überziehen.
- **Nach dem Neuverbinden** erhält jede bekannte Lok einmal Halt, nie eine Fahrstufe.
- **Weichenbefehle** werden nie unterdrückt (Weichen melden keine Endlage); solange sie warten, werden sie zusammengefasst.
- **Versatz im Takt:** Jeder Zug rechnet mit einem festen Versatz im 50-ms-Takt (aus Adresse · φ⁻¹), damit nicht alle Züge im selben Augenblick senden. Brems- und Haltebefehle liegen auf exakt berechneten Zeitpunkten, nicht auf dem Takt.

## DCC-Budget

- Token-Bucket, Größe 4 (einstellbar). Ein größerer Bucket würde den Stau in die Z21 verlagern, wo der Vorrang nicht mehr wirkt.
- Budget aus einem Rechenmodell: rund 167 DCC-Pakete/s Gleiskapazität, 10 % Reserve, abzüglich ein Auffrischpaket je aktiver Lok, geteilt durch 2. Ergibt etwa 75 / 70 / 62,5 / 50 / 25 Befehle/s bei 0 / 10 / 25 / 50 / 100 aktiven Loks. Alle Startwerte stehen in `SchedulerConfig`.
- Nachführung aus der gemessenen Antwortzeit (`BudgetCorrection`) ist vorbereitet, aber abgeschaltet, bis die aktive Prüfstufe das Echo der Z21 an der Hardware bestätigt hat.

## Überlast

Reicht das Budget nicht für die Bremskurven, wird das erkannt, gemeldet und im Journal protokolliert; Komfort wird zurückgestellt. Die Überlastmeldung wertet die Wartezeit der ganzen Sicherheitsklasse einschließlich aufgestufter Bremsstufen. Züge auszubremsen oder langsamer fahren zu lassen folgt mit dem Fahrmanagement (Meilenstein 4).

## Kennzahlen

`SchedulerMetrics`: Wartezeit je Klasse (Sicherheit getrennt nach eigenen und aufgestuften Befehlen), Rückstau, gesendet, zusammengefasst, Aufstufungen, Überlast. `TrainDriver` zählt Eingriffe der Überwachung und Aufstufungen; die erste je Fahrt kommt ins Journal (`SUPERVISION_INTERVENTION`, `BRAKE_STEP_ESCALATED`).

## Lasttests im Simulator

`SchedulerLoadSimTest`, Wartezeit in ms (mittel / p95 / max), Freigabeende bei 2700 mm:

| Loks | Sicherheit (eigen) | Automatik | Halt ±10 mm | Zugspitze max |
| --- | --- | --- | --- | --- |
| 10 | 0 / 0 / 0 | 4 / 31 / 95 | 10/10 | 2505,9 mm |
| 25 | 0 / 0 / 0 | 22 / 67 / 768 | 25/25 | 2508,8 mm |
| 50 | 0 / 0 / 0 | 66 / 150 / 762 | 49/50 | 2511,4 mm |
| 100 | 4 / 24 / 69 | 203 / 6046 / 6046 | 100/100 | 2508,4 mm |

Die Werte stammen aus dem Simulator; an der echten Z21 sind Budget-Modell und Warteschlange der Zentrale noch nicht gemessen (Lastenheft Kapitel 10, „Befehlslast“).
