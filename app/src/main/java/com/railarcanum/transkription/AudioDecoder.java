package com.railarcanum.transkription;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Wandelt eine Audiodatei (z. B. WhatsApp-Opus) in 16-kHz-Mono-Float um, wie Whisper es braucht. */
final class AudioDecoder {
    static final int TARGET_RATE = 16000;

    private float[] pcm = new float[1 << 20];
    private int count = 0;

    static float[] decode(Context ctx, Uri uri) throws IOException {
        return new AudioDecoder().run(ctx, uri);
    }

    private float[] run(Context ctx, Uri uri) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IOException("Keine Tonspur in der Datei gefunden.");
            ex.selectTrack(track);

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = ex.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(out);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        appendMono(buf.order(ByteOrder.nativeOrder()).asShortBuffer(), channels);
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
            if (count == 0) throw new IOException("Die Datei enthält keinen hörbaren Ton.");
            return resample(rate);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            ex.release();
        }
    }

    /** Mischt alle Kanäle zu Mono und hängt sie an. */
    private void appendMono(ShortBuffer s, int channels) {
        int frames = s.remaining() / channels;
        if (count + frames > pcm.length) {
            float[] bigger = new float[Math.max(pcm.length * 2, count + frames)];
            System.arraycopy(pcm, 0, bigger, 0, count);
            pcm = bigger;
        }
        for (int i = 0; i < frames; i++) {
            float sum = 0;
            for (int c = 0; c < channels; c++) sum += s.get();
            pcm[count++] = sum / channels / 32768f;
        }
    }

    /** Mittelt beim Verkleinern über das Fenster, damit keine Störtöne entstehen. */
    private float[] resample(int srcRate) {
        if (srcRate == TARGET_RATE) {
            float[] out = new float[count];
            System.arraycopy(pcm, 0, out, 0, count);
            return out;
        }
        double ratio = (double) srcRate / TARGET_RATE;
        int outLen = (int) (count / ratio);
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            int from = (int) (i * ratio);
            int to = Math.min(count, Math.max(from + 1, (int) ((i + 1) * ratio)));
            float sum = 0;
            for (int j = from; j < to; j++) sum += pcm[j];
            out[i] = sum / (to - from);
        }
        return out;
    }
}
