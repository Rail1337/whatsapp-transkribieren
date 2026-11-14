package com.railarcanum.transkription;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Findet den WhatsApp-Sprachnachrichten-Ordner und listet die Nachrichten.
 * Läuft über den Android-Ordnerzugriff (SAF): Man bestätigt den Ordner einmal, ganz ohne
 * Rechte für den ganzen Speicher.
 */
final class VoiceNotes {
    static final class Note {
        final String name;
        final long modified;
        final long size;
        final Uri uri;

        Note(String name, long modified, long size, Uri uri) {
            this.name = name; this.modified = modified; this.size = size; this.uri = uri;
        }
    }

    private static final String AUTHORITY = "com.android.externalstorage.documents";
    private static final int MAX_NOTES = 100;
    private static final int MAX_SCANNED = 5000;

    private VoiceNotes() {}

    /** Wo WhatsApp (oder WhatsApp Business) die Sprachnachrichten auf diesem Handy ablegt. */
    static Uri suggestedFolder(Context ctx) {
        boolean business = !isInstalled(ctx, "com.whatsapp") && isInstalled(ctx, "com.whatsapp.w4b");
        String docId;
        if (Build.VERSION.SDK_INT >= 30) {
            docId = business
                    ? "primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"
                    : "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes";
        } else {
            docId = business
                    ? "primary:WhatsApp Business/Media/WhatsApp Business Voice Notes"
                    : "primary:WhatsApp/Media/WhatsApp Voice Notes";
        }
        return DocumentsContract.buildDocumentUri(AUTHORITY, docId);
    }

    private static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Neueste Sprachnachrichten zuerst. Wirft SecurityException, wenn der Zugriff nicht mehr gilt. */
    static List<Note> list(Context ctx, Uri tree) {
        List<Note> out = new ArrayList<>();
        scan(ctx.getContentResolver(), tree, DocumentsContract.getTreeDocumentId(tree), 0, out, new int[]{0});
        Collections.sort(out, (a, b) -> Long.compare(b.modified, a.modified));
        return out.size() > MAX_NOTES ? new ArrayList<>(out.subList(0, MAX_NOTES)) : out;
    }

    private static void scan(ContentResolver cr, Uri tree, String parentId, int depth,
                             List<Note> out, int[] scanned) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
        };
        List<String> subfolders = new ArrayList<>();
        try (Cursor c = cr.query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext() && scanned[0]++ < MAX_SCANNED) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    subfolders.add(id);
                } else if (isAudio(name)) {
                    out.add(new Note(name, c.getLong(3), c.getLong(4),
                            DocumentsContract.buildDocumentUriUsingTree(tree, id)));
                }
            }
        }
        // WhatsApp sortiert die Nachrichten in Wochenordner, deshalb ein, zwei Ebenen tiefer schauen.
        if (depth < 3) for (String sub : subfolders) scan(cr, tree, sub, depth + 1, out, scanned);
    }

    private static boolean isAudio(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".opus") || n.endsWith(".ogg") || n.endsWith(".oga") || n.endsWith(".m4a")
                || n.endsWith(".aac") || n.endsWith(".mp3") || n.endsWith(".amr") || n.endsWith(".wav");
    }
}
