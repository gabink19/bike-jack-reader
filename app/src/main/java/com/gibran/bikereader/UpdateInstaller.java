package com.gibran.bikereader;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;

/**
 * Mengunduh APK update lewat DownloadManager lalu membuka layar pemasangan Android.
 * Android sendiri memverifikasi tanda tangan: APK hanya bisa menimpa versi terpasang
 * jika ditandatangani kunci yang sama (bike.jks di GitHub Secrets).
 */
public final class UpdateInstaller {

    public interface Listener {
        void onStatus(String message);
    }

    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final Activity activity;
    private final DownloadManager dm;
    private final Listener listener;
    private long downloadId = -1;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == downloadId) {
                onDownloadComplete(id);
            }
        }
    };

    public UpdateInstaller(Activity activity, Listener listener) {
        this.activity = activity;
        this.dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        this.listener = listener;
    }

    /** Dipanggil di onCreate. */
    public void register() {
        if (registered) return;
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        // Broadcast ini dikirim proses sistem (DownloadManager), jadi receiver harus exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(receiver, f);
        }
        registered = true;
    }

    /** Dipanggil di onDestroy. */
    public void unregister() {
        if (!registered) return;
        activity.unregisterReceiver(receiver);
        registered = false;
    }

    public boolean isDownloading() {
        return downloadId >= 0;
    }

    /** @return false jika pengguna perlu mengizinkan "instal aplikasi tak dikenal" dulu. */
    public boolean start(UpdateChecker.UpdateInfo info) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            listener.onStatus("Izinkan \"Instal aplikasi tidak dikenal\" untuk Bike Jack Reader, lalu ketuk Update lagi.");
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(i);
            } catch (ActivityNotFoundException e) {
                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            }
            return false;
        }
        if (dm == null) {
            listener.onStatus("DownloadManager tidak tersedia di perangkat ini.");
            return false;
        }

        String fileName = "BikeJackReader-" + info.tag.replaceAll("[^A-Za-z0-9._-]", "_") + ".apk";
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir != null) {
            // Hapus unduhan lama dengan nama sama agar DownloadManager tidak menambah akhiran "-1".
            File old = new File(dir, fileName);
            if (old.exists() && !old.delete()) {
                listener.onStatus("Gagal menghapus unduhan lama; mencoba tetap mengunduh.");
            }
        }

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("Bike Jack Reader " + info.tag)
                .setDescription("Mengunduh update")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);
        downloadId = dm.enqueue(req);
        listener.onStatus("Mengunduh " + info.tag + "...");
        return true;
    }

    private void onDownloadComplete(long id) {
        downloadId = -1;
        int status = -1;
        int reason = 0;
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            }
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            listener.onStatus("Unduhan gagal (kode " + reason + "). Coba lagi.");
            return;
        }
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) {
            listener.onStatus("File unduhan tidak ditemukan.");
            return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(install);
            listener.onStatus("Ikuti layar pemasangan untuk menyelesaikan update.");
        } catch (ActivityNotFoundException e) {
            listener.onStatus("Tidak ada aplikasi pemasang APK di perangkat ini.");
        }
    }
}
