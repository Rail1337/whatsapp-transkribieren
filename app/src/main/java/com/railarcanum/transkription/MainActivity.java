package com.railarcanum.transkription;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nimmt eine geteilte Sprachnachricht, eine gewählte Datei oder eine Nachricht aus der Liste des
 * verbundenen WhatsApp-Ordners an und transkribiert sie offline mit Whisper.
 */
public class MainActivity extends Activity {
    // Versania-Farben (wie beim NPC-Generator)
    private static final int BG = 0xFF08200D;
    private static final int PANEL = 0xFF132318;
    private static final int BTN_IDLE = 0xFF33293F;
    private static final int BTN_ACTIVE = 0xFF5B3E82;
    private static final int GOLD = 0xFFB8973A;
    private static final int GOLD_LIGHT = 0xFFD9B34D;
    private static final int TEXT = 0xFFEDE6F5;
    private static final int MUTED = 0xFF9C9488;
    private static final int GREEN = 0xFF8FD67A;

    private static final int PICK_AUDIO = 1;
    private static final int PICK_FOLDER = 2;
    private static final String PREFS = "einstellungen";
    private static final String KEY_FOLDER = "wa_ordner";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView result;
    private TextView resultTitle;
    private LinearLayout resultCard;
    private LinearLayout noteRows;
    private TextView noteHint;
    private Button connectBtn;
    private Button modelBtn;
    private Button langBtn;
    private Button copyBtn;
    private Button shareBtn;

    private List<VoiceNotes.Note> lastNotes = new ArrayList<>();
    private MediaPlayer player;
    private Uri playingUri;
    private ImageView playingBtn;
    private String titleForNext = null;

