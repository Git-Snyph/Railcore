# Fehlerbilder

> Status: Entwurf (Abschnitte mit eigener Statuszeile); übrige Fehlerbilder geplant (Meilenstein 1 und 4)

Je Fehlerbild: was Sie sehen, was RailCore tut, was Sie tun. Maßgeblich für die Reaktionen ist Lastenheft Kapitel 7.

Die Anzeige in der Oberfläche folgt. Bis dahin stehen alle Meldungen im **Journal**; die Kennungen in Klammern (z. B. `COMM_LATE`) helfen beim Suchen.

## Verbindung zur Z21 verspätet

> Status: Entwurf

**Was Sie sehen:** Warnung „Zentrale seit … ms ohne Antwort“ (`COMM_LATE`), Verbindung „verspätet“.

**Was RailCore tut:** Nach 3 s ohne Lebenszeichen der Zentrale nimmt RailCore keine neuen Fahrbefehle mehr an. Fahrbefehle, die noch warten, werden durch Halt ersetzt. Ein Nothalt wird nicht ausgelöst. Kommt die Zentrale wieder, geht es normal weiter.

**Was Sie tun:** Netzwerk und WLAN prüfen. Bleibt die Verbindung aus, folgt nach 5 s „Verbindung verloren“.

## Verbindung zur Z21 verloren

> Status: Entwurf

**Was Sie sehen:** „Verbindung zur Zentrale verloren“ (`COMM_LOST`), Zustand **STÖRUNG**, alle Melder „unbekannt“, alle Züge „unbestätigt“.

**Was RailCore tut:** Nach 5 s ohne Lebenszeichen sendet RailCore Nothalt und verwirft alles, was die Zentrale gemeldet hatte. Sobald die Zentrale wieder antwortet, sendet RailCore sofort erneut Nothalt und Halt an alle bekannten Loks – nie eine Fahrstufe – und liest Fähigkeiten, Gleisspannung und Melder neu ein (`STATE_REFRESH`). Bis dahin bleiben die Melder „unbekannt“. Der Nothalt bleibt gesetzt.

**Was Sie tun:**
1. Ursache beheben (Netzwerk, Stromversorgung der Z21).
2. Klicken Sie auf **Quittieren**, sobald die Verbindung wieder steht. Vorher wird das Quittieren abgewiesen.
3. Prüfen Sie die Züge auf der Anlage und geben Sie den Betrieb frei.

> **Achtung:** Ohne Verbindung kann RailCore die Loks nicht anhalten; die Z21 fährt dann mit der letzten Fahrstufe weiter. Dagegen schützt nur der Hardware-Watchdog (Lastenheft Kapitel 7).

Die Zeiten 3 s und 5 s sind einstellbar.

## Kurzschluss oder Überlast in einem Bezirk

> Status: Entwurf

**Was Sie sehen:** „Bezirk …: Kurzschluss“ bzw. „Überlast“ (`DISTRICT_POWER`, `POWER_FAULT`); Züge gekennzeichnet „Position unsicher“; Melder „unbekannt“, dazu ihr letzter gültiger Zustand mit Uhrzeit.

**Was RailCore tut:** Der Booster schaltet den Bezirk selbst ab. RailCore kennzeichnet die Züge als „Position unsicher“ und „unbestätigt“; sie dürfen nur noch bremsen und halten, nicht anfahren oder schneller werden. Die Automatik hält geordnet, ein Nothalt wird nicht ausgelöst. Die Melder im Bezirk gelten als „unbekannt“.

Solange der Gleisplan fehlt, weiß RailCore nicht, welche Züge und Melder im Bezirk liegen. Deshalb betrifft ein Bezirksfehler vorläufig **alle** Züge und Melder.

Meldet die Zentrale selbst einen Kurzschluss, geht RailCore zusätzlich in den Zustand **STÖRUNG**.

**Was Sie tun:**
1. Kurzschluss suchen und beheben (entgleistes Fahrzeug, Weiche, Metallteil).
2. Bezirk wieder einschalten. Die Melder werden neu eingelesen, siehe „Melder ohne Versorgung“.
3. Für jeden gekennzeichneten Zug nach Prüfung die Kennzeichnung aufheben. Das geht erst, wenn alle Bezirke und die Gleisspannung wieder eingeschaltet sind. Der Zug bleibt danach „unbestätigt“.

Hinweis: An der Z21 wertet RailCore bisher nur den Kurzschluss der Zentrale aus. Meldungen der Booster je Bezirk werden erst nach der Prüfung an der Hardware angebunden.

## Melder ohne Versorgung (Z21 Detector 10808)

> Status: Entwurf

**Was Sie sehen:** „… Melder ohne Versorgung“ (`DETECTOR_SUPPLY_LOST`) oder „Melder stumm“ (`DETECTOR_SILENT`); bei einem ganzen Modul „CAN-Bereich Modul … (alle 8 Melder)“. Die Melder stehen auf „unbekannt“, ihr letzter gültiger Zustand bleibt mit Uhrzeit sichtbar.

