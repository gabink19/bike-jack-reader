package com.gibran.bikereader;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * Membaca input mic (jack 3.5mm / adaptor USB-C) di thread terpisah,
 * meneruskan sampel ke PulseDetector dan WaveBuffer.
 * Pemanggil wajib sudah memegang izin RECORD_AUDIO sebelum start().
 */
public final class AudioPulseReader {

    public interface Listener {
        void onPulse(long timeMs);
        void onTick(long timeMs);
        void onError(String message);
    }

    /** Sumber input yang dipilih pengguna. */
    public enum InputChoice { AUTO, JACK, USB, BUILTIN }

    private static final String TAG = "AudioPulseReader";
    private static final int SAMPLE_RATE = 44100;
    /** Cek perangkat yang benar-benar dipakai kira-kira tiap 1 detik (43 buffer x 1024 sampel). */
    private static final int ROUTE_CHECK_EVERY = 43;

    private volatile InputChoice inputChoice = InputChoice.AUTO;

    private final AudioManager audioManager;
    private final PulseDetector detector;
    private final WaveBuffer wave;
    private final Listener listener;

    private volatile boolean running;
    private volatile String inputName = "-";
    private Thread thread;

    public AudioPulseReader(Context context, PulseDetector detector, WaveBuffer wave, Listener listener) {
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.detector = detector;
        this.wave = wave;
        this.listener = listener;
    }

    public String getInputName() {
        return inputName;
    }

    public boolean isRunning() {
        return running;
    }

    /** Berlaku saat start() berikutnya; pemanggil me-restart reader setelah mengubahnya. */
    public void setInputChoice(InputChoice choice) {
        inputChoice = choice == null ? InputChoice.AUTO : choice;
    }

    /** Mencari perangkat input sesuai pilihan. Null = biarkan Android memilih (default). */
    private AudioDeviceInfo findDevice(InputChoice choice) {
        if (audioManager == null) {
            return null;
        }
        AudioDeviceInfo[] inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        switch (choice) {
            case JACK:
                return firstOfType(inputs, AudioDeviceInfo.TYPE_WIRED_HEADSET);
            case USB:
                return firstOfType(inputs, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE);
            case BUILTIN:
                return firstOfType(inputs, AudioDeviceInfo.TYPE_BUILTIN_MIC);
            default:
                return findExternalMic();
        }
    }

    private static AudioDeviceInfo firstOfType(AudioDeviceInfo[] devices, int... types) {
        for (int type : types) {
            for (AudioDeviceInfo d : devices) {
                if (d.getType() == type) {
                    return d;
                }
            }
        }
        return null;
    }

    static String label(AudioDeviceInfo d) {
        if (d == null) {
            return "tidak diketahui";
        }
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Jack 3.5mm";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB-C (" + d.getProductName() + ")";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Mic HP";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth";
            default:
                return "Perangkat tipe " + d.getType();
        }
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "audio-pulse-reader");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                // read() memblok paling lama satu buffer (~23 ms), jadi join cepat selesai.
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Mencari mic eksternal (headset kabel / USB). Null jika hanya ada mic internal. */
    public AudioDeviceInfo findExternalMic() {
        if (audioManager == null) {
            return null;
        }
        AudioDeviceInfo usb = null;
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                return d;
            }
            if (usb == null && (type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE)) {
                usb = d;
            }
        }
        return usb;
    }

    @SuppressLint("MissingPermission") // Izin dicek di MainActivity sebelum start().
    private void loop() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            fail("Perangkat tidak mendukung perekaman 44.1 kHz mono");
            return;
        }
        AudioRecord record = createRecord(Math.max(minBuf * 2, 8192));
        if (record == null) {
            fail("Gagal membuka input mikrofon");
            return;
        }
        try {
            InputChoice choice = inputChoice;
            AudioDeviceInfo wanted = findDevice(choice);
            String missing = null;
            if (wanted != null) {
                record.setPreferredDevice(wanted);
            } else if (choice != InputChoice.AUTO) {
                missing = choice == InputChoice.JACK ? "Jack 3.5mm"
                        : choice == InputChoice.USB ? "USB-C" : "Mic HP";
            }

            detector.reset(SAMPLE_RATE);
            wave.setSampleRate(SAMPLE_RATE);
            record.startRecording();

            short[] buf = new short[1024];
            int buffers = 0;
            while (running) {
                if (buffers++ % ROUTE_CHECK_EVERY == 0) {
                    // setPreferredDevice hanya permintaan; tampilkan perangkat yang benar-benar dipakai Android.
                    AudioDeviceInfo routed = record.getRoutedDevice();
                    String name = routed != null ? label(routed) : (wanted != null ? label(wanted) : "default sistem");
                    if (missing != null) {
                        name += " (" + missing + " tidak terdeteksi)";
                    } else if (wanted != null && routed != null && routed.getType() != wanted.getType()) {
                        name += " (permintaan " + label(wanted) + " diabaikan sistem)";
                    }
                    inputName = name;
                }
                int n = record.read(buf, 0, buf.length);
                if (n < 0) {
                    fail("Error membaca audio: " + n);
                    break;
                }
                if (n == 0) {
                    continue;
                }
                detector.process(buf, n, idx -> listener.onPulse(idx * 1000L / SAMPLE_RATE));
                wave.push(buf, n);
                listener.onTick(detector.getSampleIndex() * 1000L / SAMPLE_RATE);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "audio loop failed", e);
            fail("Audio error: " + e.getMessage());
        } finally {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // Belum sempat startRecording(); tidak ada yang perlu dihentikan.
            }
            record.release();
        }
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createRecord(int bufferBytes) {
        boolean unprocessed = audioManager != null && "true".equals(
                audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        // UNPROCESSED/VOICE_RECOGNITION meminimalkan AGC & noise suppression yang bisa meredam lonjakan pulsa.
        int[] sources = unprocessed
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            AudioRecord r;
            try {
                r = new AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            } catch (IllegalArgumentException | SecurityException e) {
                Log.w(TAG, "AudioRecord source " + src + " rejected", e);
                continue;
            }
            if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                return r;
            }
            r.release();
        }
        return null;
    }

    private void fail(String message) {
        running = false;
        listener.onError(message);
    }
}
