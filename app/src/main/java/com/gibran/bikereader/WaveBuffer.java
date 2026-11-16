package com.gibran.bikereader;

/** Ring buffer min/max per kolom untuk menggambar gelombang ~2 detik terakhir. */
public final class WaveBuffer {

    public static final int COLUMNS = 300;

    private final float[] mins = new float[COLUMNS];
    private final float[] maxs = new float[COLUMNS];
    private int samplesPerColumn = 294;
    private int head;
    private float accMin;
    private float accMax;
    private int accCount;
    private float peak;

    public synchronized void setSampleRate(int sampleRate) {
        samplesPerColumn = Math.max(1, sampleRate * 2 / COLUMNS);
        accCount = 0;
        accMin = 0;
        accMax = 0;
    }

    public synchronized void push(short[] buf, int count) {
        int n = Math.min(count, buf.length);
        float bufPeak = 0;
        for (int i = 0; i < n; i++) {
            float x = buf[i] / 32768f;
            if (x < accMin) accMin = x;
            if (x > accMax) accMax = x;
            float a = Math.abs(x);
            if (a > bufPeak) bufPeak = a;
            if (++accCount >= samplesPerColumn) {
                mins[head] = accMin;
                maxs[head] = accMax;
                head = (head + 1) % COLUMNS;
                accMin = 0;
                accMax = 0;
                accCount = 0;
            }
        }
        peak = Math.max(peak * 0.9f, bufPeak);
    }

    /** Menyalin data; kembalian = indeks kolom tertua. */
    public synchronized int copyTo(float[] outMin, float[] outMax) {
        System.arraycopy(mins, 0, outMin, 0, COLUMNS);
        System.arraycopy(maxs, 0, outMax, 0, COLUMNS);
        return head;
    }

    public synchronized float getPeak() {
        return peak;
    }
}
