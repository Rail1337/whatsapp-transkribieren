package com.railarcanum.transkription;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nimmt eine geteilte Sprachnachricht (oder eine gewählte Audiodatei) an und transkribiert sie
 * offline auf Deutsch mit Whisper. Die Modelle lädt man in der App und kann mehrere behalten.
 */
public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 1;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView result;
    private Button modelBtn;
    private Button langBtn;
    private Button copyBtn;
    private Button shareBtn;

    private long ctx = 0;          // geladener Whisper-Kontext
    private String ctxModelId = ""; // zu welchem Modell er gehört
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshModelButton();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
        final long c = ctx;
        ctx = 0;
        if (c != 0) new Thread(() -> WhisperLib.freeContext(c)).start();
    }

    // ---------------------------------------------------------------- Oberfläche

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(32), pad, pad);

        TextView title = new TextView(this);
        title.setText("WhatsApp Transkription");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        modelBtn = new Button(this);
        modelBtn.setOnClickListener(v -> showModelList());
        root.addView(modelBtn);

        langBtn = new Button(this);
        langBtn.setOnClickListener(v -> showLanguageList());
        root.addView(langBtn);

        status = new TextView(this);
        status.setText("Sprachnachricht in WhatsApp gedrückt halten, Teilen wählen, diese App auswählen. Oder unten eine Datei wählen.");
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        result = new TextView(this);
        result.setTextIsSelectable(true);
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        result.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(result);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button pick = new Button(this);
        pick.setText("Datei wählen");
        pick.setOnClickListener(v -> pickFile());
        copyBtn = new Button(this);
        copyBtn.setText("Kopieren");
        copyBtn.setEnabled(false);
        copyBtn.setOnClickListener(v -> copyText());
        shareBtn = new Button(this);
        shareBtn.setText("Teilen");
        shareBtn.setEnabled(false);
        shareBtn.setOnClickListener(v -> shareText());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(pick, lp);
        row.addView(copyBtn, lp);
        row.addView(shareBtn, lp);
        root.addView(row);

        setContentView(root);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void refreshModelButton() {
        ModelStore.Model m = ModelStore.selected(this);
        modelBtn.setText("Modell: " + m.label + (ModelStore.isInstalled(this, m) ? "" : "  (nicht geladen)"));
        langBtn.setText("Sprache: " + selectedLanguage()[1]);
    }

    // ---------------------------------------------------------------- Sprache (Standard: Deutsch)

    /** Code für Whisper und Anzeigename. "auto" lässt Whisper die Sprache selbst erkennen. */
    private static final String[][] LANGUAGES = {
            {"de", "Deutsch"}, {"en", "Englisch"}, {"fr", "Französisch"}, {"es", "Spanisch"},
            {"it", "Italienisch"}, {"tr", "Türkisch"}, {"pl", "Polnisch"}, {"ru", "Russisch"},
            {"auto", "Automatisch erkennen"},
    };

    private String[] selectedLanguage() {
        String code = getSharedPreferences("einstellungen", MODE_PRIVATE).getString("sprache", "de");
        for (String[] l : LANGUAGES) if (l[0].equals(code)) return l;
        return LANGUAGES[0];
    }

    private void showLanguageList() {
        if (busy) { toastBusy(); return; }
        String[] names = new String[LANGUAGES.length];
        for (int i = 0; i < names.length; i++) names[i] = LANGUAGES[i][1];
        new AlertDialog.Builder(this)
                .setTitle("Sprache der Sprachnachricht")
                .setItems(names, (d, which) -> {
                    getSharedPreferences("einstellungen", MODE_PRIVATE).edit()
                            .putString("sprache", LANGUAGES[which][0]).apply();
                    refreshModelButton();
                })
                .setNegativeButton("Schließen", null)
                .show();
    }

    // ---------------------------------------------------------------- Modelle

    private void showModelList() {
        if (busy) { toastBusy(); return; }
        final ModelStore.Model[] all = ModelStore.MODELS;
        ModelStore.Model sel = ModelStore.selected(this);
        String[] items = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            ModelStore.Model m = all[i];
            items[i] = (m.id.equals(sel.id) ? "● " : "○ ") + m.label + " – " + m.sizeText()
                    + "\n" + (ModelStore.isInstalled(this, m) ? "geladen" : "nicht geladen") + ", " + m.hint;
        }
        new AlertDialog.Builder(this)
                .setTitle("Sprachmodell wählen")
                .setItems(items, (d, which) -> showModelActions(all[which]))
                .setNegativeButton("Schließen", null)
                .show();
    }

    private void showModelActions(final ModelStore.Model m) {
        if (ModelStore.isInstalled(this, m)) {
            new AlertDialog.Builder(this)
                    .setTitle(m.label)
                    .setItems(new String[]{"Verwenden", "Löschen (" + m.sizeText() + " frei)"}, (d, which) -> {
                        if (which == 0) {
                            ModelStore.select(this, m);
                            refreshModelButton();
                        } else {
                            ModelStore.delete(this, m);
                            if (ctxModelId.equals(m.id)) dropContext();
                            refreshModelButton();
                        }
                    })
                    .setNegativeButton("Zurück", (d, w) -> showModelList())
                    .show();
        } else {
            confirmDownload(m, () -> {
                ModelStore.select(this, m);
                refreshModelButton();
            });
        }
    }

    /** Fragt vor dem Laden nach, im Mobilfunknetz mit Hinweis auf das Datenvolumen. */
    private void confirmDownload(final ModelStore.Model m, final Runnable afterwards) {
        String msg = m.label + " ist " + m.sizeText() + " groß und wird einmalig geladen.";
        if (isMetered()) msg += "\n\nAchtung: Du bist im Mobilfunknetz, das verbraucht Datenvolumen. WLAN ist besser.";
        new AlertDialog.Builder(this)
                .setTitle("Modell laden?")
                .setMessage(msg)
                .setPositiveButton("Laden", (d, w) -> downloadModel(m, afterwards))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private boolean isMetered() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm != null && cm.isActiveNetworkMetered();
    }

    private void downloadModel(final ModelStore.Model m, final Runnable afterwards) {
        if (busy) { toastBusy(); return; }
        busy = true;
        status.setText("Lade " + m.label + " … 0 %");
        worker.execute(() -> {
            try {
                final int[] last = {-1};
                ModelStore.download(this, m, (done, total) -> {
                    int pct = (int) (done * 100 / total);
                    if (pct != last[0]) {
                        last[0] = pct;
                        setStatus("Lade " + m.label + " … " + pct + " %");
                    }
                });
                ui.post(() -> {
                    busy = false;
                    status.setText(m.label + " ist geladen.");
                    if (afterwards != null) afterwards.run();
                    refreshModelButton();
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    busy = false;
                    status.setText("Laden hat nicht geklappt: " + e.getMessage());
                    refreshModelButton();
                });
            }
        });
    }

    private void dropContext() {
        final long c = ctx;
        ctx = 0;
        ctxModelId = "";
        if (c != 0) worker.execute(() -> WhisperLib.freeContext(c));
    }

    // ---------------------------------------------------------------- Transkription

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        @SuppressWarnings("deprecation") // die neue Variante gibt es erst ab Android 13, wir laufen ab 8
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) startTranscription(uri);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, PICK_AUDIO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_AUDIO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            startTranscription(data.getData());
        }
    }

    private void startTranscription(final Uri uri) {
        if (busy) { toastBusy(); return; }
        ModelStore.Model m = ModelStore.selected(this);
        if (ModelStore.isInstalled(this, m)) {
            transcribe(uri, m);
        } else {
            // Erster Start: erst das gewählte Modell laden, dann gleich weitermachen.
            confirmDownload(m, () -> transcribe(uri, m));
        }
    }

    private void transcribe(final Uri uri, final ModelStore.Model model) {
        if (busy) { toastBusy(); return; }
        busy = true;
        result.setText("");
        copyBtn.setEnabled(false);
        shareBtn.setEnabled(false);
        status.setText("Lese Sprachnachricht …");

        worker.execute(() -> {
            try {
                float[] audio = AudioDecoder.decode(this, uri);
                float seconds = audio.length / (float) AudioDecoder.TARGET_RATE;

                if (ctx != 0 && !ctxModelId.equals(model.id)) {
                    WhisperLib.freeContext(ctx);
                    ctx = 0;
                    ctxModelId = "";
                }
                if (ctx == 0) {
                    setStatus("Lade " + model.label + " in den Speicher …");
                    ctx = WhisperLib.initContext(ModelStore.fileOf(this, model).getAbsolutePath());
                    if (ctx == 0) {
                        // Datei kaputt: löschen, damit sie beim nächsten Mal neu geladen wird.
                        ModelStore.delete(this, model);
                        throw new IllegalStateException(
                                "Das Modell ließ sich nicht öffnen. Ich habe es gelöscht, bitte neu laden.");
                    }
                    ctxModelId = model.id;
                }

                setStatus(String.format("Transkribiere %.0f Sekunden Audio …", seconds));
                long t0 = System.currentTimeMillis();
                int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
                int rc = WhisperLib.fullTranscribe(ctx, threads, audio, selectedLanguage()[0]);
                if (rc != 0) throw new IllegalStateException("Whisper meldet Fehler " + rc + ".");

                StringBuilder sb = new StringBuilder();
                int n = WhisperLib.getTextSegmentCount(ctx);
                for (int i = 0; i < n; i++) sb.append(WhisperLib.getTextSegment(ctx, i));
                final String text = sb.toString().trim();
                final float took = (System.currentTimeMillis() - t0) / 1000f;

                ui.post(() -> {
                    busy = false;
                    status.setText(String.format("Fertig. %.0f s Audio in %.1f s transkribiert.", seconds, took));
                    result.setText(text.isEmpty() ? "(Kein Text erkannt)" : text);
                    copyBtn.setEnabled(!text.isEmpty());
                    shareBtn.setEnabled(!text.isEmpty());
                    refreshModelButton();
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    busy = false;
                    status.setText("Das hat nicht geklappt: " + e.getMessage());
                    refreshModelButton();
                });
            }
        });
    }

    // ---------------------------------------------------------------- Kleinkram

    private void setStatus(final String s) {
        ui.post(() -> status.setText(s));
    }

    private void toastBusy() {
        Toast.makeText(this, "Es läuft gerade etwas, bitte kurz warten.", Toast.LENGTH_SHORT).show();
    }

    private void copyText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Transkript", result.getText()));
        Toast.makeText(this, "Text kopiert", Toast.LENGTH_SHORT).show();
    }

    private void shareText() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, result.getText().toString());
        startActivity(Intent.createChooser(send, "Transkript teilen"));
    }
}
