package com.gibran.bikereader;

/**
 * Mendeteksi pulsa sensor (reed switch) dari aliran sampel PCM 16-bit.
 *
 * Input mic HP selalu ter-kopel AC (ada kapasitor), jadi saat saklar menutup/membuka
 * yang terlihat bukan level DC melainkan lonjakan singkat: satu arah saat menutup,
 * arah sebaliknya saat membuka. Detektor ini menghitung lonjakan yang melewati ambang,
 * dengan histeresis (harus turun di bawah setengah ambang dulu) dan periode
 * refraktori adaptif agar tepi "buka" tidak ikut terhitung pada mode BOTH.
 *
 * Bukan thread-safe untuk process()/reset(); setter ambang/polaritas boleh dipanggil
 * dari thread UI karena field-nya volatile.
 */
public final class PulseDetector {

    public enum Polarity { BOTH, POSITIVE, NEGATIVE }

    public interface Callback {
        void onPulse(long sampleIndex);
    }

    /** Di atas jeda ini pulsa sebelumnya dianggap basi (pengguna berhenti mengayuh). */
    private static final int STALE_MS = 5000;
    /** Batas atas refraktori adaptif agar akselerasi mendadak tidak terlewat. */
    private static final int MAX_REFRACTORY_MS = 600;

    private int sampleRate;
    private volatile float threshold = 0.25f;
    private volatile Polarity polarity = Polarity.BOTH;
    private volatile int minIntervalMs = 120;

    private long sampleIndex;
    private long lastPulse = -1;
    private long lastPeriod;
    private boolean armed = true;

    public PulseDetector(int sampleRate) {
        reset(sampleRate);
    }

    public void reset(int newSampleRate) {
        if (newSampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0");
        }
        sampleRate = newSampleRate;
        sampleIndex = 0;
        lastPulse = -1;
        lastPeriod = 0;
        armed = true;
    }

    public void setThreshold(float value) {
        threshold = Math.max(0.005f, Math.min(0.99f, value));
    }

    public float getThreshold() {
        return threshold;
    }

    public void setPolarity(Polarity value) {
        polarity = value == null ? Polarity.BOTH : value;
    }

    public Polarity getPolarity() {
        return polarity;
    }

    public void setMinIntervalMs(int value) {
        minIntervalMs = Math.max(10, Math.min(MAX_REFRACTORY_MS, value));
    }

    public long getSampleIndex() {
        return sampleIndex;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void process(short[] buf, int count, Callback callback) {
        final float thr = threshold;
        final float rearm = thr * 0.5f;
        final Polarity pol = polarity;
        final long minGap = (long) minIntervalMs * sampleRate / 1000;
        final long maxGap = (long) MAX_REFRACTORY_MS * sampleRate / 1000;
        final long staleGap = (long) STALE_MS * sampleRate / 1000;
        final int n = Math.min(count, buf.length);

        for (int i = 0; i < n; i++) {
            float x = buf[i] / 32768f;
            float v;
            switch (pol) {
                case POSITIVE: v = x; break;
                case NEGATIVE: v = -x; break;
                default: v = Math.abs(x); break;
            }

            if (armed) {
                if (v >= thr) {
                    armed = false;
                    long since = lastPulse < 0 ? Long.MAX_VALUE : sampleIndex - lastPulse;
                    long refractory = minGap;
                    if (lastPeriod > 0 && lastPeriod < staleGap) {
                        refractory = Math.max(minGap, Math.min(lastPeriod * 2 / 5, maxGap));
                    }
                    if (since >= refractory) {
                        lastPeriod = (lastPulse >= 0 && since < staleGap) ? since : 0;
                        lastPulse = sampleIndex;
                        if (callback != null) {
                            callback.onPulse(sampleIndex);
                        }
                    }
                }
            } else if (v < rearm) {
                armed = true;
            }
            sampleIndex++;
        }
    }
}
