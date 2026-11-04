# 5. Fahrmanagement und Reservierung

> Quelle: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), Stand 03.11.2026. Maßgeblich ist das Dokument in claude.ai; Änderungen dort planen und hier nachziehen.

Blöcke, Meldeabschnitte, Weichen und Fahrstraßen sind **Ressourcen**. Ein Zug erhält eine Reservierung über mehrere Ressourcen; freigegeben wird jede erst, wenn der Zug sie mit seiner ganzen Länge verlassen hat und der Meldeabschnitt frei meldet.

| Funktion | Regel |
| --- | --- |
| Reservierung | atomar: alle Ressourcen eines Fahrwegstücks oder keine; Weichen werden erst nach erfolgreicher Reservierung gestellt |
| Fahrstraßenverriegelung | Weichen einer reservierten Fahrstraße sind gegen Umstellen gesperrt, auch gegen Handbefehle |
| Teilfreigabe | Abschnitte hinter dem Zugschluss werden einzeln freigegeben, sobald sie verlassen sind |
| Flankenschutz | Weichen, die seitlich in die Fahrstraße führen könnten, werden in Schutzlage gestellt und mitreserviert |
| Durchrutschweg | hinter einem Halt zeigenden Signal bleibt eine Schutzstrecke frei (Standard 150 mm, je Signal einstellbar) |
| Gegenfahrtschutz | eingleisige Abschnitte werden als Ganzes mit Richtung reserviert; Gegenzug wartet vor dem Abschnitt |
| Bremsweg-Reservierung | reserviert ist immer mindestens der Bremsweg plus Sicherheitsabstand plus Positionsunsicherheit |
| Deadlock-Erkennung | Wartegraph über alle Reservierungswünsche; Zyklus erkannt → Zug mit niedrigster Priorität weicht aus oder wartet; vorbeugend keine Einfahrt in Sackgassen ohne Ausweg |
| Prioritäten | P1 Fernverkehr … P6 Rangierfahrt; bei Konflikt erhält die höhere Priorität die Ressource, gleiche Priorität: wer länger wartet |
| Umleitung | ist der Regelweg gesperrt oder lange belegt, sucht die Wegsuche einen freien Alternativweg mit zulässiger Zugart und Länge |
| Fahrwegoptimierung | Kosten je Weg aus Länge, Geschwindigkeit, erwarteter Wartezeit und Konflikten mit höher priorisierten Zügen |
| Warteschlangen und Fairness | Fahrwünsche je Ressource in einer Warteschlange; die Priorität eines wartenden Zuges steigt mit der Wartezeit, damit kein Zug dauerhaft verdrängt wird |

**Wegsuche** läuft auf dem Gleisgraph (kürzester Weg mit Kosten, z. B. A\* oder Dijkstra) und beachtet erlaubte Richtungen, Zugarten, Zuglänge gegen Blocklänge sowie Sperren.

**Schattenbahnhof:** Gleiswahl nach Zuglänge und Zugart; fährt der vordere Zug aus, rücken die hinteren Blöcke nach (Reservierung jeweils nur bis zum nächsten Block). **Drehscheibe:** Bühne und Zufährt werden gemeinsam reserviert, gedreht wird nur bei freier Bühne oder Zug in Endlage.

**Züge länger als Blöcke:** Ein Zug darf Blöcke durchfahren, die kürzer sind als er selbst. Reserviert und belegt sind dann alle Blöcke, Weichen und Abschnitte unter dem Zug von Spitze bis Zugschluss. Halten darf ein Zug nur dort, wo der Haltebereich lang genug ist; ein Haltebereich kann aus mehreren hintereinanderliegenden Blöcken bestehen (z. B. ein Schattenbahnhofsgleis aus drei Blöcken für einen langen Zug, ohne Nachrücken). Die Zuglängenprüfung gilt daher für Haltebereiche, nicht für einzelne Blöcke.

**Rangieren und Einfahrt in besetzte Blöcke:** Eine Rangierfahrt erhält eine eigene Reservierungsart, die einen bereits besetzten Block mitbenutzen darf. Regeln:

