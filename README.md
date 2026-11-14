# WhatsApp Transkription

Android-App, die WhatsApp-Sprachnachrichten offline auf Deutsch in Text umwandelt. WhatsApp selbst transkribiert nur Englisch, hier läuft Whisper (`small`, quantisiert) direkt auf dem Handy.

- In WhatsApp Sprachnachricht gedrückt halten, **Teilen**, **WhatsApp Transkription** wählen. Alternativ in der App eine Audiodatei auswählen.
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
| `MainActivity.java` | Teilen-Ziel, Oberfläche, Kopieren/Teilen |

## Bauen

`third_party/` liegt nicht im Repo (zu groß). Einmalig holen:

```bash
git clone --depth 1 https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp
```

Dann `./gradlew.bat assembleDebug`. Gebaut wird nur nach ausdrücklichem Go. Voraussetzungen: Android SDK, NDK `27.2.12479018`, CMake `3.22.1`, JDK 21 (`org.gradle.java.home` in `gradle.properties`).

## Stand

Version 0.1: Java und nativer Code kompilieren, APK ist klein (kein Modell eingebaut). Noch nicht auf einem Handy getestet (Erkennungsqualität, Dauer pro Minute Audio, Speicherbedarf offen).
