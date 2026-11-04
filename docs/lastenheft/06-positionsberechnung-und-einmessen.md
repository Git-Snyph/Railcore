# 6. Positionsberechnung und Einmessen

> Quelle: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), Stand 03.11.2026. Maßgeblich ist das Dokument in claude.ai; Änderungen dort planen und hier nachziehen.

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
- Beide Modi gelten für jede Fahrt: Automatik, Fahrplan und Handbetrieb über die Stellvertreter-Adresse.
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

**Risiko bei Ausfall des PCs:** Die Z21 fährt die zuletzt gesendete Fahrstufe weiter, wenn die Steuerung abstürzt. Gesichert wird das durch einen Hardware-Watchdog (siehe Abschnitt 7).

**Abgleich und Unsicherheit**

- Spricht ein Meldeabschnitt an, wird die Zugspitze auf seinen Meldepunkt gesetzt, zuzüglich der seit dem Ereignis gefahrenen Strecke und der Melderverzögerung.
- RailCom bestätigt, welcher Zug den Abschnitt belegt (Adresse, Zeitstempel, soweit verfügbar Richtung).
- Die **Positionsunsicherheit** wächst mit der Strecke seit dem letzten Abgleich (Startwert 2 % der Strecke + 20 mm, wird aus der Statistik je Zug gelernt). Sie geht in Bremspunkt und Reservierung ein.
- Abweichung beim Abgleich größer als erwartet → Schlupf oder falsches Profil: Warnung, Zug verlangsamt; sehr groß → Halt, „Position unsicher“.

**Bremskurve:** Aus Ziel (Haltepunkt, Ende der Freigabe, Langsamfahrstelle), Unsicherheit und Bremsprofil folgt laufend die höchste zulässige Geschwindigkeit an der aktuellen Stelle. Unterschreitet der Abstand den Bremsweg, wird gebremst; der Zug hält am Haltepunkt, ohne eigenen Brems- oder Haltemelder.

**Anlage vermessen:** Ein eingemessener Zug fährt mit konstanter Fahrstufe über die Anlage; aus den Zeiten zwischen den Meldepunkten ergeben sich Abschnitts- und Blocklängen. Manuell gemessen werden muss nur die Messstrecke.
