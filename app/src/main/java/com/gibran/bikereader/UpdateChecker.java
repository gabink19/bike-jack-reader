package com.gibran.bikereader;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Mengecek GitHub Releases terbaru. Dipanggil dari thread latar (melakukan I/O jaringan).
 *
 * Perbandingan memakai versionCode dari aset version.json (ditulis workflow CI), karena
 * build dev bisa punya versionCode lebih tinggi dari release; tanpa ini aplikasi bisa
 * menawarkan "update" yang sebenarnya downgrade dan akan ditolak Android.
 */
public final class UpdateChecker {

    public static final class UpdateInfo {
        public final String tag;
        public final long versionCode;
        public final String apkUrl;
        public final String notes;

        UpdateInfo(String tag, long versionCode, String apkUrl, String notes) {
            this.tag = tag;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
            this.notes = notes;
        }
    }

    private static final String TAG = "UpdateChecker";
    private static final String APK_ASSET = "BikeJackReader.apk";
    private static final String VERSION_ASSET = "version.json";
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_BYTES = 1 << 20; // Respons API/version.json jauh di bawah 1 MB.

    private UpdateChecker() { }

    /**
     * @return info update jika release terbaru lebih baru dari versi terpasang, null jika tidak.
     * @throws IOException jika jaringan/GitHub gagal atau respons tidak valid.
     */
    public static UpdateInfo check(String repo, long currentVersionCode, String currentVersionName) throws IOException {
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IOException("Nama repo tidak valid: " + repo);
        }
        String body = httpGet("https://api.github.com/repos/" + repo + "/releases/latest");
        try {
            JSONObject release = new JSONObject(body);
            String tag = release.optString("tag_name", "");
            String notes = release.optString("body", "");
            String apkUrl = null;
            String versionUrl = null;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name");
                    String url = a.optString("browser_download_url");
                    if (APK_ASSET.equals(name)) apkUrl = url;
                    if (VERSION_ASSET.equals(name)) versionUrl = url;
                }
            }
            if (apkUrl == null || !isGithubUrl(apkUrl)) {
                return null; // Release tanpa APK: tidak ada yang bisa dipasang.
            }

            if (versionUrl != null && isGithubUrl(versionUrl)) {
                long remoteCode = new JSONObject(httpGet(versionUrl)).getLong("versionCode");
                return remoteCode > currentVersionCode ? new UpdateInfo(tag, remoteCode, apkUrl, notes) : null;
            }
            // Release lama (sebelum version.json ada): bandingkan nama versi dari tag.
            return compareVersions(tag.replaceFirst("^v", ""), baseVersion(currentVersionName)) > 0
                    ? new UpdateInfo(tag, -1, apkUrl, notes) : null;
        } catch (JSONException e) {
            throw new IOException("Respons GitHub tidak valid", e);
        }
    }

    private static boolean isGithubUrl(String url) {
        return url.startsWith("https://github.com/") || url.startsWith("https://objects.githubusercontent.com/");
    }

    /** "0.1-dev.5" -> "0.1" */
    static String baseVersion(String name) {
        if (name == null) return "0";
        int dash = name.indexOf('-');
        return dash >= 0 ? name.substring(0, dash) : name;
    }

    /** Membandingkan "1.2.3" secara numerik per bagian; bagian non-angka dianggap 0. */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? parse(pa[i]) : 0;
            long y = i < pb.length ? parse(pb[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "BikeJackReader-Android");
            int code = c.getResponseCode();
            if (code == 404) {
                throw new IOException("Belum ada release di GitHub");
            }
            if (code != 200) {
                throw new IOException("GitHub membalas HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) {
                        throw new IOException("Respons terlalu besar");
                    }
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Log.w(TAG, "GET failed: " + url, e);
            throw e;
        } finally {
            c.disconnect();
        }
    }
}
