# 4. Gleisplan-Modell

> Maßgebliche Fassung des Lastenhefts. Lesbare Fassung mit Diagrammen: Dokument „RailCore – Technisches Lastenheft“ in claude.ai (https://claude.ai/artifact/EZJSUZJ3T2DzCGpGTZfaDM), wird nach jeder Änderung hier nachgezogen.

Physik und Logik werden getrennt: Ein **Meldeabschnitt** ist ein Kabel am Melder, ein **Block** ist eine Betriebseinheit. Ein Block kann mehrere Abschnitte haben, ein Abschnitt kann eine Weiche abdecken.

| Objekt | Bedeutung | Wichtige Eigenschaften |
| --- | --- | --- |
| Gleisgraph | Gleisstücke (Kanten) zwischen Knoten; Grundlage für Wegsuche und Position | Länge in mm, Verbindungen, Weichenzweige; je Gleisstück optional Spurweite in mm (Standardwert für die ganze Anlage, je Stück überschreibbar, mehrere bei Mehrschienengleis) und Oberleitung (vorhanden, nicht vorhanden, keine Angabe) |
| Meldeabschnitt | physisch isolierter Abschnitt an einem Melder-Eingang | Kontakt, Lage im Graph (von/bis mm), Ansprech- und Freimeldeverzögerung, Booster-Bezirk |
| Block | logische Einheit, in der höchstens ein Zug steht (Ausnahme: Nachrücken, Rangieren) | Länge, Abschnitte (mind. 1, empfohlen 3), erlaubte Richtungen, Zugarten, Mindest-/Höchstzuglänge; optional Wendeverbot und Halteverbot, je Fahrtrichtung getrennt, und Bahnsteigseite (links, rechts, beide, kein Bahnsteig; gesehen in Richtung A → B) |
| Meldepunkt | Stelle, an der ein Abschnitt anspricht; Abgleichpunkt für die Position | Lage in mm, je Fahrtrichtung |
| Haltepunkt | Stelle, an der die Zugspitze hält | Lage je Richtung und Zugart (z. B. Bahnsteigmitte für kurze Züge); optional Bahnsteigseite in Fahrtrichtung |
| Geschwindigkeitswechselpunkt | ab hier gilt eine andere Höchstgeschwindigkeit | Lage, km/h, gilt für Zugspitze oder Zugschluss |
| Weiche | Zubehör mit Lage | Adresse, Adressierungsmodus des Decoders (Roco oder RCN-213), Umkehrung, Belegtabschnitt, optionale physische Lagerückmeldung (zusätzliche Kontakte; der Roco 10836 schaltet nur und bestätigt keine Endlage), Stellzeit |
| Fahrstraße | Weg von Blockende zu Blockende | Weichenlagen, Flankenschutz-Weichen, Durchrutschweg, Signal, Höchstgeschwindigkeit |
| Signal | an einem Blockende, gilt für ausfahrende Züge | Signalbilder (Hp0, Hp1, Hp2, Sh1 …) als Folge von Zubehörbefehlen |
| Streckenprofil | Eigenschaften eines Graphabschnitts | Steigung/Gefälle, Kurve, gelernte Korrekturfaktoren für Geschwindigkeit und Bremsweg |
| Bahnhof, Bahnsteig | Gruppe von Blöcken als Fahrplanziel | Bahnsteiglänge, zulässige Zugarten |
| Schattenbahnhof | Gleise aus mehreren Blöcken hintereinander | Ausfahrtende, Nachrücken, Gleiswahl nach Zuglänge und Zugart |
| Drehscheibe | Bühne als Block, Abgänge zu Blöcken | Decoder, Positionen, Drehrichtung, Verriegelung der Zufährten |
| Gleissperre, Baustelle | gesperrte Ressource | Grund, Zeitraum, wer gesperrt hat |

Spurweite, Oberleitung, Wende- und Halteverbot und Bahnsteigseite sind Angaben für die Gleiswahl (Kapitel 5, Betriebskonzept): Fehlt eine Angabe, schränkt sie nichts ein. Ein Halteverbot betrifft nur geplante Halte; ein Halt aus Sicherheitsgründen ist immer erlaubt.

Der Gleisplan wird mit einer **Prüfung** geladen: Fehler (Block ohne Melder, doppelte Adresse, unbekannte Referenz) verhindern den Automatikbetrieb, Hinweise (weniger als 3 Melder, Länge unbekannt) werden angezeigt.

**Gleisplan als versioniertes Modell:** Jede Aktivierung erzeugt eine Revision; Rückgängig/Wiederherstellen im Editor; Vergleich zweier Versionen; Validierung vor jeder Aktivierung, zusätzlich auf unerreichbare Knoten, Sackgassen ohne Ausweg und Blöcke ohne Ausfahrt. Änderungen am Gleisplan sind nur in der Betriebsart „Aufbau“ möglich, also ohne Fahrbetrieb.
