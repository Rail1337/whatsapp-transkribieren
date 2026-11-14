package com.railarcanum.transkription;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nimmt eine geteilte Sprachnachricht (oder eine gewählte Audiodatei) an und transkribiert sie
 * offline auf Deutsch mit Whisper.
 */
public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 1;
    private static final String MODEL_ASSET = "ggml-small-q5_1.bin";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView result;
    private Button copyBtn;
    private Button shareBtn;

    private long ctx = 0; // Whisper-Kontext, wird beim ersten Mal geladen
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
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

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(32), pad, pad);

        TextView title = new TextView(this);
        title.setText("WhatsApp Transkription");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

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

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        @SuppressWarnings("deprecation") // die neue Variante gibt es erst ab Android 13, wir laufen ab 8
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) transcribe(uri);
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
            transcribe(data.getData());
        }
    }

    private void transcribe(final Uri uri) {
        if (busy) {
            Toast.makeText(this, "Es läuft schon eine Transkription.", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        result.setText("");
        copyBtn.setEnabled(false);
        shareBtn.setEnabled(false);
        status.setText("Lese Sprachnachricht …");

        worker.execute(() -> {
            try {
                float[] audio = AudioDecoder.decode(this, uri);
                float seconds = audio.length / (float) AudioDecoder.TARGET_RATE;

                if (ctx == 0) {
                    setStatus("Lade Sprachmodell (nur beim ersten Mal) …");
                    ctx = WhisperLib.initContextFromAsset(getAssets(), MODEL_ASSET);
                    if (ctx == 0) throw new IllegalStateException("Sprachmodell konnte nicht geladen werden.");
                }

                setStatus(String.format("Transkribiere %.0f Sekunden Audio …", seconds));
                long t0 = System.currentTimeMillis();
                int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
                WhisperLib.fullTranscribe(ctx, threads, audio);

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
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    busy = false;
                    status.setText("Das hat nicht geklappt: " + e.getMessage());
                });
            }
        });
    }

    private void setStatus(final String s) {
        ui.post(() -> status.setText(s));
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
