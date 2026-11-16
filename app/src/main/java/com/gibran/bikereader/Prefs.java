package com.gibran.bikereader;

import android.content.Context;
import android.content.SharedPreferences;

/** Pengaturan yang disimpan di SharedPreferences, dengan batas nilai yang valid. */
public final class Prefs {

    public static final double MIN_METERS = 0.1, MAX_METERS = 20.0;
    public static final int MIN_PULSES = 1, MAX_PULSES = 16;
    public static final double MIN_WEIGHT = 20, MAX_WEIGHT = 250;
    public static final int MIN_INTERVAL = 30, MAX_INTERVAL = 500;

    public double metersPerRev = 2.0;
    public int pulsesPerRev = 1;
    public double weightKg = 65;
    public float threshold = 0.25f;
    public PulseDetector.Polarity polarity = PulseDetector.Polarity.BOTH;
    public int minIntervalMs = 120;
    public AudioPulseReader.InputChoice inputChoice = AudioPulseReader.InputChoice.AUTO;

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        metersPerRev = clamp(sp.getFloat("metersPerRev", (float) metersPerRev), MIN_METERS, MAX_METERS);
        pulsesPerRev = (int) clamp(sp.getInt("pulsesPerRev", pulsesPerRev), MIN_PULSES, MAX_PULSES);
        weightKg = clamp(sp.getFloat("weightKg", (float) weightKg), MIN_WEIGHT, MAX_WEIGHT);
        threshold = (float) clamp(sp.getFloat("threshold", threshold), 0.01, 0.95);
        minIntervalMs = (int) clamp(sp.getInt("minIntervalMs", minIntervalMs), MIN_INTERVAL, MAX_INTERVAL);
        try {
            polarity = PulseDetector.Polarity.valueOf(sp.getString("polarity", polarity.name()));
        } catch (IllegalArgumentException e) {
            polarity = PulseDetector.Polarity.BOTH;
        }
        try {
            inputChoice = AudioPulseReader.InputChoice.valueOf(sp.getString("inputChoice", inputChoice.name()));
        } catch (IllegalArgumentException e) {
            inputChoice = AudioPulseReader.InputChoice.AUTO;
        }
    }

    public void save() {
        sp.edit()
                .putFloat("metersPerRev", (float) metersPerRev)
                .putInt("pulsesPerRev", pulsesPerRev)
                .putFloat("weightKg", (float) weightKg)
                .putFloat("threshold", threshold)
                .putInt("minIntervalMs", minIntervalMs)
                .putString("polarity", polarity.name())
                .putString("inputChoice", inputChoice.name())
                .apply();
    }

    static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
