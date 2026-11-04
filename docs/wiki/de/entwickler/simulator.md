# Simulator

> Status: Entwurf

Der Simulator (Paket `sim`) ist ein vollwertiger Adapter hinter `CommandStation`. Maßgeblich: Lastenheft Kapitel 10.

## Bausteine

| Klasse | Aufgabe |
| --- | --- |
| `SimWorld` | Simulierte Anlage und Zeit |
| `SimLoco` | Lok mit eigenem, absichtlich vom eingemessenen Profil abweichendem „wahren“ Profil – die Positionsrechnung muss also auch im Test korrigieren |
| `SimDetector` | Melder mit Verzögerung und Streuung |
| `SimCommandStation` | Simulierte Zentrale |
| `Distribution` | Zufallsverteilungen für Streuung |

## Geplant

- Störungen auf Knopfdruck oder per Skript (Fehlerkatalog, Lastenheft Kapitel 7).
- Betriebsarten Vollsimulation, Hardware-in-the-Loop, Schattenbetrieb.
- Wiedergabe von Blackbox-Aufzeichnungen.
