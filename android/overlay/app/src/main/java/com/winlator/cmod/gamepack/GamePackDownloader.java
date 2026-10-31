package com.winlator.cmod.gamepack;

import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads archive parts with resume support. Yandex.Disk public links
 * are resolved to a direct link through the public Yandex.Disk REST API.
 */
public class GamePackDownloader {
    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_ATTEMPTS = 5;
    private static final String YANDEX_API = "https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key=";

    private final AtomicBoolean cancelled;

    public GamePackDownloader(AtomicBoolean cancelled) {
        this.cancelled = cancelled;
    }

    public static boolean isYandexDiskLink(String url) {
        String host = Uri.parse(url).getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ENGLISH);
        return host.equals("yadi.sk") || host.startsWith("disk.yandex.") || host.contains(".disk.yandex.");
    }

    public void download(String url, File outFile, ProgressListener listener) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            checkCancelled();
            try {
                // Yandex direct links expire, so resolve them again on every attempt.
                String directUrl = isYandexDiskLink(url) ? resolveYandexDiskLink(url) : url;
                downloadOnce(directUrl, outFile, listener);
                return;
            }
            catch (IOException e) {
                if (cancelled.get()) throw e;
                lastError = e;
                try {
                    Thread.sleep(2000L << attempt);
                }
                catch (InterruptedException ie) {
                    throw new IOException("Interrupted", ie);
                }
            }
        }
        throw lastError;
    }

    private String resolveYandexDiskLink(String publicUrl) throws IOException {
        HttpURLConnection connection = open(YANDEX_API + URLEncoder.encode(publicUrl, "UTF-8"));
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Yandex.Disk API HTTP " + code + " for " + publicUrl);
            }
            String body = readAll(connection.getInputStream());
            String href = new JSONObject(body).optString("href", "");
            if (href.isEmpty()) throw new IOException("Yandex.Disk API returned no download link");
            return href;
        }
        catch (org.json.JSONException e) {
            throw new IOException("Invalid Yandex.Disk API response", e);
        }
        finally {
            connection.disconnect();
        }
    }

    private void downloadOnce(String url, File outFile, ProgressListener listener) throws IOException {
        long existing = outFile.isFile() ? outFile.length() : 0;
        HttpURLConnection connection = open(url);
        if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");

        try {
            int code = connection.getResponseCode();
            boolean append;
            long total;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                append = true;
                total = existing + connection.getContentLengthLong();
            }
            else if (code == HttpURLConnection.HTTP_OK) {
                append = false;
                existing = 0;
                total = connection.getContentLengthLong();
            }
            else if (code == 416 && existing > 0) {
                // Requested range not satisfiable: the file is already complete.
                if (listener != null) listener.onProgress(existing, existing);
                return;
            }
            else {
                throw new IOException("HTTP " + code + " for " + url);
            }

            long downloaded = existing;
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(outFile, append)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                long lastReport = 0;
                while ((read = in.read(buffer)) != -1) {
                    checkCancelled();
                    out.write(buffer, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (listener != null && now - lastReport > 200) {
                        listener.onProgress(downloaded, total);
                        lastReport = now;
                    }
                }
            }
            if (total > 0 && downloaded < total) throw new IOException("Connection closed early");
            if (listener != null) listener.onProgress(downloaded, total);
        }
        finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "GamePack-Launcher/1.0 (Android)");
        return connection;
    }

    private void checkCancelled() throws IOException {
        if (cancelled.get()) throw new IOException("Cancelled");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toString("UTF-8");
    }

    public static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format(Locale.ENGLISH, "%02x", b));
            return sb.toString();
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
