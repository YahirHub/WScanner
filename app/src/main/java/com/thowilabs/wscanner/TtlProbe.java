package com.thowilabs.wscanner;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Obtención del TTL de respuesta de un host de forma OFFLINE.
 *
 * Android no permite abrir sockets ICMP crudos sin root, así que se apoya
 * en el binario del sistema {@code /system/bin/ping} (presente en todas las
 * imágenes AOSP). Se parsea la línea "ttl=NN" del stdout. Si el binario no
 * está disponible, si el proceso excede el timeout o si el equipo no responde
 * al ICMP echo, la función devuelve {@code -1} y el resto del pipeline
 * continúa con la información disponible (puertos, servicios, banners).
 *
 * <p>Limitaciones conocidas:
 * <ul>
 *   <li>Algunos ROM restringen {@code fork/exec} en background: se captura
 *       {@link Exception} y se degrada silenciosamente.</li>
 *   <li>Firewalls locales (Windows Defender, iOS Lockdown) descartan ICMP.
 *       Para esos casos se depende del fingerprint por puertos + banners.</li>
 *   <li>El TTL puede pasar por varios saltos si el equipo está tras un
 *       repetidor; {@link OsFingerprint} tolera ±2 en la clasificación.</li>
 * </ul>
 */
public final class TtlProbe {

    private static final String TAG = "WScanner.TTL";
    private static final Pattern TTL_PATTERN =
            Pattern.compile("ttl=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final long PROCESS_TIMEOUT_MS = 1200L;

    private TtlProbe() {}

    /**
     * Devuelve el TTL observado o -1 si no se pudo determinar.
     */
    public static int probe(String ip) {
        if (ip == null || ip.isEmpty()) return -1;
        Process process = null;
        try {
            // -c 1: un solo eco. -W 1: 1 s de espera. -n: sin resolución DNS.
            process = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", "-n", ip)
                    .redirectErrorStream(true)
                    .start();
            final Process p = process;
            Thread killer = new Thread(() -> {
                try {
                    Thread.sleep(PROCESS_TIMEOUT_MS);
                    p.destroy();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            killer.setDaemon(true);
            killer.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = TTL_PATTERN.matcher(line);
                    if (m.find()) {
                        killer.interrupt();
                        int ttl = Integer.parseInt(m.group(1));
                        Log.v(TAG, "probe(" + ip + ") = " + ttl);
                        return ttl;
                    }
                }
            }
        } catch (Exception e) {
            // ROMs sin /system/bin/ping, SELinux o proceso interrumpido: degradar silenciosamente.
            Log.v(TAG, "probe(" + ip + ") no disponible: " + e.getMessage());
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Exception ignored) {}
            }
        }
        return -1;
    }
}
