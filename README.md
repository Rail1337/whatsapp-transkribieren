# WhatsApp Transkription

Eine Android-App, die WhatsApp-Sprachnachrichten **auf dem Handy** in Text umwandelt, auf Deutsch und in anderen Sprachen. WhatsApp selbst transkribiert Sprachnachrichten nur in wenigen Sprachen, hier läuft [Whisper](https://github.com/openai/whisper) über [whisper.cpp](https://github.com/ggml-org/whisper.cpp) direkt auf dem Gerät. Kein Server, kein Konto, keine Cloud. *by Rail*

## Download

Die fertige APK gibt es unter **[Releases](../../releases/latest)**. Voraussetzung: Android 8 oder neuer, 64-Bit-Handy.

1. APK auf dem Handy öffnen und die Installation aus dieser Quelle einmal erlauben (die App kommt nicht aus dem Play Store, Play Protect kann warnen).
2. Updates: neue APK einfach über die alte installieren. Modelle, Namen und Texte bleiben erhalten, solange der Signaturschlüssel derselbe ist.

Alle offiziellen Releases sind mit demselben Schlüssel signiert. SHA-256-Fingerabdruck des Zertifikats:

```
9F:7F:97:04:25:31:36:80:39:55:A1:CB:A2:F8:C3:74:01:FA:B4:E6:AF:C0:8E:A8:FD:6D:62:FD:18:81:B9:66
```

Prüfen mit `apksigner verify --print-certs <datei>.apk`. Stimmt er nicht, stammt die APK nicht von hier. Wer selbst baut, signiert mit dem eigenen Schlüssel und bekommt einen anderen Fingerabdruck. Das ist so gewollt.

## Funktionen

- **Teilen aus WhatsApp:** Sprachnachricht gedrückt halten, *Teilen*, *WhatsApp Transkription* wählen.
- **Liste statt App-Wechsel:** Den WhatsApp-Sprachnachrichten-Ordner einmal verbinden (Android-Ordnerauswahl, nur dieser Ordner, nur lesen). Danach stehen die Nachrichten in der App, neueste zuerst. Auch WhatsApp Business wird erkannt.
- **Anhören** mit Leiste zum Vor- und Zurückspulen, **Namen** pro Nachricht (WhatsApp verrät der App nicht, von wem eine Nachricht stammt, deshalb von Hand).
- **Fertige Texte werden gemerkt** (pro Nachricht, mit Modell und Sprache), die Liste zeigt eine Vorschau, dazu *Neu transkribieren* und *Text löschen*.
- **Fortschrittsbalken** beim Laden der Modelle und beim Transkribieren: echte Prozent von Whisper, Position im Audio, grobe Restzeit.
- **Modelle** `base` (60 MB), `small` (190 MB, empfohlen), `medium` (540 MB). Die App lädt sie bei Bedarf (SHA-256-geprüft) und lässt mehrere parallel zu.
- **Sprache:** Standard Deutsch, umstellbar auf einige weitere Sprachen oder automatische Erkennung.
- Kopieren und Teilen des Textes, eine Info-Seite in der App erklärt Modelle und Funktionen.

## Datenschutz

- Die Transkription läuft **komplett lokal**. Sprachnachrichten und Texte verlassen das Handy nie.
- Das Netz nutzt die App ausschließlich, um die Sprachmodelle von `huggingface.co` zu laden (feste Adressen, Prüfsumme gegen unvollständige oder veränderte Dateien, siehe `ModelStore.java`). Berechtigungen: Internet, Netzwerkstatus. Keine Kontakte, kein Mikrofon, kein Standort, kein Zugriff auf den ganzen Speicher.
- Der Code ist offen, damit das jeder nachprüfen kann.

## Selbst bauen

Voraussetzungen: Android SDK (Plattform 35), NDK `27.2.12479018`, CMake `3.22.1`, ein JDK ab 17 (getestet mit 21 und 25).

```bash
git clone https://github.com/Rail1337/whatsapp-transkribieren
cd whatsapp-transkribieren
git clone --depth 1 https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp
echo "sdk.dir=/pfad/zum/Android/Sdk" > local.properties
./gradlew assembleDebug
```

Das Debug-Build braucht keinen Signaturschlüssel und enthält zusätzlich `x86_64`, damit es im Emulator läuft.

**Release bauen:** Lege einen eigenen Schlüssel an und ein Verzeichnis mit einer `keystore.properties`:

```
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Pfad per Umgebungsvariable `TRANSKRIPTION_SIGNING_DIR` setzen (Standard: Nachbarordner `../whatsapp-transkribieren-signing`), dann `./gradlew assembleRelease`. Der Schlüssel gehört nie in ein Repo.

## Aufbau

| Teil | Was |
|---|---|
| `app/src/main/cpp/` | JNI-Brücke zu whisper.cpp, Sprache und Fortschritts-Callback werden übergeben |
| `AudioDecoder.java` | Opus/Ogg per `MediaCodec` lesen, auf 16 kHz Mono bringen |
| `WhisperLib.java` | lädt die native Bibliothek (fp16-Variante, wenn der Prozessor sie kann) |
| `ModelStore.java` | Modelle laden, prüfen, löschen, auswählen (neues Modell: eine Zeile) |
| `VoiceNotes.java` | WhatsApp-Ordner vorschlagen, Nachrichten per Ordnerzugriff (SAF) listen |
| `TranscriptStore.java` | fertige Transkriptionen lokal merken |
| `MeterBar.java` | Fortschrittsbalken |
| `MainActivity.java` | Oberfläche (Waldgrün, Violett, Gold), Teilen-Ziel, Ablauf |

## Mitmachen

Fehlermeldungen und Vorschläge sind willkommen (Issues). Am nützlichsten sind Berichte zu **Geräten und Android-Versionen**, auf denen der WhatsApp-Ordner anders liegt oder die Transkription zu langsam ist, und zu Sprachen, in denen die Erkennung schwächelt. Pull Requests gerne klein und mit kurzer Begründung. Die App ist bisher auf einem echten Handy im Einsatz, mehr Geräte wurden noch nicht getestet.

## Lizenz

[GPL-3.0](LICENSE). Wer den Code ändert und weitergibt, muss seine Fassung ebenfalls unter der GPL offenlegen. Software von Dritten (whisper.cpp, Whisper, MIT): siehe [NOTICES.md](NOTICES.md).

Diese App ist nicht von WhatsApp oder Meta, steht in keiner Verbindung zu ihnen und wird nicht von ihnen unterstützt. Die Erkennung ist nicht fehlerfrei, bei Nuscheln, Dialekt oder Lärm hilft ein größeres Modell.
