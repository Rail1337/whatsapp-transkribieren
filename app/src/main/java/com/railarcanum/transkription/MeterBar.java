package com.railarcanum.transkription;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Schlanker Fortschrittsbalken im Stil des transcript-tools: dunkle Spur, grüne Füllung,
 * läuft nie rückwärts. Ohne echten Wert (null) wandert ein Puls hin und her.
 */
final class MeterBar extends View {
    private static final int TRACK = 0xFF1E3524;
    private static final int FILL = 0xFF8FD67A;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final ValueAnimator pulse = ValueAnimator.ofFloat(0f, 1f);

    private float fraction = 0f;      // 0..1, nur steigend
    private boolean indeterminate = false;
    private float pulsePos = 0f;

    MeterBar(Context ctx) {
        super(ctx);
        pulse.setDuration(1300);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new LinearInterpolator());
        pulse.addUpdateListener(a -> {
            pulsePos = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    /** Echter Wert 0..100. Der Balken geht nie zurück (außer nach reset()). */
    void setPercent(int percent) {
        stopPulse();
        indeterminate = false;
        fraction = Math.max(fraction, Math.min(100, Math.max(0, percent)) / 100f);
        invalidate();
    }

    /** Kein echter Wert vorhanden: Puls statt Prozent. */
    void setBusy() {
        indeterminate = true;
        if (!pulse.isStarted()) pulse.start();
        invalidate();
    }

    void reset() {
        stopPulse();
        indeterminate = false;
        fraction = 0f;
        invalidate();
    }

    private void stopPulse() {
        if (pulse.isStarted()) pulse.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPulse();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight();
        float r = h / 2f;
        paint.setColor(TRACK);
        rect.set(0, 0, getWidth(), h);
        c.drawRoundRect(rect, r, r, paint);

        paint.setColor(FILL);
        if (indeterminate) {
            float w = getWidth() * 0.3f;
            float x = -w + pulsePos * (getWidth() + w);
            c.save();
            c.clipRect(0, 0, getWidth(), h);
            rect.set(x, 0, x + w, h);
            c.drawRoundRect(rect, r, r, paint);
            c.restore();
        } else if (fraction > 0f) {
            rect.set(0, 0, Math.max(h, getWidth() * fraction), h);
            c.drawRoundRect(rect, r, r, paint);
        }
    }
}
