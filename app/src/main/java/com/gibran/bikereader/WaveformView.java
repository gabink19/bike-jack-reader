package com.gibran.bikereader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Menampilkan gelombang input + garis ambang + indikator kedip saat pulsa terdeteksi. */
public final class WaveformView extends View {

    private final Paint wavePaint = new Paint();
    private final Paint thrPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint midPaint = new Paint();
    private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] mins = new float[WaveBuffer.COLUMNS];
    private final float[] maxs = new float[WaveBuffer.COLUMNS];

    private WaveBuffer buffer;
    private float threshold = 0.25f;
    private PulseDetector.Polarity polarity = PulseDetector.Polarity.BOTH;
    private volatile long lastPulseAt;

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        wavePaint.setColor(0xFF4FC3F7);
        wavePaint.setStrokeWidth(Math.max(1f, d));
        thrPaint.setColor(0xFFFFB74D);
        thrPaint.setStrokeWidth(1.5f * d);
        thrPaint.setStyle(Paint.Style.STROKE);
        thrPaint.setPathEffect(new DashPathEffect(new float[]{6 * d, 4 * d}, 0));
        midPaint.setColor(0xFF37474F);
        flashPaint.setColor(0xFF66BB6A);
    }

    public void setBuffer(WaveBuffer buffer) {
        this.buffer = buffer;
    }

    public void setThreshold(float threshold, PulseDetector.Polarity polarity) {
        this.threshold = threshold;
        this.polarity = polarity;
        invalidate();
    }

    /** Aman dipanggil dari thread audio. */
    public void flashPulse() {
        lastPulseAt = SystemClock.uptimeMillis();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.rgb(0x10, 0x16, 0x1C));
        float w = getWidth();
        float h = getHeight();
        float mid = h / 2f;
        canvas.drawLine(0, mid, w, mid, midPaint);

        if (buffer != null) {
            int head = buffer.copyTo(mins, maxs);
            float colW = w / WaveBuffer.COLUMNS;
            for (int i = 0; i < WaveBuffer.COLUMNS; i++) {
                int idx = (head + i) % WaveBuffer.COLUMNS;
                float x = i * colW;
                float top = mid - maxs[idx] * mid;
                float bottom = mid - mins[idx] * mid;
                canvas.drawLine(x, top, x, bottom + 1, wavePaint);
            }
        }

        if (polarity != PulseDetector.Polarity.NEGATIVE) {
            float y = mid - threshold * mid;
            canvas.drawLine(0, y, w, y, thrPaint);
        }
        if (polarity != PulseDetector.Polarity.POSITIVE) {
            float y = mid + threshold * mid;
            canvas.drawLine(0, y, w, y, thrPaint);
        }

        if (SystemClock.uptimeMillis() - lastPulseAt < 150) {
            float r = h * 0.08f;
            canvas.drawCircle(w - r * 2, r * 2, r, flashPaint);
        }
    }
}