- nur mit Rangierauftrag (Bediener, Aktion oder Zugbildung) und nur in Blöcke, die für Rangieren freigegeben sind; Zugfahrten in besetzte Blöcke bleiben verboten
- Rangiergeschwindigkeit höchstens 25 km/h (einstellbar), in der Nähe des stehenden Fahrzeugs Schrittgeschwindigkeit
- Halt in Kuppelabstand (Standard 10 mm) vor dem stehenden Fahrzeug, berechnet aus beiden Positionen inklusive Unsicherheit; reicht die Genauigkeit nicht, hält der Zug früher und der Bediener fährt die letzten Zentimeter von Hand
- Kuppeln: Zugverband wird zusammengeführt (Länge, Profil, Reservierung); Abkuppeln an definierter Stelle, optional mit Digitalkupplung; danach zwei getrennte Züge mit eigenen Positionen
- Rangierbereiche und Rangiersignale (Sh0/Sh1) begrenzen, wo rangiert werden darf; der Durchrutschweg entfällt im Rangierbereich, der Flankenschutz bleibt

**Überholungen und Kreuzungen:** Der Betriebsmanager plant vorausschauend, nicht erst beim Konflikt.

- Er berechnet für jeden Zug die voraussichtliche Ankunft an den nächsten Bahnhöfen (aus Position, Geschwindigkeit und Fahrweg).
- Holt ein Zug höherer Priorität einen langsameren vor dem nächsten Überholbahnhof ein, wird der langsamere in ein Überholgleis geleitet und wartet dort.
- Beispiel: Güterzug (P5) wartet im Bahnhofsgleis, Railjet (P1) fährt durch; der Güterzug fährt erst aus, wenn der Railjet den nächsten Block verlassen hat und ein Mindestabstand eingehalten ist.
- Auf eingleisigen Strecken gilt dasselbe für Kreuzungen: Der Zug mit niedrigerer Priorität wartet im Kreuzungsbahnhof.
- Je Bahnhof konfigurierbar: welche Gleise Überhol- oder Kreuzungsgleise sind, für welche Zugarten und Längen, Mindestabstand der Prioritäten für eine Überholung (Standard 2 Stufen), Höchstwartezeit, nach der ein Zug trotzdem fährt, wenn der Weg frei ist.
- Fahrplanzüge: Verspätungen verschieben die Planung; ein pünktlicher Zug höherer Priorität hat Vorrang vor einem verspäteten.

**Fahren mit Zielvorgabe:** Der Bediener zieht einen Zug im Gleisbild auf den Wunschblock (oder wählt einen Bahnhof). Die Steuerung sucht den Weg, reserviert ihn schrittweise, stellt Weichen und Signale und fährt den Zug mit Trägheit bis zum Haltepunkt. Ist das Ziel besetzt oder zu kurz, wird das vor der Abfahrt gemeldet; als Rangierfahrt ist die Einfahrt in einen besetzten Block nach den Rangierregeln möglich. Dieselbe Zielvorgabe geht auch über Aktionen, Fahrplan und Netzwerk-API.

**Heimatgleise, Betriebsschluss und Betriebsbeginn**

- Jeder Zugverband kann ein oder mehrere **Heimatgleise** haben (z. B. ein Schattenbahnhofsgleis oder Abstellgleis), mit Reihenfolge der Bevorzugung.
- **Betriebsschluss** auf Knopfdruck oder per Modellzeit: Neue Fahrten werden nicht mehr gestartet, laufende Züge beenden ihre Fahrt und fahren dann in ihr Heimatgleis; ist es belegt oder fehlt eines, in ein freies, passendes Bahnhofs- oder Abstellgleis (Zuglänge, Zugart). Danach Prüfung, dass alle Züge stehen, Zustand sichern, auf Wunsch Gleisspannung aus.
- **Betriebsbeginn:** Zugerkennung, Prüfung und Freigabe (siehe Wiederanlauf), dann setzen die Züge aus ihren Heimatgleisen den Betrieb nach Fahrplan oder Automatik fort.
- Ein Zug, der seinen Platz nicht erreicht (Weg dauerhaft blockiert), wird dem Bediener gemeldet und bleibt sicher an der letzten erreichbaren Stelle stehen.
