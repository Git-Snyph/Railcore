# 3. Zustandsmodelle

> Quelle: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), Stand 03.11.2026. Maßgeblich ist das Dokument in claude.ai; Änderungen dort planen und hier nachziehen.

Vier Zustandsmodelle mit fest vorgegebenen Übergängen; jeder andere Wechsel wird abgelehnt und protokolliert.

**Zugzustände** – ein Zug fährt nur aus dem Zustand „Reserviert“ los:

```text
Abgestellt (PARKED) → Bereit (READY) → Wartet (WAITING) → Reserviert (RESERVED)
                                            ▲                     │
                          nächster Fahrwegabschnitt               ▼
Steht (STOPPED) ← Bremst (BRAKING, STOPPING) ← Fährt (RUNNING) ← Beschleunigt (ACCELERATING)
   │   ▲
   ▼   │ Rangierauftrag
Rangierfahrt (SHUNTING)

Nothalt · Fehler (EMERGENCY, ERROR): aus jedem Zustand; Zug steht,
Position unsicher bis Abgleich und Quittung.
```

Wird die Freigabe während der Fahrt verlängert, wechselt ein bremsender Zug zurück in „Fährt“. Der Handbetrieb nutzt dieselben Zustände.

| Modell | Zustände | Wichtigste Regel |
| --- | --- | --- |
| Anlage | Initialisierung (Hardware prüfen, Zustände einlesen), Aufbau (Gleisplan bearbeiten, kein Fahrbetrieb), Bereit, Handbetrieb, Automatik, Automatik endet, Störung/Nothalt (Fahrfreigaben entziehen, Zustand sichern), Wiederanlauf/Quittierung | Automatik-Fahrbefehle nur in „Automatik“; Wechsel nur über festgelegte Übergänge, nie von Störung direkt in Automatik, sondern über Wiederanlauf, Ursachenprüfung und eigene Freigabe nach Nothalt |
| Meldeabschnitt | frei, belegt, unbekannt, gesperrt (verdächtig oder Handsperre) | „unbekannt“ nach Start und Verbindungsverlust, wird wie belegt behandelt |
| Reservierung | angefragt, gewährt, aktiv (Zug darin), teilweise freigegeben, freigegeben | Freigabe erst nach vollständigem Verlassen und Freimeldung |
| Zug | siehe Bild | Bewegung nur nach „Reserviert“, Nothalt und Fehler aus jedem Zustand |
| Weiche | unbekannt, gerade angefordert, abzweigend angefordert, gerade bestätigt, abzweigend bestätigt, Fehler (Widerspruch oder Zeitüberschreitung) | mit physischer Lagerückmeldung erst nach Bestätigung freigeben; ohne Rückmeldung (z. B. Roco 10836) gilt „angefordert“ nach Stellzeit als gestellt und wird so gekennzeichnet |

Manuelle Übersteuerung eines Melderzustands (z. B. „als frei setzen“) ist möglich, wird aber mit Grund und Zeit protokolliert und gilt nur im Zustand Bereit.
