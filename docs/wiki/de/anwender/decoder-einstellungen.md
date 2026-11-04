# Decoder-Einstellungen

> Status: Entwurf

## Software Motion Control (Standard)

RailCore rechnet Anfahren und Bremsen selbst. Damit die Position stimmt, darf der Decoder nicht zusätzlich verzögern:

- CV3 (Anfahrverzögerung) und CV4 (Bremsverzögerung) möglichst 0.
- Herstellerspezifische Zusatzverzögerungen aus (z. B. ZIMO CV121–CV123).
- Lastregelung an.

## Decoder Motion Control (Ausnahme je Lok)

Wenn das Soundprojekt ohne Decoder-Rampe nicht stimmig klingt, verzögert der Decoder selbst. RailCore rechnet dann mit dem gemessenen Verhalten. Ändern sich danach CV3 oder CV4, ist das Profil ungültig und die Lok muss neu eingemessen werden.

## Adressen

- 9000–9999 sind Stellvertreter-Adressen für Handregler – dort keinen Decoder programmieren.

## Geplant

- Decoder-Werkstatt prüft, ob die CVs zum gewählten Modus passen (Meilenstein 3).
- Herstellerhinweise je Decoder-Familie.
