package com.thowilabs.wscanner;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

/**
 * Test de velocidad de internet usando descarga HTTP desde CDNs públicas
 * + ping ICMP a DNS públicos. Sin dependencia de servidores externos específicos.
 */
public class SpeedTestTool {

    private static final String TAG = "WScanner.Speed";

    // ── CDN test files (conocidos, confiables) ──────────────────────
    private static final String DOWNLOAD_URL_1 = "http://speedtest.tele2.net/10MB.zip";
    private static final String DOWNLOAD_URL_2 = "http://ipv4.download.thinkbroadband.com/5MB.zip";
    private static final String SMALL_URL       = "http://speedtest.tele2.net/100KB.zip";

    // ── DNS públicos para ping ──────────────────────────────────────
    private static final String PING_HOST_1 = "8.8.8.8";   // Google DNS
    private static final String PING_HOST_2 = "1.1.1.1";   // Cloudflare DNS

    private static final int PING_COUNT       = 5;
    private static final int PING_TIMEOUT_MS  = 2000;
    private static final int CONNECT_TIMEOUT  = 15_000;
    private static final int READ_TIMEOUT     = 30_000;

    public interface Callback {
        void onPhase(String phase);
        void onProgress(int percent);
        void onPingResult(double pingMs, double jitterMs);
        void onDownloadSpeedUpdate(double currentMbps);
        void onDownloadResult(double mbps);
        void onUploadResult(double mbps);
        void onFinished(double pingMs, double jitterMs, double downMbps, double upMbps);
        void onError(String message);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ═══════════════════════════════════════════════════════════════
    //  runTest
    // ═══════════════════════════════════════════════════════════════

    public void runTest(Callback callback) {
        new Thread(() -> {
            try {
                // ── Fase 1: Ping ──────────────────────────────────
                post(() -> callback.onPhase("Midiendo latencia…"));
                post(() -> callback.onProgress(5));

                double[] pingResult = measurePing();
                double pingMs = pingResult[0];
                double jitterMs = pingResult[1];

                Log.i(TAG, String.format("Ping: %.1f ms  Jitter: %.1f ms", pingMs, jitterMs));
                post(() -> callback.onPingResult(pingMs, jitterMs));

                // ── Fase 2: Download ──────────────────────────────
                post(() -> callback.onPhase("Midiendo descarga…"));
                post(() -> callback.onProgress(20));

                double downMbps = measureDownload(callback);
                Log.i(TAG, String.format("Download: %.1f Mbps", downMbps));
                post(() -> callback.onDownloadResult(downMbps));

                // ── Final ─────────────────────────────────────────
                post(() -> callback.onProgress(100));

                if (downMbps <= 0 && pingMs <= 0) {
                    post(() -> callback.onError(
                            "No se pudo conectar.\nVerifica tu conexión a internet."));
                    return;
                }

                post(() -> callback.onUploadResult(0));
                post(() -> callback.onFinished(pingMs, jitterMs, downMbps, 0));

            } catch (Exception e) {
                Log.e(TAG, "Error en speed test: " + e.getMessage(), e);
                post(() -> callback.onError(e.getMessage() != null
                        ? e.getMessage() : "Error desconocido"));
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Ping  —  InetAddress.isReachable contra DNS públicos
    // ═══════════════════════════════════════════════════════════════

    private double[] measurePing() {
        // Intentar host principal, fallback al secundario
        InetAddress target = resolveAny(PING_HOST_1, PING_HOST_2);
        if (target == null) {
            Log.e(TAG, "No se pudo resolver ningún host de ping");
            return new double[]{0, 0};
        }

        String host = target.getHostAddress();
        Log.d(TAG, "Ping → " + host);

        double[] rtts = new double[PING_COUNT];
        int success = 0;

        for (int i = 0; i < PING_COUNT; i++) {
            try {
                long t0 = System.nanoTime();
                boolean reachable = target.isReachable(PING_TIMEOUT_MS);
                long t1 = System.nanoTime();

                if (reachable) {
                    rtts[success] = (t1 - t0) / 1_000_000.0;
                    success++;
                }
            } catch (Exception e) {
                Log.d(TAG, "Ping #" + (i + 1) + " falló: " + e.getMessage());
            }
        }

        if (success == 0) {
            Log.w(TAG, "Todos los pings fallaron");
            return new double[]{0, 0};
        }

        double sum = 0;
        for (int i = 0; i < success; i++) sum += rtts[i];
        double avg = sum / success;

        double jitterSum = 0;
        for (int i = 1; i < success; i++)
            jitterSum += Math.abs(rtts[i] - rtts[i - 1]);
        double jitter = success > 1 ? jitterSum / (success - 1) : 0;

        return new double[]{round2(avg), round2(jitter)};
    }

    // ═══════════════════════════════════════════════════════════════
    //  Download  —  descarga HTTP desde CDN pública
    // ═══════════════════════════════════════════════════════════════

    private double measureDownload(Callback callback) {
        // Primero intentamos URL principal, luego fallback
        double result = downloadUrl(DOWNLOAD_URL_1, callback);
        if (result > 0) return result;

        Log.w(TAG, "URL principal falló, intentando fallback…");
        return downloadUrl(DOWNLOAD_URL_2, callback);
    }

    private double downloadUrl(String urlStr, Callback callback) {
        HttpURLConnection conn = null;
        try {
            Log.d(TAG, "Download → " + urlStr);
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "WScanner/1.0");
            conn.setInstanceFollowRedirects(true);

            long t0 = System.nanoTime();
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Download HTTP " + code + " → " + urlStr);
                return 0;
            }

            InputStream is = conn.getInputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            long intervalStart = System.nanoTime();
            long intervalBytes = 0;
            while ((n = is.read(buf)) != -1) {
                total += n;
                intervalBytes += n;
                long now = System.nanoTime();
                double intervalSecs = (now - intervalStart) / 1_000_000_000.0;
                if (intervalSecs >= 0.25) {
                    double instantMbps = (intervalBytes * 8.0) / (intervalSecs * 1_000_000);
                    post(() -> callback.onDownloadSpeedUpdate(round2(instantMbps)));
                    intervalStart = now;
                    intervalBytes = 0;
                }
            }
            is.close();
            long t1 = System.nanoTime();

            if (total == 0) return 0;
            double seconds = (t1 - t0) / 1_000_000_000.0;
            double mbps = (total * 8.0) / (seconds * 1_000_000);
            Log.d(TAG, String.format("  %d bytes en %.2fs → %.1f Mbps", total, seconds, mbps));
            return round2(mbps);

        } catch (Exception e) {
            Log.w(TAG, "Download falló (" + urlStr + "): " + e.getMessage());
            return 0;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /** Intenta resolver varios hosts en orden; devuelve el primero exitoso. */
    private static InetAddress resolveAny(String... hosts) {
        for (String host : hosts) {
            try {
                return InetAddress.getByName(host);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private void post(Runnable r) {
        handler.post(r);
    }
}
