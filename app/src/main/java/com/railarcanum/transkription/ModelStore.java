package com.railarcanum.transkription;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Verwaltet die Whisper-Modelle: laden, prüfen, löschen, auswählen.
 * Das ist der einzige Ort, an dem die App das Netz nutzt, und nur für diese festen Adressen.
 */
final class ModelStore {
    static final class Model {
        final String id, label, hint, file, sha256;
        final long size;

        Model(String id, String label, String hint, String file, long size, String sha256) {
            this.id = id; this.label = label; this.hint = hint;
            this.file = file; this.size = size; this.sha256 = sha256;
        }

        String url() {
            return "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + file;
        }

        String sizeText() {
            return Math.round(size / 1_000_000f) + " MB";
        }
    }

    /** Neue Modelle: hier eine Zeile ergänzen (Größe und SHA-256 stehen auf huggingface.co beim Modell). */
    static final Model[] MODELS = {
            new Model("base", "Schnell (base)", "für schwache Handys, einfache Sprache",
                    "ggml-base-q5_1.bin", 59_707_625L,
                    "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
            new Model("small", "Ausgewogen (small)", "empfohlen für die meisten Handys",
                    "ggml-small-q5_1.bin", 190_085_487L,
                    "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
            new Model("medium", "Genau (medium)", "am besten, aber langsam und nur für starke Handys",
                    "ggml-medium-q5_0.bin", 539_212_467L,
                    "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f"),
    };

    interface Progress { void onProgress(long done, long total); }

    private static final String PREFS = "modelle";
    private static final String KEY_SELECTED = "gewaehlt";

    private ModelStore() {}

    static File fileOf(Context ctx, Model m) {
        return new File(ctx.getFilesDir(), m.file);
    }

    static boolean isInstalled(Context ctx, Model m) {
        File f = fileOf(ctx, m);
        return f.isFile() && f.length() == m.size;
    }

    static Model selected(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString(KEY_SELECTED, "small");
        for (Model m : MODELS) if (m.id.equals(id)) return m;
        return MODELS[1];
    }

    static void select(Context ctx, Model m) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SELECTED, m.id).apply();
    }

    static void delete(Context ctx, Model m) {
        // Eine kaputte Datei darf nicht liegen bleiben, sonst lädt die App sie nie neu.
        //noinspection ResultOfMethodCallIgnored
        fileOf(ctx, m).delete();
    }

    /** Lädt das Modell in eine .part-Datei, prüft die SHA-256-Summe und benennt dann erst um. */
    static void download(Context ctx, Model m, Progress progress) throws IOException {
        File dir = ctx.getFilesDir();
        if (dir.getUsableSpace() < m.size + 50_000_000L) {
            throw new IOException("Nicht genug freier Speicher (" + m.sizeText() + " nötig).");
        }
        File part = new File(dir, m.file + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(m.url()).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        try {
            if (c.getResponseCode() != 200) {
                throw new IOException("Server antwortet mit Fehler " + c.getResponseCode() + ".");
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long done = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    progress.onProgress(done, m.size);
                }
            }
            if (done != m.size || !hex(sha.digest()).equals(m.sha256)) {
                throw new IOException("Die geladene Datei ist unvollständig oder verändert.");
            }
            File target = fileOf(ctx, m);
            if (!part.renameTo(target)) throw new IOException("Modell konnte nicht gespeichert werden.");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            c.disconnect();
            if (part.exists()) //noinspection ResultOfMethodCallIgnored
                part.delete();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
