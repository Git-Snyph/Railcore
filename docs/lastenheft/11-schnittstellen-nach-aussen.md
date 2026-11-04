# 11. Schnittstellen nach außen

> Maßgebliche Fassung des Lastenhefts. Lesbare Fassung mit Diagrammen: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), wird nach jeder Änderung hier nachgezogen.

Alle externen Zugänge sprechen mit der Steuerungs-Schnittstelle des Kerns, genau wie die Desktop-Oberfläche, und durchlaufen damit dieselben Prüfungen.

| Zugang | Inhalt | Zeitpunkt |
| --- | --- | --- |
| Netzwerk-API (REST + Live-Ereignisse) | Zustände abfragen, Befehle senden, Ereignisse abonnieren | Meilenstein 8 |
| MQTT mit Home-Assistant-Discovery | Züge, Blöcke, Weichen, Licht, Szenen, Automatik als Entitäten | Meilenstein 8 |
| iOS- und Android-App | Fahren, Stellpult, Live-Gleisbild, Diagnose | Meilenstein 9 |

**Home Assistant in beide Richtungen:** Neben der Steuerung von RailCore aus Home Assistant kann RailCore auch **Home-Assistant-Geräte bedienen und abfragen** (über die Home-Assistant-API mit eigenem Zugangstoken, alternativ MQTT).

- **Schalten:** Schalter, Steckdosen, Lichter, Szenen und Skripte in Home Assistant als Befehl in Aktionen, Szenarien, Fahrplan, Betriebsbeginn/-schluss und auf Stellpulten (z. B. Raumlicht dimmen im Nachtbetrieb, Anlagennetzteile bei Betriebsbeginn einschalten, Rauchgenerator-Steckdose, Bahnhofsdurchsage über einen Home-Assistant-Lautsprecher).
- **Abfragen:** Zustände von Home-Assistant-Entitäten als Auslöser und Bedingungen in Aktionen (z. B. Bewegungsmelder im Raum → Besucherbetrieb, Türkontakt, Tageslicht).
- **Zuordnung im Programm:** Home-Assistant-Entitäten werden in RailCore Studio wie Zubehör angelegt (Name, Entitäts-ID, Art) und können auf Stellpulte gelegt werden; der Zustand wird live angezeigt.
- **Sicherheitsgrenze:** Home Assistant ist eine Komfortfunktion. Keine Sicherheitsfunktion (Nothalt, Watchdog, Gleisspannung, Freigaben) darf von Home Assistant abhängen. Fällt Home Assistant aus, läuft der Betrieb weiter; ausgefallene Schaltbefehle werden gemeldet. Booster-Versorgung wird über Home Assistant höchstens eingeschaltet, abgeschaltet erst nach Betriebsschluss, wenn alle Züge stehen.
- Befehle an Home Assistant laufen mit Priorität „Komfort“ durch die Befehlsverwaltung und werden protokolliert.

**Netzwerksicherheit (Pflicht, sobald ein Zugang aktiv ist)**

- Standardmäßig ist kein Netzwerkzugang offen; jeder wird einzeln eingeschaltet.
- Anmeldung mit Token bzw. Benutzer und Kennwort, verschlüsselte Verbindung (TLS), MQTT mit eigenem Benutzer.
- Zugriff nur aus dem Heimnetz, optional auf bestimmte Geräte beschränkt.
- REST und MQTT sind standardmäßig deaktiviert; die erste API-Version ist nur lesend. Schreibrechte werden ausdrücklich freigeschaltet, Fahrbefehle und Nothalt getrennt berechtigt. Sicherheitsfunktionen lassen sich nie über REST oder MQTT abschalten oder umgehen. Rollen (Administrator, Fahrdienstleiter, Lokführer, Beobachter, Besucher) sind im Datenmodell vorgesehen.
- Jeder externe Eingriff wird mit Herkunft im Betriebsprotokoll vermerkt.

**Lizenzierung (später)**

Konzept für den späteren Vertrieb; noch nicht umgesetzt.

- **Signierter Lizenzcode:** Signatur mit Ed25519, offline prüfbar. Der öffentliche Schlüssel ist im Programm enthalten, der private Schlüssel liegt nie im Repository.
- **Inhalt des Lizenzcodes:** Kunde, freigeschaltete Produktbereiche, Grenzen (z. B. Anzahl Züge), Update-Berechtigung (bis Datum oder Version), optional Rechnerbindung.
- **Lizenzgenerator:** separates Werkzeug, wird nicht ausgeliefert.
- **Demo-Modus ohne Lizenz:** eingeschränkter Betrieb, z. B. höchstens 3 Züge in der Automatik; Simulator und Gleisplan-Editor frei.
- **Sicherheitsregel:** Die Lizenz wird nur beim Start und beim Starten neuer Vorgänge geprüft, nie während laufender Fahrten; eine Lizenzprüfung bricht nie eine laufende Fahrt ab. Nothalt, Watchdog, Bremsen und Wiederanlauf funktionieren immer, unabhängig von Lizenz und Demo-Modus.
