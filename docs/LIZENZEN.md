# Lizenzen der Fremdbausteine

Stand: 04.11.2026. Jede neue Fremdbibliothek wird hier eingetragen, bevor sie eingebunden wird (siehe CLAUDE.md, „Code und Tests“). Zulässig sind nur Lizenzen, die einen kommerziellen Vertrieb als geschlossene Software erlauben (z. B. Apache 2.0, MIT, BSD, EPL); GPL/AGPL nur nach Rückfrage.

## Verwendet

| Name | Version | Lizenz | Link | Wofür |
| --- | --- | --- | --- | --- |
| Gradle Build Tool (über den Gradle-Wrapper: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) | 9.8.2 | Apache License 2.0 | https://gradle.org | Bauen und Testen; der Wrapper liegt im Repository, Gradle selbst wird beim ersten Lauf geladen. Nur Entwicklungswerkzeug, nicht Teil des ausgelieferten Programms |
| Eclipse Temurin (OpenJDK) | 25.0.5+8 (LTS) | GNU GPL v2 mit Classpath Exception | https://adoptium.net | Laufzeitumgebung; wird mit dem Installer als Java-Laufzeit mitgeliefert (jpackage). Die Classpath Exception erlaubt das Mitliefern mit geschlossener Software |

Eigene Bestandteile ohne Fremdlizenz: JSON-Verarbeitung (`util/Json`), Testlauf (`TestRunner`, kein JUnit), Logo und Programmsymbole.

## Geplant (noch nicht eingebunden)

| Name | Lizenz | Link | Wofür | Status |
| --- | --- | --- | --- | --- |
| FlatLaf | Apache License 2.0 | https://www.formdev.com/flatlaf/ | Erscheinungsbild der Swing-Oberfläche (FEATURES.md) | vor dem Einbinden Version festlegen und Begründung im Entscheidungsprotokoll |
