package com.railarcanum.transkription;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Merkt sich fertige Transkriptionen pro Sprachnachricht (Schlüssel: Dateiname aus dem WhatsApp-Ordner),
 * damit ein erneutes Antippen den Text sofort zeigt. Alles bleibt lokal auf dem Handy.
 */
final class TranscriptStore {
    static final class Entry {
        final String text;
        final String modelId;
        final String lang;
        final long time;

        Entry(String text, String modelId, String lang, long time) {
            this.text = text; this.modelId = modelId; this.lang = lang; this.time = time;
        }
    }

    private static final String PREFS = "transkripte";
    private static final int MAX_ENTRIES = 300; // Ältere werden verdrängt, damit der Speicher nicht wächst

    private TranscriptStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Entry get(Context ctx, String key) {
        String raw = prefs(ctx).getString(key, null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            return new Entry(o.getString("text"), o.optString("model"), o.optString("lang"), o.optLong("time"));
        } catch (JSONException e) {
            // Beschädigter Eintrag: wegwerfen, dann wird einfach neu transkribiert.
            prefs(ctx).edit().remove(key).apply();
            return null;
        }
    }

    static void put(Context ctx, String key, String text, String modelId, String lang) {
        try {
            JSONObject o = new JSONObject();
            o.put("text", text);
            o.put("model", modelId);
            o.put("lang", lang);
            o.put("time", System.currentTimeMillis());
            prefs(ctx).edit().putString(key, o.toString()).apply();
            trim(ctx);
        } catch (JSONException e) {
            throw new IllegalStateException("Transkription konnte nicht gespeichert werden.", e);
        }
    }

    static void delete(Context ctx, String key) {
        prefs(ctx).edit().remove(key).apply();
    }

    /** Behält nur die neuesten MAX_ENTRIES Einträge. */
    private static void trim(Context ctx) {
        Map<String, ?> all = prefs(ctx).getAll();
        if (all.size() <= MAX_ENTRIES) return;
        List<Object[]> items = new ArrayList<>();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            long t = 0;
            try { t = new JSONObject(String.valueOf(e.getValue())).optLong("time"); } catch (JSONException ignored) { }
            items.add(new Object[]{e.getKey(), t});
        }
        items.sort((a, b) -> Long.compare((Long) a[1], (Long) b[1]));
        SharedPreferences.Editor ed = prefs(ctx).edit();
        for (int i = 0; i < items.size() - MAX_ENTRIES; i++) ed.remove((String) items.get(i)[0]);
        ed.apply();
    }
}
