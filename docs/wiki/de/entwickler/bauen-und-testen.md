# Bauen und Testen

> Status: Entwurf

## Voraussetzungen

- JDK 25 (Eclipse Temurin).
- Gradle kommt über den Wrapper (Gradle 9.8.2); ältere Gradle-Versionen unterstützen Java 25 nicht.

## Befehle

```
gradlew build -q      # bauen und alle Tests ausführen, leise
gradlew selfTest      # nur die Tests
gradlew z21Check --console=plain --args="--hilfe"   # Prüfprogramm, Optionen anzeigen
```

`build` hängt über `check` am `selfTest`.

## Tests

- RailCore nutzt einen eigenen Testlauf (`TestRunner`) statt JUnit. Er findet alle Klassen mit Namen `*Test` und führt deren Methoden `test*` aus; Prüfungen mit `assertEquals`, `assertThrows` usw.
- Der Gradle-Standardtask `test` ist abgeschaltet.
- Protokoll-Kodierung wird gegen bekannte Bytefolgen getestet, Sicherheitsverhalten im Simulator.
- Zeitabhängige Tests verwenden `TimeSource.Manual`, nie die echte Uhr.
- Simulator-Abnahmetests geben ihre Kennzahlen (z. B. Haltegenauigkeit, Wartezeiten unter Last) auf der Konsole aus.

## Prüfprogramm an der echten Z21

Siehe `docs/Z21-PRUEFPROGRAMM.md`. Die aktive Stufe schaltet Gleisspannung, Weichen und fährt eine Lok – nur mit Bestätigung durch den Bediener, nie aus automatischen Tests.
