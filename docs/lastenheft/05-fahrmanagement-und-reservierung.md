# 5. Fahrmanagement und Reservierung

> Maßgebliche Fassung des Lastenhefts. Lesbare Fassung mit Diagrammen: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), wird nach jeder Änderung hier nachgezogen.

Blöcke, Meldeabschnitte, Weichen und Fahrstraßen sind **Ressourcen**. Ein Zug erhält eine Reservierung über mehrere Ressourcen; freigegeben wird jede erst, wenn der Zug sie mit seiner ganzen Länge verlassen hat **und** der Meldeabschnitt frei meldet (Standard). Eine Freigabe nur nach der Rechnung, ohne Freimeldung, ist ein eigens im Menü ausgewähltes, nicht standardmäßiges Verhalten (Entscheidung 47) und wird dort begründet.

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

## Betriebskonzept

Dieser Abschnitt legt fest, **wer** einem Zug sagt, wohin er fährt, und wie sich Zuglauf, Automatik ohne Fahrplan, Fahrplan, Handbetrieb und Aktionen dazu verhalten. Für alle gilt dieselbe Sicherheitsschicht dieses Kapitels (Reservierung, Verriegelung, Bremskurve); keine der folgenden Ebenen kann sie umgehen oder abschwächen (Grundsatz 4).

**Abgrenzung:** Geisterzug-Erkennung, ausbleibender Melder, unerwarteter Melder in einer Reservierung und RailCom-Widerspruch (Kapitel 7) sind immer aktiv; einstellbar ist nur der Umfang der Reaktion, nie ob reagiert wird. Freigegeben wird standardmäßig immer erst nach berechnetem Zugschluss plus Zuschlag **und** Freimeldung (Entscheidung 40/E8); eine Freigabe nur nach der Rechnung lässt sich im Menü einstellen, ist aber nicht die Voreinstellung und wird protokolliert und angezeigt, solange sie aktiv ist (Entscheidung 47).

### 1. Ebenen: Fahrauftrag, Zuglauf, Automatik ohne Fahrplan, Fahrplan

| Ebene | Was sie ist | Datenbereich (Kap. 9) | Erzeugt |
| --- | --- | --- | --- |
| **Fahrauftrag** | der einzige Auftrag, den das Fahrmanagement ausführt: Zug, Ziel (Block, Haltebereich oder Bahnhof), Halteposition, Betriebsart (Abschnitt 5), optional Zwischenziele, Wendeerlaubnis, Rangieranteil | Betriebszustand | Reservierungen, Weichen- und Signalbefehle, Fahrstufen |
| **Zuglauf** | wiederverwendbare Vorlage: Folge von Einträgen (Block, Blockauswahl oder Bahnhof) mit Fahrtrichtung, Halt und Wartezeit, Halteposition, Gleiswahl-Strategie, Rangieranteilen, Aktionen bei Einfahrt, Halt und Abfahrt, Start- und Endmarke mit Wiederholungszahl | Konfiguration | nacheinander Fahraufträge |
| **Automatik ohne Fahrplan** | Regeln je Bahnhof und Zugart: darf halten / nur durchfahren / verboten, Haltewahrscheinlichkeit, Wartezeit min–max, Wahrscheinlichkeit eines Richtungswechsels, Gleiswahl-Strategie | Konfiguration (Regeln), Betriebszustand (aktuelle Wahl) | nach jedem Halt das nächste Ziel als Fahrauftrag |
| **Fahrplan** | Fahrten mit Zugnummer, Modellzeiten (Ankunft, Abfahrt, Mindesthaltezeit), Zugart und Priorität; verweist je Fahrt auf einen Zuglauf (Weg und Halte) und ergänzt nur Zeiten, Zugnummer und Priorität; Umläufe verbinden Fahrten zu einem Fahrzeugeinsatz | Konfiguration | startet zur Modellzeit Zugläufe bzw. Fahraufträge, führt Soll/Ist |
| **Zielvorgabe** (Bediener, API, Aktion) | ein einzelner Fahrauftrag ohne Vorlage (wie bisher „Fahren mit Zielvorgabe“) | – | einen Fahrauftrag |

