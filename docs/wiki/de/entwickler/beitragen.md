# Mitarbeiten

> Status: Entwurf

Verbindlich ist `CLAUDE.md`. Kurzfassung:

- Neue Arbeit auf einem eigenen Branch, nie direkt auf `main`; Pull Request, Merge erst nach grünem `gradlew build -q` und Zustimmung.
- Kleine Commits mit deutscher Nachricht; Bezeichner im Code englisch, Kommentare und Texte deutsch.
- Jede neue Logik mit Test.
- Grundsätzliche Entscheidungen (Architektur, Sicherheit, Hardwarezugriff, Bibliotheken, Datenformate) trifft der Projektinhaber; sie stehen in `docs/ENTSCHEIDUNGEN.md`.
- Fremdbibliotheken nur mit Lizenzen, die geschlossenen kommerziellen Vertrieb erlauben, eingetragen in `docs/LIZENZEN.md`.
- Die Sicherheitsgrundsätze in `CLAUDE.md` werden nie aufgeweicht.
- Für Anwender sichtbare Änderungen ziehen Anleitung und Wiki im selben PR nach (`docs/dokumentation/RICHTLINIEN.md`).
- Zugriff auf echte Hardware (Gleisspannung, Loks, Weichen, CVs) nur mit ausdrücklicher Bestätigung.
