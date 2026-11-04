# 6. Positionsberechnung und Einmessen

> Maßgebliche Fassung des Lastenhefts. Lesbare Fassung mit Diagrammen: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), wird nach jeder Änderung hier nachgezogen.

Jede Zugposition ist ein Punkt im Gleisgraph (Kante + mm) für die Zugspitze, dazu Richtung und Länge; daraus folgen alle belegten Abschnitte. Gerechnet wird 20× pro Sekunde aus Fahrstufe, Profil und Zeit.

**Drei Profilebenen**

1. **Lokprofil:** Geschwindigkeit je Fahrstufe in mm/s, vorwärts und rückwärts getrennt, gemessen auf einer Messstrecke mit zwei Meldern bekannten Abstands; mehrere Läufe gemittelt.
2. **Zugprofil:** Trägheit des Zugverbands (Lok plus Wagen) als Masse-Modell der Steuerung: Beschleunigung, Betriebs- und Schnellbremsung; im Standardmodus verzögert der Decoder selbst nicht (CV3/CV4 = 0).
3. **Streckenprofil:** Korrekturfaktoren je Abschnitt für Geschwindigkeit und Bremsweg, gelernt aus den Abweichungen beim Melder-Abgleich (z. B. Rampe −8 %, Gefälle Bremsweg +15 %).

**Fahrkurve: zwei Betriebsarten je Lok, Standard ist Software Motion Control:**

| Modus | Funktion | Einsatz |
| --- | --- | --- |
| **Software Motion Control** (Standard) | CV3/CV4 möglichst 0, herstellerspezifische Zusatzverzögerungen aus (z. B. ZIMO CV121–CV123), Lastregelung an; Anfahren und Bremsen durch die Trägheitssimulation der Steuerung (Masse-Modell je Zugverband) | alle Loks, deren Decoder und Soundprojekt das zulassen |
| **Decoder Motion Control** (Ausnahme) | Decoder übernimmt die Verzögerung mit seinen eingestellten CVs; die Steuerung rechnet mit dem gemessenen Verhalten einschließlich Decoder-Rampe; ändern sich CV3/CV4, ist das Profil ungültig | Loks, bei denen das Soundprojekt ohne Decoder-Rampe nicht stimmig klingt oder der Decoder es erfordert |

- Der Modus wird je Lok in der Datenbank geführt und nie gemischt; die Decoder-Werkstatt prüft, ob die CVs zum Modus passen, und warnt bei Abweichung.
- Beide Modi gelten für jede Fahrt: Automatik, Fahrplan und Handbetrieb (WLAN-Maus über RailCore, Handregler an der Z21 über die Stellvertreter-Adresse).
- Fahrbefehle laufen über den \*\*Befehlsplaner\*\* (siehe unten), der die Rate je Zug dynamisch nach Z21- und Gleislast festlegt.
- **Bremsweg wird gerechnet:** aus Geschwindigkeit, Masse-Modell bzw. Messung und Streckenprofil. Ein schwerer Güterzug beginnt deutlich früher zu bremsen als ein Triebwagen und hält trotzdem am selben Haltepunkt.

Eingemessen wird das Grundverhalten der Lok (Geschwindigkeit je Fahrstufe, Reaktionszeit des Decoders, Auslaufweg bei Fahrstufe 0) mit vollständigem Zugverband, in beiden Richtungen und mindestens dreimal; gespeichert werden Mittelwert und Streuung. Die Streuung geht in die Positionsunsicherheit ein.

**Laufende Nachmessung:** Bei jeder Blockdurchfahrt misst die Steuerung die tatsächliche Geschwindigkeit zwischen den Meldepunkten und pflegt damit Lok-, Zug- und Streckenprofil laufend nach (gleitender Mittelwert, Ausreißer verworfen, jede Änderung protokolliert). Weicht eine Lok dauerhaft ab, meldet die Steuerung „neu einmessen oder warten“.

**Einmessen mit externem Messgerät:** Zusätzlich zur Messstrecke mit 10808-Meldern wird ein Adapter für externe Geschwindigkeitsmessgeräte vorgesehen, zuerst für die RZ Speedbox 2. Deren Schnittstelle (USB, Bluetooth, Datenformat) ist noch zu klären; benötigt wird die Anleitung bzw. Herstellerangabe.

**Befehlsplaner und DCC-Befehlsbudget:** Die Z21 wiederholt Fahr- und Funktionsbefehle selbst auf dem Gleis; die Bandbreite ist für die ganze Anlage begrenzt. Deshalb:

- Budget an Befehlen pro Sekunde, laufend aus Antwortzeiten und Zahl aktiver Loks nachgeführt.
- Unveränderte Fahrstufen werden nie gesendet; mehrere Änderungen an derselben Lok werden zusammengefasst (nur der neueste Wert zählt).
- Vorrang: Nothalt, Brems- und Sicherheitsbefehle vor Anfahren, Anfahren vor Funktionen und Komfort.
- Die Aktualisierungsrate je Zug sinkt dynamisch bei hoher Last; bremsende Züge behalten ihre Rate. Sinkt das Budget unter den Bedarf der Bremskurven, fährt die Steuerung Züge mit größerem Abstand bzw. langsamer.
- Gemessen wird die tatsächliche Befehlsverzögerung bei 10, 25, 50 und 100 aktiven Loks; die Messwerte gehen in Bremspunkt und Sicherheitsabstand ein.