Regeln:

- Ein Zug hat höchstens **einen aktiven Fahrauftrag**. Er stammt genau aus einer Quelle (Zuglauf, Automatik ohne Fahrplan, Fahrplan, Zielvorgabe oder Bediener). Die Quelle steht im Journal.
- Die Ebenen bauen aufeinander auf und tauschen nur Fahraufträge aus. Fahrplan oder Automatik stellen nie selbst Weichen, Signale oder Fahrstufen.
- **Gleiswahl-Strategien** gelten für Zuglauf, Automatik und Bahnhof gleichermaßen: in Reihenfolge, zufällig, kürzestes passendes Gleis (Zuglänge gegen Haltebereich), bevorzugte Richtung, kürzester Weg, erstes zugelassenes. Ein Gleis, das für die Zuglänge, Zugart, Traktion oder Spurweite nicht zugelassen ist, wird nie gewählt.
- **Schattenbahnhof:** Ein Schwellwert N je Bahnhof legt fest, dass ein Zug erst ausfahren darf, wenn danach höchstens N Gleise frei sind (N = 1: „einer rein, einer raus“). Bahnhofszustände sind „Normal“, „Endstation“ (keine Ausfahrten mehr; Baustein für den Betriebsschluss) und „Nur Durchfahrt“. Ein Zug, der bereits mit diesem Bahnhof als Ziel unterwegs ist, behält sein Ziel.
- **Zufall ist reproduzierbar:** Wahrscheinlichkeiten und Zufallswartezeiten nutzen eine eigene, austauschbare Zufallsquelle mit protokolliertem Startwert (wie `TimeSource`, siehe Kapitel 2). Jede Zufallsentscheidung steht im Journal, damit Blackbox-Wiedergabe und Simulator dasselbe Ergebnis liefern (Kapitel 10, „Wiedergabe“).
- Läuft ein Zug nach Fahrplan, haben dessen Vorgaben Vorrang vor seinem Zuglauf. Ein Zug ohne Fahrplan und ohne Zuglauf fährt nur, wenn ihm Automatik ohne Fahrplan zugewiesen ist.

### 2. Reservierungsvorlauf und Abfahrvorlauf

Die Bremsweg-Reservierung bleibt die **Mindestgrenze**: Bremsweg plus Sicherheitsabstand plus Positionsunsicherheit. Sie lässt sich nicht verkleinern. Darüber hinaus gilt:

| Begriff | Regel |
| --- | --- |
| **Reservierungsvorlauf** | wie viele Blöcke über die Mindestgrenze hinaus das Fahrmanagement für einen fahrenden Zug **anzufordern versucht**; Standard-Anzahl je Zugart, im Zuglauf und im Fahrplan überschreibbar. Wird der Vorlauf nicht erreicht, fährt der Zug trotzdem, solange die Mindestgrenze erfüllt ist; seine Bremskurve endet am Ende der tatsächlich gewährten Reservierung. |
| **Abfahrvorlauf** | wie viel reserviert sein muss, bevor ein stehender Zug abfährt (Zugzustand „Reserviert“ → „Beschleunigt“). Standard: bis zum nächsten Haltebereich, in dem der Zug vollständig Platz hat, mindestens aber die Strecke, um auf die zulässige Geschwindigkeit zu beschleunigen und wieder zu bremsen. Er ist nie größer als der Reservierungsvorlauf. Verhindert, dass ein Zug den Bahnhof verlässt und vor dem nächsten Signal wieder hält. |
| **Vorlauf während eines Halts** | ein haltender Zug fordert normalerweise erst nach Ablauf seiner Wartezeit neu an. Für Zugarten oder Fahrten mit dieser Einstellung beginnt die Anforderung schon eine einstellbare Zeit vor der Abfahrt (Standard 30 s Modellzeit), höchstens bis zum Abfahrvorlauf. |
| Fairness | Anforderungen aus dem Vorlauf stehen in denselben Warteschlangen und im selben Wartegraph wie alle anderen; vergebene Ressourcen werden nicht entzogen (Abschnitt „Prioritäten“). |

