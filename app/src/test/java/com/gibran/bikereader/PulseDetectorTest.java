package com.gibran.bikereader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PulseDetectorTest {

    private static final int SR = 8000;

    /**
     * Mensimulasikan reed switch pada input mic ter-kopel AC:
     * lonjakan negatif saat menutup, positif saat membuka, meluruh eksponensial.
     */
    private static short[] reedSignal(int revs, int periodMs, int closeMs, float amp, float noise) {
        int total = SR * (revs * periodMs + 500) / 1000;
        float[] x = new float[total];
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < total; i++) {
            x[i] = (rnd.nextFloat() * 2 - 1) * noise;
        }
        for (int r = 0; r < revs; r++) {
            int close = SR * (200 + r * periodMs) / 1000;
            int open = close + SR * closeMs / 1000;
            addSpike(x, close, -amp);
            addSpike(x, open, amp);
        }
        short[] out = new short[total];
        for (int i = 0; i < total; i++) {
            out[i] = (short) Math.max(-32768, Math.min(32767, x[i] * 32767));
        }
        return out;
    }

    private static void addSpike(float[] x, int at, float amp) {
        double tau = SR * 0.003; // 3 ms
        for (int i = at; i < x.length && i < at + SR / 20; i++) {
            x[i] += (float) (amp * Math.exp(-(i - at) / tau));
        }
    }

    private static List<Long> run(PulseDetector d, short[] sig) {
        List<Long> hits = new ArrayList<>();
        // Diproses per potongan seperti AudioRecord.read().
        short[] chunk = new short[256];
        for (int off = 0; off < sig.length; off += chunk.length) {
            int n = Math.min(chunk.length, sig.length - off);
            System.arraycopy(sig, off, chunk, 0, n);
            d.process(chunk, n, hits::add);
        }
        return hits;
    }

    @Test
    public void bothPolarityCountsOncePerRevolution() {
        PulseDetector d = new PulseDetector(SR);
        List<Long> hits = run(d, reedSignal(10, 1000, 100, 0.8f, 0.02f));
        assertEquals(10, hits.size());
    }

    @Test
    public void positiveOnlyCountsOpenEdges() {
        PulseDetector d = new PulseDetector(SR);
        d.setPolarity(PulseDetector.Polarity.POSITIVE);
        // Tutup lama (300 ms) tetap 1 pulsa per putaran karena hanya tepi positif dihitung.
        List<Long> hits = run(d, reedSignal(8, 1500, 300, 0.8f, 0.02f));
        assertEquals(8, hits.size());
    }

    @Test
    public void fastCadenceIsTracked() {
        PulseDetector d = new PulseDetector(SR);
        d.setPolarity(PulseDetector.Polarity.NEGATIVE);
        // 120 RPM = 500 ms per putaran.
        List<Long> hits = run(d, reedSignal(20, 500, 40, 0.6f, 0.02f));
        assertEquals(20, hits.size());
        long period = hits.get(hits.size() - 1) - hits.get(hits.size() - 2);
        assertEquals(SR / 2, period, SR / 100);
    }

    @Test
    public void noiseBelowThresholdIsIgnored() {
        PulseDetector d = new PulseDetector(SR);
        d.setThreshold(0.25f);
        List<Long> hits = run(d, reedSignal(0, 1000, 100, 0f, 0.1f));
        assertEquals(0, hits.size());
    }

    @Test
    public void rideStatsComputesSpeedAndDistance() {
        RideStats s = new RideStats();
        s.configure(2.0, 1, 70);
        s.start();
        // 60 RPM selama 10 detik.
        for (int t = 0; t <= 10000; t += 50) {
            if (t % 1000 == 0) {
                s.onPulse(t);
            }
            s.onTick(t);
        }
        RideStats.Snapshot snap = s.snapshot();
        assertEquals(60.0, snap.rpm, 0.5);
        assertEquals(7.2, snap.speedKmh, 0.1); // 60 rpm * 2 m * 60 / 1000
        assertEquals(22.0, snap.distanceM, 0.001); // 11 pulsa (t=0..10000)
        assertEquals(10000, snap.elapsedMs);
    }

    @Test
    public void rpmDropsToZeroWhenPedalingStops() {
        RideStats s = new RideStats();
        s.configure(2.0, 1, 70);
        s.start();
        for (int t = 0; t <= 3000; t += 1000) {
            s.onPulse(t);
            s.onTick(t);
        }
        s.onTick(9000);
        assertEquals(0.0, s.snapshot().rpm, 0.0001);
    }
}
