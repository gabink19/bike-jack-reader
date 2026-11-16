package com.gibran.bikereader;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements AudioPulseReader.Listener {

    private static final int REQ_AUDIO = 1;
    private static final long FRAME_MS = 50;
    /** Cek update otomatis paling sering tiap 6 jam (batas API GitHub tanpa login: 60/jam per IP). */
    private static final long AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private UpdateInstaller updateInstaller;
    private UpdateChecker.UpdateInfo pendingUpdate;
    private boolean checkingUpdate;
    private Button btnUpdateBanner;
    private TextView tvVersion;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final PulseDetector detector = new PulseDetector(44100);
    private final WaveBuffer wave = new WaveBuffer();
    private final RideStats stats = new RideStats();

    private Prefs prefs;
    private AudioPulseReader reader;
    private AudioManager audioManager;
    private boolean visible;
    private boolean lastHadExternalMic;
    private volatile String errorMessage;
    private int frame;

    private TextView tvStatus, valSpeed, valRpm, valDistance, valTime, valKcal, valAvg, tvExtra, tvLevel, tvThreshold, btnSettings;
    private Button btnStart;
    private WaveformView waveform;
    private View settingsPanel;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            waveform.invalidate();
            if (frame++ % 4 == 0) {
                render();
            }
            ui.postDelayed(this, FRAME_MS);
        }
    };

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            onDevicesChanged();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            onDevicesChanged();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Layar tetap menyala selama aplikasi terbuka (dipakai sebagai layar latihan).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        prefs = new Prefs(this);
        applyPrefs();
        reader = new AudioPulseReader(this, detector, wave, this);
        reader.setInputChoice(prefs.inputChoice);

        bindViews();
        setupSettings();
        setupUpdates();
    }

    // ---- Update dari GitHub Releases ----

    private void setupUpdates() {
        btnUpdateBanner = findViewById(R.id.btnUpdateBanner);
        tvVersion = findViewById(R.id.tvVersion);
        tvVersion.setText("Versi terpasang: " + BuildConfig.VERSION_NAME);
        // Status unduhan juga ditulis di banner, karena panel pengaturan biasanya tertutup.
        updateInstaller = new UpdateInstaller(this, msg -> {
            tvVersion.setText(msg);
            if (btnUpdateBanner.getVisibility() == View.VISIBLE) {
                btnUpdateBanner.setText(msg);
            }
        });
        updateInstaller.register();

        btnUpdateBanner.setOnClickListener(v -> {
            if (pendingUpdate != null && !updateInstaller.isDownloading()) {
                updateInstaller.start(pendingUpdate);
            }
        });
        findViewById(R.id.btnCheckUpdate).setOnClickListener(v -> checkForUpdate(true));

        long last = getSharedPreferences("update", MODE_PRIVATE).getLong("lastCheck", 0);
        if (System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS) {
            checkForUpdate(false);
        }
    }

    private void checkForUpdate(boolean manual) {
        if (checkingUpdate) return;
        checkingUpdate = true;
        if (manual) tvVersion.setText("Mengecek update...");
        bg.execute(() -> {
            UpdateChecker.UpdateInfo info = null;
            String error = null;
            try {
                info = UpdateChecker.check(BuildConfig.UPDATE_REPO, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final UpdateChecker.UpdateInfo result = info;
            final String err = error;
            ui.post(() -> {
                checkingUpdate = false;
                if (isDestroyed()) return;
                if (err == null) {
                    getSharedPreferences("update", MODE_PRIVATE).edit()
                            .putLong("lastCheck", System.currentTimeMillis()).apply();
                }
                showUpdateResult(result, err, manual);
            });
        });
    }

    private void showUpdateResult(UpdateChecker.UpdateInfo info, String error, boolean manual) {
        pendingUpdate = info;
        if (info != null) {
            btnUpdateBanner.setText("Update tersedia: " + info.tag + " — ketuk untuk pasang");
            btnUpdateBanner.setVisibility(View.VISIBLE);
            tvVersion.setText("Versi terpasang: " + BuildConfig.VERSION_NAME + "  ·  terbaru: " + info.tag);
        } else {
            btnUpdateBanner.setVisibility(View.GONE);
            if (error != null) {
                // Cek otomatis yang gagal (misal offline) tidak perlu mengganggu pengguna.
                if (manual) tvVersion.setText("Gagal cek update: " + error);
            } else if (manual) {
                tvVersion.setText("Versi terpasang: " + BuildConfig.VERSION_NAME + "  ·  sudah terbaru");
            }
        }
    }

    @Override
    protected void onDestroy() {
        updateInstaller.unregister();
        bg.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        tvStatus = findViewById(R.id.tvStatus);
        valSpeed = findViewById(R.id.valSpeed);
        valRpm = findViewById(R.id.valRpm);
        valDistance = findViewById(R.id.valDistance);
        valTime = findViewById(R.id.valTime);
        valKcal = findViewById(R.id.valKcal);
        valAvg = findViewById(R.id.valAvg);
        tvExtra = findViewById(R.id.tvExtra);
        tvLevel = findViewById(R.id.tvLevel);
        tvThreshold = findViewById(R.id.tvThreshold);
        btnSettings = findViewById(R.id.btnSettings);
        btnStart = findViewById(R.id.btnStart);
        settingsPanel = findViewById(R.id.settingsPanel);
        waveform = findViewById(R.id.waveform);
        waveform.setBuffer(wave);
        waveform.setThreshold(prefs.threshold, prefs.polarity);

        btnStart.setOnClickListener(v -> {
            if (stats.isRunning()) {
                stats.pause();
            } else {
                stats.start();
            }
            render();
        });
        findViewById(R.id.btnReset).setOnClickListener(v -> {
            stats.reset();
            render();
        });
        btnSettings.setOnClickListener(v -> {
            boolean show = settingsPanel.getVisibility() != View.VISIBLE;
            settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            btnSettings.setText(show ? R.string.settings_hide : R.string.settings_show);
        });
    }

    private void setupSettings() {
        SeekBar sb = findViewById(R.id.sbThreshold);
        sb.setProgress(Math.round(prefs.threshold * 100));
        updateThresholdLabel();
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.threshold = Math.max(1, progress) / 100f;
                applyPrefs();
                updateThresholdLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.save();
            }
        });

        RadioGroup rgInput = findViewById(R.id.rgInput);
        switch (prefs.inputChoice) {
            case JACK: rgInput.check(R.id.rbInJack); break;
            case USB: rgInput.check(R.id.rbInUsb); break;
            case BUILTIN: rgInput.check(R.id.rbInMic); break;
            default: rgInput.check(R.id.rbInAuto); break;
        }
        rgInput.setOnCheckedChangeListener((group, id) -> {
            prefs.inputChoice = id == R.id.rbInJack ? AudioPulseReader.InputChoice.JACK
                    : id == R.id.rbInUsb ? AudioPulseReader.InputChoice.USB
                    : id == R.id.rbInMic ? AudioPulseReader.InputChoice.BUILTIN
                    : AudioPulseReader.InputChoice.AUTO;
            prefs.save();
            reader.setInputChoice(prefs.inputChoice);
            restartReaderIfAllowed();
        });

        RadioGroup rg = findViewById(R.id.rgPolarity);
        rg.check(prefs.polarity == PulseDetector.Polarity.POSITIVE ? R.id.rbPos
                : prefs.polarity == PulseDetector.Polarity.NEGATIVE ? R.id.rbNeg : R.id.rbBoth);
        rg.setOnCheckedChangeListener((group, id) -> {
            prefs.polarity = id == R.id.rbPos ? PulseDetector.Polarity.POSITIVE
                    : id == R.id.rbNeg ? PulseDetector.Polarity.NEGATIVE : PulseDetector.Polarity.BOTH;
            applyPrefs();
            prefs.save();
        });

        bindNumber(R.id.etMeters, fmt(prefs.metersPerRev), v -> {
            prefs.metersPerRev = Prefs.clamp(v, Prefs.MIN_METERS, Prefs.MAX_METERS);
        });
        bindNumber(R.id.etPulses, String.valueOf(prefs.pulsesPerRev), v -> {
            prefs.pulsesPerRev = (int) Prefs.clamp(Math.round(v), Prefs.MIN_PULSES, Prefs.MAX_PULSES);
        });
        bindNumber(R.id.etWeight, fmt(prefs.weightKg), v -> {
            prefs.weightKg = Prefs.clamp(v, Prefs.MIN_WEIGHT, Prefs.MAX_WEIGHT);
        });
        bindNumber(R.id.etInterval, String.valueOf(prefs.minIntervalMs), v -> {
            prefs.minIntervalMs = (int) Prefs.clamp(Math.round(v), Prefs.MIN_INTERVAL, Prefs.MAX_INTERVAL);
        });
    }

    private interface NumberSetter {
        void set(double value);
    }

    private void bindNumber(int id, String initial, NumberSetter setter) {
        EditText et = findViewById(id);
        et.setText(initial);
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                double v;
                try {
                    // Terima koma maupun titik sebagai pemisah desimal.
                    v = Double.parseDouble(s.toString().trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    return; // Input belum lengkap (misal kosong); nilai lama tetap dipakai.
                }
                setter.set(v);
                applyPrefs();
                prefs.save();
            }
        });
    }

    private void applyPrefs() {
        detector.setThreshold(prefs.threshold);
        detector.setPolarity(prefs.polarity);
        detector.setMinIntervalMs(prefs.minIntervalMs);
        stats.configure(prefs.metersPerRev, prefs.pulsesPerRev, prefs.weightKg);
        if (waveform != null) {
            waveform.setThreshold(prefs.threshold, prefs.polarity);
        }
    }

    private void updateThresholdLabel() {
        tvThreshold.setText(getString(R.string.set_threshold) + ": " + Math.round(prefs.threshold * 100) + "%");
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startReader();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, ui);
        ui.post(ticker);
    }

    @Override
    protected void onStop() {
        visible = false;
        ui.removeCallbacks(ticker);
        audioManager.unregisterAudioDeviceCallback(deviceCallback);
        reader.stop();
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (visible) {
                startReader();
            }
        } else {
            errorMessage = getString(R.string.perm_denied);
            render();
        }
    }

    private void startReader() {
        errorMessage = null;
        lastHadExternalMic = reader.findExternalMic() != null;
        stats.clockReset();
        reader.start();
    }

    /** Kabel dicolok/dicabut: buka ulang AudioRecord agar input mengikuti perangkat baru. */
    private void onDevicesChanged() {
        boolean has = reader.findExternalMic() != null;
        if (has == lastHadExternalMic) {
            return;
        }
        restartReaderIfAllowed();
    }

    private void restartReaderIfAllowed() {
        if (!visible
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        reader.stop();
        startReader();
    }

    // ---- Callback dari thread audio ----

    @Override
    public void onPulse(long timeMs) {
        stats.onPulse(timeMs);
        waveform.flashPulse();
    }

    @Override
    public void onTick(long timeMs) {
        stats.onTick(timeMs);
    }

    @Override
    public void onError(String message) {
        errorMessage = message;
    }

    // ---- Tampilan ----

    private void render() {
        RideStats.Snapshot s = stats.snapshot();
        valSpeed.setText(String.format(Locale.getDefault(), "%.1f", s.speedKmh));
        valRpm.setText(String.format(Locale.getDefault(), "%.0f", s.rpm));
        valDistance.setText(String.format(Locale.getDefault(), "%.2f", s.distanceM / 1000.0));
        valTime.setText(formatDuration(s.elapsedMs));
        valKcal.setText(String.format(Locale.getDefault(), "%.0f", s.kcal));
        valAvg.setText(String.format(Locale.getDefault(), "%.1f", s.avgSpeedKmh));
        tvExtra.setText(String.format(Locale.getDefault(),
                "Maks %.1f km/j  ·  %d putaran  ·  bergerak %s",
                s.maxSpeedKmh, s.revolutions, formatDuration(s.movingMs)));
        btnStart.setText(s.running ? R.string.pause : (s.elapsedMs > 0 ? R.string.resume : R.string.start));
        tvLevel.setText(String.format(Locale.getDefault(), "Level puncak: %.0f%%", wave.getPeak() * 100));

        String err = errorMessage;
        if (err != null) {
            tvStatus.setText(err);
        } else if (reader.isRunning()) {
            tvStatus.setText("Input: " + reader.getInputName() + (s.running ? "  ·  merekam" : "  ·  tekan Mulai"));
        } else {
            tvStatus.setText("Input belum aktif");
        }
    }

    private static String formatDuration(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    private static String fmt(double v) {
        // Nilai disimpan sebagai float, jadi bulatkan ke 3 desimal agar 2.1 tidak tampil 2.0999999.
        double r = Math.round(v * 1000) / 1000.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
