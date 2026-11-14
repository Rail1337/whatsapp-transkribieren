package com.railarcanum.transkription;

import android.content.res.AssetManager;
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

    static native long initContextFromAsset(AssetManager assetManager, String assetPath);
    static native void freeContext(long contextPtr);
    static native void fullTranscribe(long contextPtr, int numThreads, float[] audioData);
    static native int getTextSegmentCount(long contextPtr);
    static native String getTextSegment(long contextPtr, int index);
    static native String getSystemInfo();

    private WhisperLib() {}
}
