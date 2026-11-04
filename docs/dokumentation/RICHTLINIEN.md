# Richtlinien für Anleitung und Wiki

Gilt für `docs/anleitung/` und `docs/wiki/` (Entscheidung 31).

## Was wohin gehört

| Werk | Ordner | Für wen | Art |
| --- | --- | --- | --- |
| Anleitung | `docs/anleitung/<sprache>/` | Modellbahner, die RailCore einrichten und betreiben | Schritt für Schritt, in der Reihenfolge der Einrichtung; ein Kapitel je Datei |
| Wiki – Anwender | `docs/wiki/<sprache>/anwender/` | Anwender mit einer konkreten Frage | Nachschlagen: Z21 und Melder, Decoder, Fehlerbilder, FAQ |
| Wiki – Entwickler | `docs/wiki/<sprache>/entwickler/` | Wer am Code arbeitet | Architektur, Bauen, Tests, Simulator, Beitragen |
| Glossar | `docs/wiki/<sprache>/glossar.md` | alle | Begriffe mit Übersetzung |

Lastenheft (`docs/lastenheft/`) und Entscheidungsprotokoll bleiben die Vorgaben; Anleitung und Wiki beschreiben, **was gebaut ist und wie man es benutzt**. Sie wiederholen das Lastenheft nicht, sondern verweisen darauf.

## Stand kennzeichnen

Bis Meilenstein 5 werden Anleitung und Wiki nur als Gerüst (Kapitelstruktur, Statuszeilen) und Glossar gepflegt; ausformuliert wird ein Kapitel erst, wenn die beschriebene Funktion fertig und getestet ist (Entscheidung 39).

Jede Seite beginnt nach der Überschrift mit einer Statuszeile:

- `> Status: geplant (Meilenstein N)` – Funktion noch nicht gebaut; die Seite enthält nur die Gliederung.
- `> Status: Entwurf` – Funktion gebaut, Text noch nicht geprüft.
- `> Status: gilt ab Version X.Y` – geprüft und freigegeben.

Funktionen werden nie als vorhanden beschrieben, bevor sie gebaut sind. Jede für Anwender sichtbare Änderung zieht die betroffenen Seiten im selben PR nach.

## Schreiben

- Deutsch, Sie-Form, kurze Sätze; ein Schritt je nummerierter Zeile.
- Bedienelemente so benennen, wie sie in der Oberfläche heißen, in **Fett**: „Klicken Sie auf **Quittieren**“.
- Begriffe nur aus dem Glossar; neue Begriffe dort zuerst eintragen (Deutsch und Englisch).
- Sicherheitshinweise als eigener Absatz mit `> **Achtung:**` – z. B. vor allem, was Gleisspannung einschaltet, Loks bewegt, Weichen stellt oder CVs schreibt.
- Werte mit Einheit und Leerzeichen: „250 ms“, „±10 mm“, „5 km/h (Vorbild)“.
- Keine Versprechen zur Echtzeit oder Genauigkeit über das hinaus, was die Abnahme belegt (Lastenheft Kapitel 10).

## Bilder

- Ablage: `docs/bilder/anleitung/<sprache>/<kapitel>-<inhalt>.png` (sprachabhängige Bildschirmfotos), sprachneutrale Grafiken in `docs/bilder/anleitung/`.
- Bildschirmfotos im hellen und dunklen Erscheinungsbild nur, wenn es sich unterscheidet; Hausfarben siehe CLAUDE.md.
- Jedes Bild mit Alternativtext.

## Sprachen

- Jede Sprache hat einen eigenen Ordner (`de`, `en`) mit **denselben Dateinamen** und derselben Gliederung, damit Links und die Offline-Hilfe sprachunabhängig bleiben. Dateinamen bleiben deutsch, auch in `en/`.
- Englisch wird erst angelegt, wenn die deutsche Seite den Status „gilt ab Version“ hat; jede englische Seite nennt in der Statuszeile den deutschen Stand, von dem sie übersetzt ist.
- Das Glossar führt Deutsch und Englisch nebeneinander und ist für Übersetzungen verbindlich.

## Ausgabe

- Quelle ist immer Markdown im Repository.
- Offline-Hilfe im Programm (Meilenstein 5) und optional eine Website werden aus diesen Dateien erzeugt; das Werkzeug wird später gewählt und vorher im Entscheidungsprotokoll und in `docs/LIZENZEN.md` eingetragen.
- Deshalb: nur Standard-Markdown (Überschriften, Listen, Tabellen, Links, Bilder), relative Links zwischen Seiten, keine HTML-Einbettungen.