    private long ctx = 0;           // geladener Whisper-Kontext
    private String ctxModelId = ""; // zu welchem Modell er gehört
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshButtons();
        refreshNotes();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (noteRows != null && !busy) refreshNotes(); // neue Nachrichten, wenn man aus WhatsApp zurückkommt
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        worker.shutdown();
        final long c = ctx;
        ctx = 0;
        if (c != 0) new Thread(() -> WhisperLib.freeContext(c)).start();
    }

    // ---------------------------------------------------------------- Oberfläche

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int t, b, l, r;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                t = i.top; b = i.bottom; l = i.left; r = i.right;
            } else {
                //noinspection deprecation
                t = insets.getSystemWindowInsetTop();
                //noinspection deprecation
                b = insets.getSystemWindowInsetBottom();
                //noinspection deprecation
                l = insets.getSystemWindowInsetLeft();
                //noinspection deprecation
                r = insets.getSystemWindowInsetRight();
            }
            v.setPadding(l + dp(16), t + dp(14), r + dp(16), b + dp(12));
            return insets;
        });

        // Kopf: Titel, "by Rail" und Info
        TextView title = new TextView(this);
        title.setText("WhatsApp Transkription");
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        title.setTextColor(GOLD_LIGHT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        root.addView(title);

        LinearLayout byRow = new LinearLayout(this);
        byRow.setOrientation(LinearLayout.HORIZONTAL);
        byRow.setGravity(Gravity.CENTER_VERTICAL);
        byRow.setPadding(0, dp(2), 0, dp(12));
        TextView by = new TextView(this);
        by.setText("by Rail  ·  läuft komplett auf deinem Handy");
        by.setTypeface(Typeface.SERIF, Typeface.ITALIC);
        by.setTextColor(MUTED);
        by.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        byRow.addView(by, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button info = textButton("Info");
        info.setOnClickListener(v -> showInfo());
        byRow.addView(info);
        root.addView(byRow);

        // Modell und Sprache
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        modelBtn = button("", false);
        modelBtn.setOnClickListener(v -> showModelList());
        langBtn = button("", false);
        langBtn.setOnClickListener(v -> showLanguageList());
        chips.addView(modelBtn, weight(0, 6));
        chips.addView(langBtn, weight(6, 0));
        root.addView(chips);

        // Listenkopf
        LinearLayout listHead = new LinearLayout(this);
        listHead.setOrientation(LinearLayout.HORIZONTAL);
        listHead.setGravity(Gravity.CENTER_VERTICAL);
        listHead.setPadding(0, dp(16), 0, dp(2));
        TextView listTitle = new TextView(this);
        listTitle.setText("Sprachnachrichten");
        listTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
        listTitle.setTextColor(TEXT);
        listTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        listHead.addView(listTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = textButton("Neu laden");
        refresh.setOnClickListener(v -> refreshNotes());
        Button change = textButton("Ordner ändern");
        change.setOnClickListener(v -> askConnectFolder());
        listHead.addView(refresh);
        listHead.addView(change);
        root.addView(listHead);

        TextView usage = new TextView(this);
        usage.setText("Antippen: transkribieren   Dreieck: anhören   Stift: Namen vergeben");
        usage.setTextColor(MUTED);
        usage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        usage.setPadding(dp(2), 0, 0, dp(8));
        root.addView(usage);

        // Liste der Sprachnachrichten
        ScrollView listScroll = new ScrollView(this);
        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        noteHint = new TextView(this);
        noteHint.setTextColor(MUTED);
        noteHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        noteHint.setPadding(dp(4), dp(4), dp(4), dp(8));
        listBox.addView(noteHint);
        connectBtn = button("WhatsApp-Ordner verbinden", true);
        connectBtn.setOnClickListener(v -> askConnectFolder());
        listBox.addView(connectBtn);
        noteRows = new LinearLayout(this);
        noteRows.setOrientation(LinearLayout.VERTICAL);
        listBox.addView(noteRows);
        listScroll.addView(listBox);
        root.addView(listScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Status
        status = new TextView(this);
        status.setTextColor(GREEN);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setPadding(dp(2), dp(8), dp(2), dp(6));
        root.addView(status);

        // Ergebnis
        resultCard = new LinearLayout(this);
        resultCard.setOrientation(LinearLayout.VERTICAL);
        resultCard.setBackground(round(PANEL, dp(14), BTN_IDLE, dp(1)));
        resultCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        resultCard.setVisibility(View.GONE);
        resultTitle = new TextView(this);
        resultTitle.setTextColor(GOLD_LIGHT);
        resultTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
        resultTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        resultTitle.setPadding(0, 0, 0, dp(6));
        resultCard.addView(resultTitle);
        ScrollView resultScroll = new ScrollView(this);
        result = new TextView(this);
        result.setTextIsSelectable(true);
        result.setTextColor(TEXT);
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        resultScroll.addView(result);
        resultCard.addView(resultScroll);
        root.addView(resultCard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Unten
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        Button pick = button("Datei wählen", false);
        pick.setOnClickListener(v -> pickFile());
        copyBtn = button("Kopieren", true);
        copyBtn.setOnClickListener(v -> copyText());
        shareBtn = button("Teilen", true);
        shareBtn.setOnClickListener(v -> shareText());
        setEnabledLook(copyBtn, false);
        setEnabledLook(shareBtn, false);
        row.addView(pick, weight(0, 6));
        row.addView(copyBtn, weight(0, 6));
        row.addView(shareBtn, weight(0, 0));
        root.addView(row);

        setContentView(root);
    }

    private LinearLayout.LayoutParams weight(int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(leftDp), 0, dp(rightDp), 0);
        return lp;
    }

    private GradientDrawable round(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeWidth > 0) g.setStroke(strokeWidth, stroke);
        return g;
    }

    /** Violetter Knopf mit Goldrand (wichtig) oder dunkelvioletter (normal). */
    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.SERIF, primary ? Typeface.BOLD : Typeface.NORMAL);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(46));
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = primary ? round(BTN_ACTIVE, dp(12), GOLD, dp(1))
                : round(BTN_IDLE, dp(12), BTN_ACTIVE, dp(1));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        return b;
    }

    private Button textButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(GOLD_LIGHT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(null);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        return b;
    }

    private void setEnabledLook(Button b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.4f);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void refreshButtons() {
        ModelStore.Model m = ModelStore.selected(this);
        modelBtn.setText("Modell: " + m.id + (ModelStore.isInstalled(this, m) ? "" : "  ↓"));
        langBtn.setText("Sprache: " + selectedLanguage()[1]);
    }

    // ---------------------------------------------------------------- WhatsApp-Ordner

    private String savedFolder() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FOLDER, null);
    }

    private void askConnectFolder() {
        if (busy) { toastBusy(); return; }
        new AlertDialog.Builder(this)
                .setTitle("WhatsApp-Ordner verbinden")
                .setMessage("Im nächsten Fenster öffnet sich der Ordner mit deinen Sprachnachrichten. "
                        + "Tippe unten auf „Diesen Ordner verwenden“ und bestätige mit „Zulassen“. "
                        + "Die App liest nur diesen Ordner und nur Audiodateien.")
                .setPositiveButton("Weiter", (d, w) -> openFolderPicker())
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void openFolderPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, VoiceNotes.suggestedFolder(this));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, PICK_FOLDER);
        } catch (Exception e) {
            status.setText("Der Ordner-Auswähler lässt sich nicht öffnen: " + e.getMessage());
        }
    }

    private void refreshNotes() {
        String saved = savedFolder();
        noteRows.removeAllViews();
        if (saved == null) {
            noteHint.setVisibility(View.VISIBLE);
            noteHint.setText("Verbinde einmal deinen WhatsApp-Ordner, dann stehen die Sprachnachrichten hier "
                    + "zum Antippen und du musst nicht zwischen den Apps wechseln.");
            connectBtn.setVisibility(View.VISIBLE);
            return;
        }
        connectBtn.setVisibility(View.GONE);
        noteHint.setVisibility(View.VISIBLE);
        noteHint.setText("Lese Ordner …");
        final Uri tree = Uri.parse(saved);
        worker.execute(() -> {
            try {
                final List<VoiceNotes.Note> notes = VoiceNotes.list(this, tree);
                ui.post(() -> renderNotes(notes));
            } catch (final Exception e) {
                // Zugriff weg (z. B. Ordner gelöscht oder Recht entzogen): neu verbinden lassen.
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_FOLDER).apply();
                ui.post(() -> {
                    refreshNotes();
                    status.setText("Der Ordner ist nicht mehr lesbar, bitte neu verbinden.");
                });
            }
        });
    }

    private void renderNotes(List<VoiceNotes.Note> notes) {
        lastNotes = notes;
        stopPlayback();
        noteRows.removeAllViews();
        if (notes.isEmpty()) {
            noteHint.setVisibility(View.VISIBLE);
            noteHint.setText("Keine Sprachnachrichten gefunden. Ist der richtige Ordner verbunden? "
                    + "Mit „Ordner ändern“ kannst du ihn neu wählen.");
            return;
        }
        noteHint.setVisibility(View.GONE);
        for (final VoiceNotes.Note n : notes) {
            final String label = nameOf(n);
            final String when = whenText(n.modified);
            final String sizeText = n.size > 0 ? Math.max(1, n.size / 1024) + " KB" : "";

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                    round(PANEL, dp(12), BTN_IDLE, dp(1)), null));
            row.setClickable(true);
            row.setOnClickListener(v -> startTranscription(n.uri, label != null ? label + " · " + when : when));

            final ImageView play = iconButton(R.drawable.ic_play, true);
            play.setOnClickListener(v -> togglePlay(n.uri, play));
            row.addView(play, new LinearLayout.LayoutParams(dp(46), dp(46)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(12), 0, dp(8), 0);
            TextView line1 = new TextView(this);
            line1.setText(label != null ? label : when);
            line1.setTextColor(TEXT);
            line1.setTypeface(Typeface.SERIF, label != null ? Typeface.BOLD : Typeface.NORMAL);
            line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            TextView line2 = new TextView(this);
            line2.setText((label != null ? when + " · " : "") + sizeText);
            line2.setTextColor(MUTED);
            line2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            texts.addView(line1);
            texts.addView(line2);
            row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            ImageView edit = iconButton(R.drawable.ic_edit, false);
            edit.setOnClickListener(v -> editName(n));
            row.addView(edit, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            noteRows.addView(row, lp);
        }
    }

    private ImageView iconButton(int res, boolean filled) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setClickable(true);
        GradientDrawable bg = filled ? round(BTN_ACTIVE, dp(23), GOLD, dp(1)) : round(0x00000000, dp(21), 0, 0);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        return v;
    }

    private static String whenText(long millis) {
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(millis);
        int diff = (int) ((startOfDay(now) - startOfDay(t)) / 86_400_000L);
        String time = new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(millis));
        if (diff == 0) return "Heute · " + time;
        if (diff == 1) return "Gestern · " + time;
        return new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(new Date(millis)) + " · " + time;
    }

    private static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    // ---------------------------------------------------------------- Abspielen

    private void togglePlay(Uri uri, ImageView btn) {
        boolean same = player != null && uri.equals(playingUri);
        stopPlayback();
        if (same) return;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            player.setDataSource(this, uri);
            player.setOnCompletionListener(mp -> stopPlayback());
            player.prepare();
            player.start();
            playingUri = uri;
            playingBtn = btn;
            btn.setImageResource(R.drawable.ic_stop);
        } catch (Exception e) {
            stopPlayback();
            status.setText("Abspielen nicht möglich: " + e.getMessage());
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (playingBtn != null) playingBtn.setImageResource(R.drawable.ic_play);
        playingBtn = null;
        playingUri = null;
    }

    // ---------------------------------------------------------------- Namen

    private SharedPreferences names() {
        return getSharedPreferences("namen", MODE_PRIVATE);
    }

    /** WhatsApp speichert nicht, von wem eine Nachricht ist, deshalb vergibt man den Namen selbst. */
    private String nameOf(VoiceNotes.Note n) {
        return names().getString(n.name, null);
    }

    private void editName(final VoiceNotes.Note n) {
        TreeSet<String> known = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Object v : names().getAll().values()) known.add(String.valueOf(v));
        if (known.isEmpty()) { askNewName(n); return; }
        final List<String> items = new ArrayList<>(known);
        final int newIdx = items.size();
        items.add("Neuer Name …");
        final int removeIdx = nameOf(n) != null ? items.size() : -1;
        if (removeIdx >= 0) items.add("Namen entfernen");
        new AlertDialog.Builder(this)
                .setTitle("Von wem ist die Nachricht?")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == newIdx) { askNewName(n); return; }
                    if (which == removeIdx) names().edit().remove(n.name).apply();
                    else names().edit().putString(n.name, items.get(which)).apply();
                    renderNotes(lastNotes);
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void askNewName(final VoiceNotes.Note n) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setSingleLine(true);
        String cur = nameOf(n);
        if (cur != null) input.setText(cur);
        new AlertDialog.Builder(this)
                .setTitle("Name für diese Nachricht")
                .setMessage("Zum Beispiel der Name aus dem Chat. Die App merkt ihn sich.")
                .setView(input)
                .setPositiveButton("Speichern", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) names().edit().remove(n.name).apply();
                    else names().edit().putString(n.name, name).apply();
                    renderNotes(lastNotes);
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    // ---------------------------------------------------------------- Info

    private void showInfo() {
        String text = "MODELLE\n"
                + "Alle laufen auf deinem Handy. Du lädst sie einmal, danach geht es ohne Netz. "
                + "Du kannst mehrere behalten und jederzeit wechseln.\n\n"
                + "base (60 MB): am schnellsten, läuft auch auf älteren Handys. Gut bei klarer Sprache, "
                + "macht bei Nuscheln, Dialekt oder Lärm mehr Fehler.\n\n"
                + "small (190 MB): der Mittelweg und für die meisten Handys die beste Wahl. "
                + "Deutlich genauer als base.\n\n"
                + "medium (540 MB): am genauesten, auch bei Dialekt und Hintergrundgeräuschen. "
                + "Braucht viel Arbeitsspeicher und Zeit, nur für starke Handys.\n\n"
                + "FUNKTIONEN\n"
                + "Teilen aus WhatsApp: Sprachnachricht gedrückt halten, Teilen, diese App wählen.\n\n"
                + "WhatsApp-Ordner verbinden: Du bestätigst den Ordner einmal, danach stehen die "
                + "Sprachnachrichten hier als Liste. Antippen transkribiert.\n\n"
                + "Anhören: Das Dreieck spielt die Nachricht ab, das Quadrat stoppt sie.\n\n"
                + "Namen: Mit dem Stift gibst du einer Nachricht einen Namen, zum Beispiel den aus dem Chat. "
                + "WhatsApp verrät der App nicht, von wem eine Nachricht ist, deshalb geht das nur von Hand. "
                + "Bekannte Namen kannst du später wieder auswählen.\n\n"
                + "Sprache: Standard ist Deutsch. Du kannst auf andere Sprachen oder automatische Erkennung umstellen.\n\n"
                + "Datei wählen: transkribiert eine beliebige Audiodatei.\n\n"
                + "Kopieren und Teilen: geben dir den Text weiter.\n\n"
                + "DATENSCHUTZ\n"
                + "Internet braucht die App nur, um Modelle zu laden. Sprachnachrichten und Texte verlassen "
                + "das Handy nie.";
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("Info")
                .setView(sv)
                .setPositiveButton("Schließen", null)
                .show();
    }

    // ---------------------------------------------------------------- Sprache (Standard: Deutsch)

    /** Code für Whisper und Anzeigename. "auto" lässt Whisper die Sprache selbst erkennen. */
    private static final String[][] LANGUAGES = {
            {"de", "Deutsch"}, {"en", "Englisch"}, {"fr", "Französisch"}, {"es", "Spanisch"},
            {"it", "Italienisch"}, {"tr", "Türkisch"}, {"pl", "Polnisch"}, {"ru", "Russisch"},
            {"auto", "Automatisch erkennen"},
    };

    private String[] selectedLanguage() {
        String code = getSharedPreferences(PREFS, MODE_PRIVATE).getString("sprache", "de");
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
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("sprache", LANGUAGES[which][0]).apply();
                    refreshButtons();
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
                        } else {
                            ModelStore.delete(this, m);
                            if (ctxModelId.equals(m.id)) dropContext();
                        }
                        refreshButtons();
                    })
                    .setNegativeButton("Zurück", (d, w) -> showModelList())
                    .show();
        } else {
            confirmDownload(m, () -> {
                ModelStore.select(this, m);
                refreshButtons();
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
                    refreshButtons();
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    busy = false;
                    status.setText("Laden hat nicht geklappt: " + e.getMessage());
                    refreshButtons();
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
        if (uri != null) startTranscription(uri, "Geteilte Sprachnachricht");
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == PICK_AUDIO) {
            startTranscription(data.getData(), "Gewählte Datei");
        } else if (requestCode == PICK_FOLDER) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                status.setText("Der Zugriff auf den Ordner ließ sich nicht dauerhaft merken: " + e.getMessage());
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FOLDER, tree.toString()).apply();
            refreshNotes();
        }
    }

    private void startTranscription(final Uri uri, String title) {
        if (busy) { toastBusy(); return; }
        titleForNext = title;
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
        final String title = titleForNext;
        titleForNext = null;
        result.setText("");
        resultCard.setVisibility(View.GONE);
        setEnabledLook(copyBtn, false);
        setEnabledLook(shareBtn, false);
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

                setStatus(String.format(Locale.GERMANY, "Transkribiere %.0f Sekunden Audio …", seconds));
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
                    status.setText(String.format(Locale.GERMANY,
                            "Fertig. %.0f s Audio in %.1f s transkribiert.", seconds, took));
                    resultTitle.setText(title != null ? title : "");
                    result.setText(text.isEmpty() ? "(Kein Text erkannt)" : text);
                    resultCard.setVisibility(View.VISIBLE);
                    setEnabledLook(copyBtn, !text.isEmpty());
                    setEnabledLook(shareBtn, !text.isEmpty());
                    refreshButtons();
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    busy = false;
                    status.setText("Das hat nicht geklappt: " + e.getMessage());
                    refreshButtons();
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