**Was RailCore tut:** Der 10808 wird aus der Gleisspannung versorgt und meldet ohne sie nichts. Bei Gleisspannung aus, Kurzschluss oder einem Bezirk ohne Strom gelten die Melder als „unbekannt“. Unterstützt die Zentrale die Melderabfrage, fragt RailCore regelmäßig ab und erkennt so auch einzelne stumme Melder.

Kommt die Versorgung zurück, gilt das **nie** als Freimeldung. RailCore fragt neu ab und gibt den Melder erst frei, wenn zwei Antworten übereinstimmen; „frei“ zusätzlich erst nach der Freimeldeverzögerung. Ohne Melderabfrage bleibt der Melder „unbekannt“, bis Sie neu verbinden.

**Was Sie tun:** Versorgung und CAN-Kabel des Moduls prüfen. Bleibt ein Melder nach der Rückkehr „unbekannt“ (`DETECTOR_RECHECK_FAILED`), den Melder prüfen oder neu verbinden.

Die regelmäßige Abfrage läuft ab Werk alle 2 s, stumm gilt ein Melder nach 5 s; beide Werte sind einstellbar.

Hinweis: An der Z21 ist die Melderabfrage noch nicht angebunden; das folgt nach dem nächsten Prüflauf an der Hardware. Nach Gleisspannung aus bleiben die Melder dort deshalb bis zum Neuverbinden „unbekannt“.

## Flackernder Melder

> Status: Entwurf

**Was Sie sehen:** „Rückmelder … flackert (…×) – Kontakt/Radschleifer prüfen“ (`SENSOR_FLICKER`); die Diagnose zählt die Wechsel je Melder.

**Was RailCore tut:** Belegt wird sofort übernommen, frei erst nach der Freimeldeverzögerung (Standard 500 ms). Kurze Unterbrechungen ändern den Zustand nicht, werden aber gezählt. Ab 5 Wechseln (einstellbar) warnt RailCore.

**Was Sie tun:** Radschleifer, Radkontakte und Schienenstöße im Abschnitt prüfen und reinigen.

## Lok fährt anders als gesendet

> Status: Entwurf

**Was Sie sehen:** „Lok …: Zentrale meldet Stufe …, gesendet war Stufe …“ (`LOCO_DIVERGED`); der Zug ist gekennzeichnet „größerer Sicherheitsabstand“ und „unbestätigt“.

**Was RailCore tut:** Meldet die Zentrale für eine Lok eine andere Fahrstufe oder Richtung, als RailCore gesendet hat, sendet RailCore den erlaubten Wert erneut. Ist Fahren inzwischen nicht mehr erlaubt, sendet RailCore Halt. Der Zug wird gekennzeichnet; die Kennzeichnung wirkt sich mit der Reservierung (Meilenstein 2) auf die Abstände aus.

Wechseln sich ein Handregler und RailCore bei derselben Lok ab (3 Abweichungen in 10 s, einstellbar), sendet RailCore nicht weiter dagegen an, sondern hält die Lok (`LOCO_CONTROL_CONFLICT`). Sie darf erst wieder anfahren, wenn Sie entschieden und die Kennzeichnung aufgehoben haben.

**Was Sie tun:** Prüfen, ob ein Handregler direkt die echte Lokadresse fährt. Handregler an der Z21 gehören auf eine Stellvertreter-Adresse (9000–9999). Danach die Kennzeichnung aufheben.

Hinweis: Die Z21 meldet Fahrbefehle anderer Geräte nicht von selbst. RailCore erkennt die Abweichung deshalb nur, wenn die Zentrale den Zustand der Lok meldet, etwa auf eine Abfrage. Fahrende Loks werden bewusst nicht laufend abgefragt; gezielt abgefragt wird bei Verdacht, sobald die Positionsverfolgung gebaut ist.

## Interner Fehler

> Status: Entwurf

**Was Sie sehen:** **NOTHALT** und **STÖRUNG**, „Interner Fehler …“ (`INTERNAL_ERROR`) oder „Steuerungs-Executor ohne Takt seit … ms“ (`EXECUTOR_STALLED`), dazu ein Diagnosebericht (`DIAGNOSTIC_REPORT`).

**Was RailCore tut:** Bei einer Ausnahme im Kern oder wenn die Steuerung länger als 250 ms keinen Takt schafft, sendet RailCore sofort Nothalt und schreibt einen Diagnosebericht mit Ursache und Zustand ins Journal.

**Was Sie tun:** Den Diagnosebericht sichern und melden. Danach **Quittieren**, Anlage prüfen, Betrieb freigeben.

## Geplant

- Melder bleibt frei oder bleibt belegt
- Geisterzug, falsche RailCom-Adresse
- Position unsicher durch Schlupf oder falsches Profil
- Weiche stellt nicht
- Verlorener Wagen
- Überlast der Befehlsausgabe