### 3. Deadlock-Vermeidung in Schichten

Zwei Schichten ergänzen sich; keine ersetzt die andere.

1. **Vorbeugende Regeln aus dem Gleisplan (statisch):**
   - keine Einfahrt in Sackgassen ohne Ausweg;
   - Gegenfahrtschutz für eingleisige Abschnitte (als Ganzes mit Richtung reserviert; Folgezüge in derselben Richtung zugelassen);
   - **kritische Abschnitte:** Aus der Topologie abgeleitet, von Hand ergänz- und abwählbar, mit Prüfbericht (wie Flanken- und Gegenfahrtschutz, Entscheidung 40/E5). Ein als kritisch erkannter oder gekennzeichneter Block (z. B. die eingleisige Zufahrt zu einem Kopfbahnhof) wird nur zusammen mit den folgenden Ressourcen bis zu einem Haltebereich reserviert, in dem der Zug vollständig Platz hat – in **einer atomaren Reservierung** oder gar nicht. Je Zuglauf-Eintrag lässt sich ein Block zusätzlich als kritisch kennzeichnen.
2. **Wartegraph (dynamisch, immer aktiv):** erkennt Zyklen über alle Anforderungen, auch solche aus kritischen Abschnitten, und löst sie wie im Abschnitt „Deadlock-Erkennung“ beschrieben auf.

Löst der Wartegraph einen Zyklus auf, der durch eine fehlende Kennzeichnung entstanden ist, schlägt die Gleisplan-Prüfung die betroffenen Blöcke als kritisch vor (Hinweis, kein Fehler). Abnahme Meilenstein 6: Im Dauerlauf werden Auflösungen durch den Wartegraph gezählt und dokumentiert.

### 4. Booster-Bezirke im Fahrmanagement

| Zustand des Bezirks | Regel |
| --- | --- |
| eingeschaltet, kein Alarm | normal |
| **vom Bediener abgeschaltet** oder **Alarm** (Kurzschluss, Überlast, Übertemperatur, Grenzwert überschritten) | Ressourcen im Bezirk sind für neue Reservierungen **gesperrt**. Bestehende Reservierungen bleiben als Sperre bestehen, die Freigabe (Ende der Freigabe) jedes Zuges davor wird auf die Bezirksgrenze abzüglich Sicherheitsabstand zurückgenommen. Signale vor dem Bezirk gehen sofort auf Halt. Kann ein Zug nicht mehr vor der Grenze halten, bremst die Überwachung in der Klasse Sicherheit; ein Zug, der im Bezirk zum Stehen kommt, ist „Position unsicher“. Für Züge im Bezirk gilt Kapitel 7 (Kurzschluss). |
| Bezirk wieder eingeschaltet | gilt nicht als frei: Erst werden die Melder neu abgefragt und auf Plausibilität geprüft, die Züge im Bezirk müssen bestätigt sein (wie „Ausfall der Rückmelder-Versorgung“, Kapitel 7), dann wird der Bezirk freigegeben: **nach einem Alarm erst nach Freigabe durch den Bediener; nach geplantem Abschalten durch den Bediener automatisch** (das Wiedereinschalten ist dann bereits seine Bedienhandlung). |

Fällt die Versorgung der 10808 im Bezirk mit aus, gehen dessen Melder auf „unbekannt“ und halten Züge zusätzlich über die bestehenden Regeln an. Das ist eine zweite, unabhängige Sicherung.

Ist die Fähigkeit zur Statusmeldung eines Boosters nicht erkennbar (Quelle der Bezirksmeldung laut Entscheidung 40/E7 an Hardware zu prüfen), gilt der Bezirk als „nicht überwacht“ mit Warnung im Selbsttest; die Automatik bleibt erlaubt, weil die Melder einen spannungslosen Bezirk ohnehin als „unbekannt“ melden (Grundsatz 1 greift über diesen Weg).

