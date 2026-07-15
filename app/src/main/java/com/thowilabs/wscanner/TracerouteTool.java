package com.thowilabs.wscanner;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traceroute usando ping del sistema con TTL incremental.
 * Funciona sin root ni NDK en todos los dispositivos Android.
 */
public class TracerouteTool {

    public interface Callback {
        void onHopFound(int ttl, String ip, double rttMs);
        void onHopTimeout(int ttl);
        void onDestinationReached(int totalHops);
        void onProgress(int currentHop, int maxHops);
        void onError(String message);
    }

    private static final int DEFAULT_MAX_HOPS = 30;
    private static final int DEFAULT_TIMEOUT = 2; // segundos

    // Patrones para parsear salida de ping en múltiples locales
    private static final Pattern IP_PATTERN = Pattern.compile(
            "(?:from|desde|de|von|da)\\s+([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(?:time|tiempo|temps|zeit)[=<:]?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms",
            Pattern.CASE_INSENSITIVE);

    private final Handler handler = new Handler(Looper.getMainLooper());

    public void trace(String host, Callback callback) {
        trace(host, DEFAULT_MAX_HOPS, callback);
    }

    public void trace(String host, int maxHops, Callback callback) {
        new Thread(() -> {
            try {
                // Resolver hostname a IP para detección de destino
                String targetIp = resolveHost(host);

                for (int ttl = 1; ttl <= maxHops; ttl++) {
                    final int currentTtl = ttl;
                    post(() -> callback.onProgress(currentTtl, maxHops));

                    String result = pingWithTtl(host, ttl, DEFAULT_TIMEOUT);
                    if (result == null) {
                        post(() -> callback.onHopTimeout(currentTtl));
                        continue;
                    }

                    String ip = extractIp(result);
                    double rtt = extractTime(result);

                    if (ip != null) {
                        post(() -> callback.onHopFound(currentTtl, ip, rtt));
                        if (ip.equals(targetIp)) {
                            post(() -> callback.onDestinationReached(currentTtl));
                            return;
                        }
                    } else {
                        post(() -> callback.onHopTimeout(currentTtl));
                    }
                }
                // Si llegamos al final sin alcanzar el destino
                post(() -> callback.onDestinationReached(-1));
            } catch (Exception e) {
                post(() -> callback.onError(e.getMessage() != null
                        ? e.getMessage() : "Error en traceroute"));
            }
        }).start();
    }

    private String pingWithTtl(String host, int ttl, int timeoutSec) {
        try {
            String cmd = "ping -c 1 -W " + timeoutSec + " -t " + ttl + " " + host;
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractIp(String pingOutput) {
        Matcher m = IP_PATTERN.matcher(pingOutput);
        return m.find() ? m.group(1) : null;
    }

    private double extractTime(String pingOutput) {
        Matcher m = TIME_PATTERN.matcher(pingOutput);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static String resolveHost(String host) {
        try {
            return java.net.InetAddress.getByName(host).getHostAddress();
        } catch (Exception e) {
            return host; // fallback: usar el string tal cual (puede ser IP)
        }
    }

    private void post(Runnable r) {
        handler.post(r);
    }
}
