# WhatsApp Transkription

Android-App, die WhatsApp-Sprachnachrichten offline auf Deutsch in Text umwandelt. WhatsApp selbst transkribiert nur Englisch, hier läuft Whisper (`small`, quantisiert) direkt auf dem Handy.

- In WhatsApp Sprachnachricht gedrückt halten, **Teilen**, **WhatsApp Transkription** wählen. Alternativ in der App eine Audiodatei auswählen.
- Der Text lässt sich kopieren oder weiterteilen.
- Die App hat keine Internet-Berechtigung. Nichts verlässt das Handy.

## Aufbau

| Teil | Was |
|---|---|
| `app/src/main/cpp/` | JNI-Brücke zu whisper.cpp, Sprache fest auf `de` |
| `AudioDecoder.java` | Opus/Ogg per `MediaCodec` lesen, auf 16 kHz Mono bringen |
| `WhisperLib.java` | lädt die native Bibliothek (fp16-Variante, wenn der Prozessor sie kann) |
| `MainActivity.java` | Teilen-Ziel, Oberfläche, Kopieren/Teilen |

## Bauen

`third_party/` und das Modell liegen nicht im Repo (zu groß). Einmalig holen:

```bash
git clone --depth 1 https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp
curl -L -o app/src/main/assets/ggml-small-q5_1.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin
```

Dann `./gradlew.bat assembleDebug`. Gebaut wird nur nach ausdrücklichem Go. Voraussetzungen: Android SDK, NDK `27.2.12479018`, CMake `3.22.1`, JDK 21 (`org.gradle.java.home` in `gradle.properties`).

## Stand

Version 0.1: Java und nativer Code kompilieren. Noch nicht auf einem Handy getestet (Erkennungsqualität, Dauer pro Minute Audio, Speicherbedarf offen).