### 5. Betriebsarten eines Zuges

| Betriebsart | Wer regelt das Tempo | Wer bestimmt den Weg | Reservierung | Befehlsklasse der Fahrstufen |
| --- | --- | --- | --- | --- |
| **Hand** | Bediener (Desktop, WLAN-Maus über RailCore, Handregler über Stellvertreter-Adresse) | aktuelle Weichenlagen | mitlaufend über den Bremsweg entlang bekannter Weichenlagen, Halt vor Weiche mit unbekannter Lage (Entscheidung 40/E11) | Hand |
| **Hand mit Fahrweg** | Bediener | Fahrauftrag (Zielvorgabe oder Zuglauf) | wie Automatik: Fahrweg wird reserviert, Weichen und Signale werden gestellt | Hand |
| **Automatik** | Steuerung (Bremskurve, zielgenaues Halten) | Fahrauftrag | Reservierungs- und Abfahrvorlauf | Automatik |
| **Rangierfahrt** | Steuerung oder Bediener, je nach Auftrag | Rangierauftrag | Rangierreservierung (Abschnitt „Rangieren“) | Automatik bzw. Hand |

Regeln für **Hand mit Fahrweg**:

- Die Überwachung begrenzt den Wunsch des Bedieners auf die Bremskurve bis zum Ende der Freigabe und auf die zulässigen Geschwindigkeiten (Kapitel 7, „Handbefehl über Grenze“).
- Am Ziel hält die Überwachung den Zug spätestens am Haltepunkt. Zielgenaues Halten nach Kapitel 6 gilt nur, wenn der Bediener das Annäherungstempo einhält; sonst hält die Überwachung mit Sicherheitsabstand.
- Bremst der Bediener nicht, greift die Überwachung in der Klasse Sicherheit ein (Entscheidung 32).
- Nach dem Ziel gilt wieder „Hand“ mit mitlaufender Reservierung.

Für alle Betriebsarten gilt: Ein Zug bewegt sich nur innerhalb seiner Reservierung (Grundsatz 2). Die Betriebsart steht im Zugzustand (Kapitel 3) und im Journal. Der Wechsel der Betriebsart ist **nur im Stand** möglich: eindeutige Zuständigkeit, keine Übergangszustände; der Bediener kann jederzeit „Halt“ geben.

### 6. Modellzeit

- Die **Modellzeit** ist eine eigene Uhr für Fahrplan, Aktionen, Licht und Betriebsbeginn bzw. -schluss. Einstellbar sind: Faktor, Tagesbeginn und -ende (verkürzte Nacht), Wochentag, Anzeige. Sie darf angehalten, gestellt und vorgestellt werden.
- Sie wird aus der monotonen `TimeSource` fortgeschrieben (Faktor mal vergangene Zeit) und ist **nie** Grundlage für Sicherheitsrechnungen. Ausgeschlossen sind: Position, Bremskurve, Freimelde- und Ansprechverzögerung, Watchdogs, Zeitgrenzen der Störungserkennung, Wartegrenzen des Befehlsplaners, Stellzeiten. Diese bleiben in Echtzeit.
- Haltezeiten aus Fahrplan, Zuglauf und Automatik gelten in Modellzeit. Technische Mindestzeiten (Stellzeit, Ablauf der Abfahrhandlungen) gelten in Echtzeit; es zählt der längere der beiden Werte.
- Wechsel der Modellzeit (z. B. „Abfahrt 07:12“) sind Ereignisse in der Warteschlange des Steuerungs-Executors. Das Journal führt je Eintrag monotone Zeit, Uhrzeit und Modellzeit.
- Im Simulator schneller als Echtzeit läuft die Modellzeit aus der simulierten `TimeSource` mit.
- Nach einem Neustart wird die gespeicherte Modellzeit übernommen, die Uhr startet angehalten.
- **Bei Nothalt oder Störung ist einstellbar, ob die Modellzeit anhält oder weiterläuft** (ab Werk: anhält, läuft nach der Freigabe weiter; wer realistisch mit Verspätungen fahren will, stellt auf „läuft weiter“).

