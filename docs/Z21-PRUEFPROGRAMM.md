# Z21-Prüfprogramm

Klärt an der echten Z21 die offenen Hardware-Fragen (Lastenheft Kap. 8) und erkennt die Fähigkeiten der Zentrale. Entscheidung 20.

## Start

```
gradlew z21Check --console=plain --args="--host 192.168.0.111"              # Stufe 1, passiv
gradlew z21Check --console=plain --args="--host 192.168.0.111 --dauer 120"  # längere Beobachtung
gradlew z21Check --console=plain --args="--host 192.168.0.111 --stufe aktiv"
gradlew z21Check --console=plain --args="--hilfe"                           # alle Optionen
```

Ergebnis: Prüfbericht `berichte/z21-pruefbericht-<Datum>-<Zeit>.txt`, vollständiges Journal mit allen Rohpaketen unter `logs/`.

## Stufe 1 – passiv (Standard)

Sendet nur Abfragen und Abos (Positivliste in `CommandClass`): Seriennummer, HW-Info, Firmware, Freischaltcode, Status, Systemzustand, Broadcast-Flags setzen und zurücklesen, R-Bus, CAN-Melder, Lokinfo der Stellvertreter-Adresse, Weicheninfo. Danach werden Melder-, RailCom- und Lokmeldungen mitgeschnitten. Während der Beobachtung bietet es sich an,

- Melder zu belegen und Loks mit RailCom auf überwachte Abschnitte zu stellen (RailCom-Adressen und Richtungsbits),
- eine Lok mit einem Handregler zu bewegen (Lok-Broadcast, F29–F31-Byte),
- die Stellvertreter-Adresse an der WLAN-Maus aufzurufen – nur, wenn dort sicher kein Decoder ist.

## Stufe 2 – aktiv

Jeder Eingriff wird im Klartext angekündigt und nur nach der Eingabe `JA` (Großbuchstaben) ausgeführt. Jede andere Eingabe, eine Zeitüberschreitung, eine fehlende Rückmeldung oder mehr als `--verbindung-ms` Stille bricht die Stufe ab: ein aktiver Weichenausgang wird abgeschaltet, an die Stellvertreter-Adresse geht sofort ein Nothalt.

1. Gleisspannung einschalten (nur wenn aus; Nothalt, Kurzschluss oder unbekannter Zustand werden nicht aufgehoben)
2. Weiche auf Abzweig, danach auf Gerade (Impuls 150 ms); der Bediener gibt an, welche Weiche sich bewegt hat → 10836-Adressierung
3. POM-Zubehör: CV lesen (es wird nichts geschrieben)
4. Fahren auf der Stellvertreter-Adresse mit Fahrstufe 1 (höchstens 10) für 2 s, danach Fahrstufe 0 und Nothalt; der Bediener meldet, ob sich etwas bewegt hat und ob die Maus-Anzeige die Fahrstufe übernommen hat
5. Gleisspannung wieder ausschalten, falls das Programm sie eingeschaltet hat

## Grenzen

- Ergebnisse ohne Rückmeldung stehen als „unbekannt“, nie als in Ordnung. Punkte mit „an Hardware zu prüfen“ sind in ihrer Deutung nicht gesichert.
- Wird das Programm hart beendet (z. B. Fenster geschlossen, Gradle-Abbruch), läuft der Shutdown-Hook unter Umständen nicht. Die Z21 sendet dann die letzte Fahrstufe an die Stellvertreter-Adresse weiter – deshalb Fahrstufe klein halten und auf dieser Adresse keinen Decoder betreiben.
