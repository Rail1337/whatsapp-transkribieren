# WhatsApp Transkription

Android-App, die WhatsApp-Sprachnachrichten offline auf Deutsch in Text umwandelt. WhatsApp selbst transkribiert nur Englisch, hier läuft Whisper (`small`, quantisiert) direkt auf dem Handy.

- In WhatsApp Sprachnachricht gedrückt halten, **Teilen**, **WhatsApp Transkription** wählen. Alternativ in der App eine Audiodatei auswählen.
- Oder den WhatsApp-Sprachnachrichten-Ordner einmal verbinden (Android-Ordnerauswahl, nur lesen, nur dieser Ordner): dann stehen die Nachrichten als Liste in der App, antippen genügt.
- Jede Nachricht lässt sich in der Liste anhören (Dreieck) und mit einem selbst vergebenen Namen versehen (Stift). WhatsApp speichert nicht, von wem eine Nachricht ist, deshalb geht das nicht automatisch.
- Beim Abspielen erscheint eine Leiste mit goldenem Dot: ziehen zum Vor- und Zurückspulen, dazu Zeit und Gesamtlänge.
- Fortschrittsbalken für das Laden der Modelle und für die Transkription (echte Prozent von Whisper, Position im Audio und grobe Restzeit, Muster aus dem transcript-tool).
- Der Info-Knopf erklärt Modelle und Funktionen.
- Der Text lässt sich kopieren oder weiterteilen.
- Sprachmodelle (base 60 MB, small 190 MB, medium 540 MB) lädt man in der App und kann mehrere behalten. Beim ersten Start wird das gewählte Modell geladen (Prüfsumme SHA-256, im Mobilfunknetz mit Warnung). Das ist der einzige Netzzugriff, Sprachnachrichten und Texte verlassen das Handy nie.
- Sprache: Standard Deutsch, in der App umstellbar (auch automatische Erkennung).

## Aufbau

| Teil | Was |
|---|---|
| `app/src/main/cpp/` | JNI-Brücke zu whisper.cpp, Sprache fest auf `de` |
| `AudioDecoder.java` | Opus/Ogg per `MediaCodec` lesen, auf 16 kHz Mono bringen |
| `ModelStore.java` | Modelle laden, prüfen, löschen, auswählen (neue Modelle: eine Zeile) |
| `WhisperLib.java` | lädt die native Bibliothek (fp16-Variante, wenn der Prozessor sie kann) |
| `VoiceNotes.java` | WhatsApp-Ordner vorschlagen (auch WhatsApp Business), Nachrichten per Ordnerzugriff listen |
| `MainActivity.java` | Teilen-Ziel, Oberfläche im Versania-Stil (Waldgrün, Violett, Gold), Kopieren/Teilen |

## Bauen

`third_party/` liegt nicht im Repo (zu groß). Einmalig holen:

```bash
git clone --depth 1 https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp
```

Dann `./gradlew.bat assembleDebug`. Gebaut wird nur nach ausdrücklichem Go. Voraussetzungen: Android SDK, NDK `27.2.12479018`, CMake `3.22.1`, JDK 21 (`org.gradle.java.home` in `gradle.properties`).

## Signierschlüssel

Der Schlüssel liegt **nicht** in diesem Repo, sondern im getrennten privaten Repo `whatsapp-transkribieren-signing`. Dieses Repo kann deshalb öffentlich sein. Für Release-Builds muss das Schlüssel-Repo als Nachbarordner liegen (`../whatsapp-transkribieren-signing`) oder `TRANSKRIPTION_SIGNING_DIR` auf seinen Ordner zeigen. Ohne Schlüssel bricht das Release-Build mit einer klaren Meldung ab. Ein Schlüsselwechsel hieße: alle müssen deinstallieren (Modelle und Namen weg), also den Schlüssel gut sichern.

## Stand

Version 0.2: Im Emulator (x86_64, nur in der Debug-Variante) läuft die ganze Kette durch: Ordner verbinden, Abspielen mit Spulen, Modell laden mit Prüfsumme, Transkription mit Prozentanzeige. Auf einem echten Handy noch nicht getestet (Erkennungsqualität, Tempo, Speicherbedarf offen).