**Geschwindigkeitsvorgaben auf der Strecke:** dauerhafte und temporäre Langsamfahrstellen, Begrenzung vor und in Weichen abhängig von der Lage (gerade/abzweigend), signalabhängige Geschwindigkeit, Höchstgeschwindigkeit je Fahrzeug und je Zugverband (es gilt der kleinste Wert). Bremsprofile: Betriebsbremsung und Notbremsung getrennt, je Zug und je Streckenabschnitt; Nachkalibrierung nach wiederholten Messungen. Eine grafische **Bremskurven-Ansicht** zeigt je Zug Position, Geschwindigkeit, zulässige Höchstgeschwindigkeit, Bremsbeginn, Sicherheitsabstand, Ziel, Unsicherheit und den tatsächlichen Verlauf; Parameter lassen sich dort anpassen.

**Risiko bei Ausfall des PCs:** Die Z21 fährt die zuletzt gesendete Fahrstufe weiter, wenn die Steuerung abstürzt oder die Netzwerkverbindung ganz abreißt. Ein externer Hardware-Watchdog wird nicht gebaut (Entscheidung 38); das ist ein bewusst akzeptiertes Restrisiko (siehe Abschnitt 7).

**Abgleich und Unsicherheit**

- Spricht ein Meldeabschnitt an, wird die Zugspitze auf seinen Meldepunkt gesetzt, zuzüglich der seit dem Ereignis gefahrenen Strecke und der Melderverzögerung.
- RailCom bestätigt, welcher Zug den Abschnitt belegt (Adresse, Zeitstempel, Aufgleisrichtung der Lok – die RailCom-Richtungsbits zeigen nicht die Fahrtrichtung). Eine RailCom-Adresse gilt nur, solange der Abschnitt belegt gemeldet ist; der 10808 meldet das Verlassen nicht immer mit einer leeren Adresse.
- Die **Positionsunsicherheit** wächst mit der Strecke seit dem letzten Abgleich (Startwert 1 % der Strecke + 3 mm – 3 mm Messstreuung des Melders, 1 % Abweichung im Fahrprofil; wird aus der Statistik je Zug gelernt, die Startwerte gelten nur bis zu echten Messungen). Sie geht in Bremspunkt und Reservierung ein.
- Abweichung beim Abgleich größer als erwartet → Schlupf oder falsches Profil: Warnung, Zug verlangsamt; sehr groß → Halt, „Position unsicher“.

**Bremskurve:** Aus Ziel (Haltepunkt, Ende der Freigabe, Langsamfahrstelle), Unsicherheit und Bremsprofil folgt laufend die höchste zulässige Geschwindigkeit an der aktuellen Stelle. Unterschreitet der Abstand den Bremsweg, wird gebremst; der Zug hält am Haltepunkt, ohne eigenen Brems- oder Haltemelder.

**Zielgenaues Halten:** Zwei Arten von Halt mit unterschiedlicher Bremsweise:

| Halt | Bremsweise | Genauigkeit |
| --- | --- | --- |
| Bahnsteig-Haltepunkt (Bahnhof) | zweistufig: Bremskurve auf ein **Annäherungstempo** (Standard 5 km/h Vorbild, je Zugprofil einstellbar), die letzte Strecke (Standard 50–100 mm, je Haltepunkt einstellbar) im Annäherungstempo, dann Halt | Ziel ±10 mm |
| Halt auf der Strecke (z. B. vor Halt zeigendem Signal, Ende der Freigabe) | normal: eine Bremskurve bis zum Stillstand | Halt mit Sicherheitsabstand vor dem Ziel, gesichert durch Durchrutschweg |

- Es wird **nicht** angenommen, wie viele Melder ein Block hat oder welcher Melder vor einem Haltepunkt liegt. Die Steuerung berechnet für jeden Haltepunkt die erreichbare Genauigkeit aus dem Abstand zum letzten Meldepunkt davor (Positionsunsicherheit wächst mit der Strecke seit dem Abgleich), der gemessenen Melderverzögerung und dem Tempo beim Überfahren.
- RailCore Studio zeigt die erwartete Genauigkeit je Haltepunkt an und gibt einen Hinweis, wenn ±10 mm nicht erreichbar sind (z. B. „erwartet ±17 mm – Meldepunkt näher am Haltepunkt würde helfen“). Der Halt funktioniert trotzdem, nur weniger genau.
- Beim Einmessen zusätzlich gemessen: Ansprechverzögerung der Melder je Abschnitt und Auslaufweg je Lok aus dem Annäherungstempo.
- Brems- und Haltebefehle werden auf den vorausberechneten Zeitpunkt eingeplant, nicht erst beim nächsten Rechentakt.
- Nothalt und ungeplante Halte bremsen bewusst auf kürzestem Weg und sind von der Genauigkeitsvorgabe ausgenommen.

**Anlage vermessen:** Ein eingemessener Zug fährt mit konstanter Fahrstufe über die Anlage; aus den Zeiten zwischen den Meldepunkten ergeben sich Abschnitts- und Blocklängen. Manuell gemessen werden muss nur die Messstrecke.
