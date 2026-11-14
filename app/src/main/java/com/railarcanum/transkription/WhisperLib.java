package com.railarcanum.transkription;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;

/** Dünne Brücke zu whisper.cpp (siehe cpp/jni.c). */
final class WhisperLib {
    private static final String TAG = "WhisperLib";

    static {
        String lib = "whisper";
        if (Build.SUPPORTED_ABIS[0].equals("arm64-v8a") && cpuHasFp16()) {
            lib = "whisper_v8fp16_va"; // schnellere Variante für Prozessoren mit fp16
        }
        Log.d(TAG, "Lade lib" + lib + ".so");
        System.loadLibrary(lib);
    }

    private static boolean cpuHasFp16() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Features") && line.contains("fphp")) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "cpuinfo nicht lesbar", e);
        }
        return false;
    }

    /** Wird vom nativen Code (jni.c) während der Transkription mit 0..100 aufgerufen. */
    interface ProgressListener { void onProgress(int percent); }

    static volatile ProgressListener listener;

    @SuppressWarnings("unused") // wird per JNI aufgerufen
    static void onProgress(int percent) {
        ProgressListener l = listener;
        if (l != null) l.onProgress(percent);
    }

    static native long initContext(String modelPath);
    static native void freeContext(long contextPtr);
    /** Gibt 0 zurück, wenn es geklappt hat. */
    static native int fullTranscribe(long contextPtr, int numThreads, float[] audioData, String language);
    static native int getTextSegmentCount(long contextPtr);
    static native String getTextSegment(long contextPtr, int index);
    static native String getSystemInfo();

    private WhisperLib() {}
}
