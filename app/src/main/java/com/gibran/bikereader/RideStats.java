package com.gibran.bikereader;

import java.util.ArrayDeque;

/**
 * Mengakumulasi statistik latihan dari pulsa sensor.
 * Semua waktu dalam milidetik berbasis jam sampel audio (bukan jam dinding),
 * sehingga perhitungan RPM tidak terpengaruh jitter penjadwalan thread.
 * Semua method synchronized: dipanggil dari thread audio dan thread UI.
 */
public final class RideStats {

    public static final class Snapshot {
        public boolean running;
        public double rpm;
        public double speedKmh;
        public double distanceM;
        public long elapsedMs;
        public long movingMs;
        public double avgSpeedKmh;
        public double maxSpeedKmh;
        public double kcal;
        public long revolutions;
    }

    private static final int WINDOW = 4;

    private double metersPerRev = 2.0;
    private int pulsesPerRev = 1;
    private double weightKg = 65.0;

    private boolean running;
    private long pulses;
    private double distanceM;
    private long elapsedMs;
    private long movingMs;
    private double maxSpeedKmh;
    private double kcal;
    private long lastNow = -1;
    private final ArrayDeque<Long> times = new ArrayDeque<>();

    public synchronized void configure(double metersPerRev, int pulsesPerRev, double weightKg) {
        this.metersPerRev = metersPerRev;
        this.pulsesPerRev = Math.max(1, pulsesPerRev);
        this.weightKg = weightKg;
    }

    public synchronized void start() {
        running = true;
        lastNow = -1;
        times.clear();
    }

    public synchronized void pause() {
        running = false;
        times.clear();
    }

    public synchronized void reset() {
        pulses = 0;
        distanceM = 0;
        elapsedMs = 0;
        movingMs = 0;
        maxSpeedKmh = 0;
        kcal = 0;
        lastNow = -1;
        times.clear();
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /** Dipanggil saat pembaca audio dimulai ulang: jam sampel kembali ke nol. */
    public synchronized void clockReset() {
        lastNow = -1;
        times.clear();
    }

    public synchronized void onPulse(long tMs) {
        if (!running) {
            return;
        }
        pulses++;
        distanceM += metersPerRev / pulsesPerRev;
        times.addLast(tMs);
        while (times.size() > WINDOW) {
            times.removeFirst();
        }
    }

    public synchronized void onTick(long nowMs) {
        if (!running) {
            return;
        }
        if (lastNow >= 0) {
            long dt = Math.max(0, nowMs - lastNow);
            elapsedMs += dt;
            double speed = speedAt(nowMs);
            if (speed > 0) {
                movingMs += dt;
                // Rumus MET standar: kkal/menit = MET * 3.5 * berat(kg) / 200.
                kcal += met(speed) * 3.5 * weightKg / 200.0 * dt / 60000.0;
            }
            if (speed > maxSpeedKmh) {
                maxSpeedKmh = speed;
            }
        }
        lastNow = nowMs;
    }

    public synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        long now = lastNow < 0 ? 0 : lastNow;
        s.running = running;
        s.rpm = running ? rpmAt(now) : 0;
        s.speedKmh = rpmToKmh(s.rpm);
        s.distanceM = distanceM;
        s.elapsedMs = elapsedMs;
        s.movingMs = movingMs;
        s.avgSpeedKmh = movingMs > 0 ? (distanceM / 1000.0) / (movingMs / 3600000.0) : 0;
        s.maxSpeedKmh = maxSpeedKmh;
        s.kcal = kcal;
        s.revolutions = pulses / pulsesPerRev;
        return s;
    }

    private double speedAt(long now) {
        return rpmToKmh(rpmAt(now));
    }

    private double rpmToKmh(double rpm) {
        return rpm * metersPerRev * 60.0 / 1000.0;
    }

    private double rpmAt(long now) {
        if (times.size() < 2) {
            return 0;
        }
        long first = times.peekFirst();
        long last = times.peekLast();
        long span = last - first;
        if (span <= 0) {
            return 0;
        }
        double periodPerPulse = (double) span / (times.size() - 1);
        double sinceLast = Math.max(0, now - last);
        if (sinceLast > Math.max(4000.0, periodPerPulse * 2.5)) {
            return 0;
        }
        // Jika pulsa berikutnya terlambat, RPM ditampilkan menurun secara bertahap.
        double effective = Math.max(periodPerPulse, sinceLast);
        return 60000.0 / effective / pulsesPerRev;
    }

    static double met(double speedKmh) {
        if (speedKmh < 16) return 4.0;
        if (speedKmh < 19) return 6.8;
        if (speedKmh < 22) return 8.0;
        if (speedKmh < 25) return 10.0;
        return 12.0;
    }
}