### 7. Aktionen

Eine Aktion besteht aus **Auslöser** (Ereignis vom Ereignisbus, Modellzeit, Bedienhandlung auf dem Stellpult), **Bedingungen** (mit UND/ODER verschachtelbar; Zustände von Meldern, Blöcken, Zügen, Zugarten, Bezirken, Zubehör, Modellzeit und Wochentag, Home-Assistant-Entitäten, Zufall) und einer Folge von **Schritten** mit Verzögerung.

**Sicherheitsregeln (nicht übersteuerbar):**

1. Aktionen werden auf dem Steuerungs-Executor ausgewertet; Verzögerungen laufen über dessen Zeitplan, keine eigenen Threads.
2. Jeder Schritt ist eine **Anforderung an die Steuerungs-Schnittstelle**, wie die eines Bedieners, und durchläuft dieselben Prüfungen.
   - Betriebliche Schritte laufen in der Klasse **Fahrplan**: Fahrauftrag, Zuglauf starten oder beenden, Zugart wechseln, Rangierauftrag einschließlich Kuppeln und Trennen, Sperre setzen.
   - Licht, Ton, Funktionen ohne Fahrwirkung, Home Assistant und externes Programm starten (siehe unten) laufen in der Klasse **Komfort**.
3. Aktionen **bewegen nie direkt**: kein Setzen von Fahrstufen; Bewegung nur über einen Fahrauftrag mit Reservierung. Weichen und Signale nur über eine Anforderung an das Fahrmanagement; eine verriegelte Weiche oder ein Signal auf Fahrt wird abgelehnt.
4. **Erlaubt** sind einschränkende Schritte: Nothalt auslösen, Halt eines Zuges anfordern, Sperre setzen, Gleisspannung aus.
5. **Nie erlaubt** ist alles, was eine Sicherheitsgrenze lockert:
   - Nothalt quittieren
   - Gleisspannung einschalten
   - Reservierungen freigeben
   - Sperren oder „Position unsicher“ aufheben
   - Melder übersteuern
   - Prüfungen abschalten
   - Konfiguration oder Gleisplan ändern
6. In Nothalt, Störung und Wiederanlauf werden betriebliche Schritte abgelehnt; einschränkende Schritte und Komfort laufen weiter.
7. Wird ein Schritt abgelehnt, bricht die Aktion ab; die folgenden Schritte entfallen. Ablehnung, Grund und auslösende Aktion stehen im Journal. Bei Komfort-Schritten lässt sich „bei Fehler fortfahren“ einstellen.
8. Schutz gegen Schleifen: höchstens eine einstellbare Zahl von Ausführungen je Aktion und Zeitraum sowie eine begrenzte Verkettungstiefe. Bei Überschreitung wird die Aktion deaktiviert, und es erscheint eine Warnung.
9. Zufall nur über die reproduzierbare Zufallsquelle (Abschnitt 1).
10. Lizenzstatus und Demo-Modus begrenzen höchstens das Anlegen neuer Aktionen, nie die Ausführung einschränkender Schritte (Entscheidung 26).

**Externes Programm starten** *(Entscheidung 48)*: Eine Aktion kann frei, mit beliebigen Parametern, ein externes Programm aufrufen – wie bei iTrain (Klasse Komfort, durchläuft Regel 2 wie jeder andere Schritt und steht im Journal). **Bewusst akzeptiertes Risiko:** Ein beliebiger Programmaufruf ist ein Einfallstor für Netzwerkzugriff und muss bei Vertrieb und Unterstützung berücksichtigt werden; dagegen sichert nur die allgemeine Regel, dass Aktionen nie direkt bewegen und keine Sicherheitsgrenze lockern können (Regeln 3 und 5).
