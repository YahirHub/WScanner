package com.thowilabs.wscanner;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test de velocidad en dos etapas.
 *
 * 1) Test normal: latencia, jitter, descarga y subida usando backends públicos
 *    compatibles con HTTP. Se prueban en orden y el fallback es transparente
 *    para la UI.
 * 2) Descarga real: conserva el método histórico de WScanner descargando un
 *    archivo estático desde un servidor distinto para contrastar el resultado.
 *
 * No se envía telemetría de resultados desde WScanner.
 */
public class SpeedTestTool {

    private static final String TAG = "WScanner.Speed";

    // Test normal. Cloudflare es el backend principal. Si falla, WScanner obtiene
    // la lista pública oficial de LibreSpeed, mide rápidamente disponibilidad y
    // prueba hasta tres servidores de menor latencia. Todo el fallback es interno.
    private static final StandardProvider CLOUDFLARE_PROVIDER = new CloudflareProvider();
    private static final String LIBRESPEED_SERVER_LIST_URL =
            "https://librespeed.org/backend-servers/servers.php";
    private static final int LIBRESPEED_PROBE_WORKERS = 8;
    private static final int LIBRESPEED_MAX_FULL_ATTEMPTS = 3;
    private static final int PROBE_CONNECT_TIMEOUT_MS = 3_000;
    private static final int PROBE_READ_TIMEOUT_MS = 3_000;
    private static final int MAX_SERVER_LIST_BYTES = 1024 * 1024;

    // Descarga real histórica: se conserva como segunda etapa y fallback.
    private static final String[] REAL_DOWNLOAD_URLS = {
            "http://speedtest.tele2.net/10MB.zip",
            "http://ipv4.download.thinkbroadband.com/5MB.zip"
    };

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int LATENCY_SAMPLES = 8;
    private static final int DOWNLOAD_STREAMS = 4;
    private static final int UPLOAD_STREAMS = 3;
    private static final long DOWNLOAD_TARGET_NS = TimeUnit.SECONDS.toNanos(4);
    private static final long UPLOAD_TARGET_NS = TimeUnit.SECONDS.toNanos(4);
    private static final long MAX_DOWNLOAD_TOTAL_BYTES = 200L * 1024L * 1024L;
    private static final long MAX_UPLOAD_TOTAL_BYTES = 120L * 1024L * 1024L;
    private static final long MIN_DOWNLOAD_PER_STREAM = 256L * 1024L;
    private static final long MAX_DOWNLOAD_PER_STREAM = 50L * 1024L * 1024L;
    private static final long MIN_UPLOAD_PER_STREAM = 128L * 1024L;
    private static final long MAX_UPLOAD_PER_STREAM = 40L * 1024L * 1024L;

    public interface Callback {
        void onPhase(String phase);
        void onLatencyStage();
        void onTransferStage();
        void onUploadStage();
        void onProgress(int percent);
        void onPingResult(double pingMs, double jitterMs);
        void onDownloadSpeedUpdate(double currentMbps);
        void onDownloadResult(double mbps);
        void onUploadSpeedUpdate(double currentMbps);
        void onUploadResult(double mbps);
        void onPrimaryFinished(double pingMs, double jitterMs, double downMbps, double upMbps);
        void onRealDownloadStart();
        void onRealDownloadSpeedUpdate(double currentMbps);
        void onRealDownloadResult(double mbps);
        void onFinished(double pingMs, double jitterMs, double downMbps, double upMbps,
                        double realDownloadMbps);
        void onError(String message);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private volatile Thread activeWorker;

    /** Inicia una medición nueva e invalida/interrumpe cualquier ejecución anterior. */
    public synchronized void runTest(Callback callback) {
        if (activeWorker != null) activeWorker.interrupt();
        final int runId = generation.incrementAndGet();
        Thread worker = new Thread(() -> {
            try {
                runInternal(runId, callback);
            } finally {
                synchronized (SpeedTestTool.this) {
                    if (activeWorker == Thread.currentThread()) activeWorker = null;
                }
            }
        }, "WScanner-SpeedTest");
        activeWorker = worker;
        worker.start();
    }

    /** Cancela el test activo y descarta cualquier callback tardío. */
    public synchronized void cancel() {
        generation.incrementAndGet();
        if (activeWorker != null) {
            activeWorker.interrupt();
            activeWorker = null;
        }
    }

    private void runInternal(int runId, Callback callback) {
        try {
            post(runId, () -> callback.onProgress(2));
            post(runId, () -> callback.onPhase("Preparando test de velocidad…"));

            StandardResult primary = null;
            Exception lastError = null;

            // 1) Cloudflare: red edge global y endpoints oficiales de download/upload.
            try {
                primary = tryStandardProvider(runId, CLOUDFLARE_PROVIDER, callback);
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "Backend Cloudflare no disponible: " + e.getMessage());
            }

            // 2) Fallback multi-servidor: solo se consulta si el principal falló,
            // evitando una selección de servidores innecesaria en el camino normal.
            if (primary == null || primary.downMbps <= 0) {
                try {
                    post(runId, callback::onLatencyStage);
                    post(runId, () -> callback.onPhase("Seleccionando servidor con mejor respuesta…"));
                    List<StandardProvider> backups = discoverLibreSpeedProviders(runId);
                    for (StandardProvider provider : backups) {
                        if (!isActive(runId)) return;
                        try {
                            primary = tryStandardProvider(runId, provider, callback);
                            if (primary != null && primary.downMbps > 0) break;
                        } catch (Exception e) {
                            lastError = e;
                            Log.w(TAG, "Backend " + provider.name()
                                    + " no disponible: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    lastError = e;
                    Log.w(TAG, "No se pudo preparar fallback LibreSpeed: " + e.getMessage());
                }
            }

            // Fallback transparente: conserva la técnica antigua para que el botón
            // siga ofreciendo una medición útil aunque los backends normales fallen.
            if (primary == null || primary.downMbps <= 0) {
                Log.w(TAG, "Backends normales agotados; usando compatibilidad de descarga directa");
                primary = runCompatibilityPrimary(runId, callback);
            }

            if (!isActive(runId)) return;
            if (primary == null || primary.downMbps <= 0) {
                String detail = lastError != null && lastError.getMessage() != null
                        ? lastError.getMessage() : "No hubo respuesta de los servidores de prueba";
                final String error = "No se pudo completar el test de velocidad.\n" + detail;
                post(runId, () -> callback.onError(error));
                return;
            }

            final StandardResult completedPrimary = primary;
            post(runId, () -> callback.onPingResult(
                    completedPrimary.pingMs, completedPrimary.jitterMs));
            post(runId, () -> callback.onDownloadResult(completedPrimary.downMbps));
            post(runId, () -> callback.onUploadResult(completedPrimary.upMbps));
            post(runId, () -> callback.onPrimaryFinished(
                    completedPrimary.pingMs,
                    completedPrimary.jitterMs,
                    completedPrimary.downMbps,
                    completedPrimary.upMbps));
            post(runId, () -> callback.onProgress(72));

            // Segunda pantalla: descarga convencional de un archivo real.
            post(runId, callback::onRealDownloadStart);
            post(runId, () -> callback.onPhase("Iniciando test de descarga real…"));

            double realDownload = measureRealDownload(runId, callback);
            if (!isActive(runId)) return;

            final double finalRealDownload = realDownload;
            post(runId, () -> callback.onRealDownloadResult(finalRealDownload));
            post(runId, () -> callback.onProgress(100));
            post(runId, () -> callback.onFinished(
                    completedPrimary.pingMs,
                    completedPrimary.jitterMs,
                    completedPrimary.downMbps,
                    completedPrimary.upMbps,
                    finalRealDownload));

        } catch (Exception e) {
            if (!isActive(runId)) return;
            Log.e(TAG, "Error en speed test", e);
            String message = e.getMessage() != null ? e.getMessage() : "Error desconocido";
            post(runId, () -> callback.onError(message));
        }
    }

    private StandardResult tryStandardProvider(int runId, StandardProvider provider,
                                               Callback callback) throws Exception {
        ensureActive(runId);
        Log.i(TAG, "Intentando backend normal: " + provider.name());
        StandardResult result = runStandardProvider(runId, provider, callback);
        if (result != null && result.downMbps > 0) {
            Log.i(TAG, String.format(Locale.US,
                    "Backend %s OK — ping %.1f ms, down %.1f Mbps, up %.1f Mbps",
                    provider.name(), result.pingMs, result.downMbps, result.upMbps));
        }
        return result;
    }

    /**
     * Obtiene la lista pública de LibreSpeed y selecciona silenciosamente hasta tres
     * servidores disponibles con menor latencia HTTP. No se envían resultados ni
     * telemetría al endpoint de resultados de LibreSpeed.
     */
    private List<StandardProvider> discoverLibreSpeedProviders(int runId) throws Exception {
        ensureActive(runId);
        JSONArray servers = fetchLibreSpeedServerList(runId);
        List<StandardProvider> candidates = new ArrayList<>();
        for (int i = 0; i < servers.length(); i++) {
            JSONObject item = servers.optJSONObject(i);
            if (item == null) continue;

            String base = item.optString("server", "").trim();
            String dl = item.optString("dlURL", "").trim();
            String ul = item.optString("ulURL", "").trim();
            String ping = item.optString("pingURL", "").trim();
            String name = item.optString("name", "LibreSpeed").trim();
            if (base.isEmpty() || dl.isEmpty() || ul.isEmpty() || ping.isEmpty()) continue;

            try {
                candidates.add(new LibreSpeedProvider(name, base, dl, ul, ping));
            } catch (IllegalArgumentException ignored) {
                // Entrada inválida/obsoleta de la lista: se ignora sin afectar la UI.
            }
        }

        if (candidates.isEmpty()) {
            throw new IllegalStateException("lista LibreSpeed sin servidores utilizables");
        }

        List<ProviderProbe> probes = probeProviders(runId, candidates);
        probes.sort(Comparator.comparingDouble(p -> p.latencyMs));

        List<StandardProvider> selected = new ArrayList<>();
        for (ProviderProbe probe : probes) {
            if (probe.latencyMs <= 0 || !Double.isFinite(probe.latencyMs)) continue;
            selected.add(probe.provider);
            if (selected.size() >= LIBRESPEED_MAX_FULL_ATTEMPTS) break;
        }
        if (selected.isEmpty()) {
            throw new IllegalStateException("ningún servidor LibreSpeed respondió");
        }
        Log.i(TAG, "Fallback LibreSpeed preparado con " + selected.size() + " servidor(es)");
        return selected;
    }

    private JSONArray fetchLibreSpeedServerList(int runId) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = openGet(new URL(LIBRESPEED_SERVER_LIST_URL));
            conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_READ_TIMEOUT_MS * 2);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("lista LibreSpeed HTTP " + code);
            }

            StringBuilder json = new StringBuilder();
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    ensureActive(runId);
                    total += read;
                    if (total > MAX_SERVER_LIST_BYTES) {
                        throw new IllegalStateException("lista LibreSpeed demasiado grande");
                    }
                    json.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            return new JSONArray(json.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private List<ProviderProbe> probeProviders(int runId, List<StandardProvider> providers)
            throws Exception {
        int workers = Math.max(1, Math.min(LIBRESPEED_PROBE_WORKERS, providers.size()));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<ProviderProbe>> tasks = new ArrayList<>();
            for (StandardProvider provider : providers) {
                tasks.add(() -> new ProviderProbe(provider, probeProviderLatency(runId, provider)));
            }

            // Presupuesto global acotado: los servidores caídos no pueden retrasar
            // indefinidamente el fallback ni hacer visible el cambio de proveedor.
            List<Future<ProviderProbe>> futures = pool.invokeAll(
                    tasks, PROBE_READ_TIMEOUT_MS * 3L, TimeUnit.MILLISECONDS);
            List<ProviderProbe> results = new ArrayList<>();
            for (Future<ProviderProbe> future : futures) {
                if (!isActive(runId)) return Collections.emptyList();
                if (future.isCancelled()) continue;
                try {
                    ProviderProbe result = future.get();
                    if (result != null && result.latencyMs > 0) results.add(result);
                } catch (Exception ignored) {
                    // Un servidor caído no invalida la selección completa.
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private double probeProviderLatency(int runId, StandardProvider provider) {
        HttpURLConnection conn = null;
        try {
            ensureActive(runId);
            conn = openGet(new URL(provider.pingUrl(nonce())));
            conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_READ_TIMEOUT_MS);
            long start = System.nanoTime();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return 0;
            try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                in.read();
            }
            return (System.nanoTime() - start) / 1_000_000.0;
        } catch (Exception ignored) {
            return 0;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private StandardResult runStandardProvider(int runId, StandardProvider provider,
                                               Callback callback) throws Exception {
        post(runId, callback::onLatencyStage);
        post(runId, () -> callback.onPhase("Midiendo latencia…"));
        post(runId, () -> callback.onProgress(6));

        double[] latency = measureHttpLatency(runId, provider);
        if (latency[0] <= 0) throw new IllegalStateException("latencia no disponible");
        post(runId, () -> callback.onPingResult(latency[0], latency[1]));

        // Warm-up pequeño para estimar el tamaño apropiado del test principal.
        post(runId, () -> callback.onPhase("Calentando conexión…"));
        post(runId, () -> callback.onProgress(14));
        double warmDown = measureSingleDownload(runId, provider, 512L * 1024L, null);
        if (warmDown <= 0) throw new IllegalStateException("descarga de calentamiento falló");

        post(runId, callback::onTransferStage);
        post(runId, () -> callback.onPhase("Midiendo descarga…"));
        post(runId, () -> callback.onProgress(22));

        double down = measureAdaptiveDownload(runId, provider, warmDown,
                speed -> post(runId, () -> callback.onDownloadSpeedUpdate(speed)));
        if (down <= 0) throw new IllegalStateException("descarga principal falló");
        post(runId, () -> callback.onDownloadResult(down));
        post(runId, () -> callback.onProgress(48));

        post(runId, callback::onUploadStage);
        post(runId, () -> callback.onPhase("Midiendo subida…"));
        double warmUp = measureSingleUpload(runId, provider, 128L * 1024L, null);
        if (warmUp <= 0) throw new IllegalStateException("subida de calentamiento falló");

        double up = measureAdaptiveUpload(runId, provider, warmUp,
                speed -> post(runId, () -> callback.onUploadSpeedUpdate(speed)));
        if (up <= 0) throw new IllegalStateException("subida principal falló");
        post(runId, () -> callback.onUploadResult(up));
        post(runId, () -> callback.onProgress(70));

        return new StandardResult(latency[0], latency[1], down, up);
    }

    /**
     * Compatibilidad reducida con el motor antiguo. No inventa upload: devuelve 0
     * cuando ningún backend bidireccional está disponible.
     */
    private StandardResult runCompatibilityPrimary(int runId, Callback callback) {
        post(runId, callback::onLatencyStage);
        post(runId, () -> callback.onPhase("Midiendo latencia…"));
        post(runId, () -> callback.onProgress(8));

        double[] latency = measureLegacyLatency(runId);
        post(runId, () -> callback.onPingResult(latency[0], latency[1]));

        post(runId, callback::onTransferStage);
        post(runId, () -> callback.onPhase("Midiendo descarga…"));
        post(runId, () -> callback.onProgress(25));
        double down = measureRealDownloadInternal(
                runId,
                speed -> post(runId, () -> callback.onDownloadSpeedUpdate(speed)));
        post(runId, () -> callback.onDownloadResult(down));
        post(runId, () -> callback.onUploadResult(0));
        post(runId, () -> callback.onProgress(70));
        return new StandardResult(latency[0], latency[1], down, 0);
    }

    // ─────────────────────────────────────────────────────────────
    // Latencia / jitter HTTP
    // ─────────────────────────────────────────────────────────────

    private double[] measureHttpLatency(int runId, StandardProvider provider) throws Exception {
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < LATENCY_SAMPLES; i++) {
            ensureActive(runId);
            HttpURLConnection conn = null;
            try {
                URL url = new URL(provider.pingUrl(nonce()));
                conn = openGet(url);
                conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(PROBE_READ_TIMEOUT_MS);
                long start = System.nanoTime();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) continue;
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    in.read(); // fuerza llegada del primer byte cuando exista
                }
                long end = System.nanoTime();
                samples.add((end - start) / 1_000_000.0);
            } catch (Exception e) {
                Log.d(TAG, provider.name() + " latency sample falló: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        if (samples.size() < 2) return new double[]{0, 0};
        return new double[]{round2(percentile(samples, 0.5)), round2(calculateJitter(samples))};
    }

    private double[] measureLegacyLatency(int runId) {
        List<Double> samples = new ArrayList<>();
        String url = REAL_DOWNLOAD_URLS[0];
        for (int i = 0; i < 4; i++) {
            if (!isActive(runId)) break;
            HttpURLConnection conn = null;
            try {
                conn = openGet(new URL(url));
                conn.setRequestProperty("Range", "bytes=0-0");
                long start = System.nanoTime();
                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) {
                    try (InputStream in = conn.getInputStream()) {
                        in.read();
                    }
                    samples.add((System.nanoTime() - start) / 1_000_000.0);
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        if (samples.isEmpty()) return new double[]{0, 0};
        return new double[]{round2(percentile(samples, 0.5)), round2(calculateJitter(samples))};
    }

    // ─────────────────────────────────────────────────────────────
    // Download normal, multistream
    // ─────────────────────────────────────────────────────────────

    private double measureAdaptiveDownload(int runId, StandardProvider provider,
                                           double estimateMbps, SpeedListener listener)
            throws Exception {
        long perStream = choosePerStreamBytes(
                estimateMbps, DOWNLOAD_TARGET_NS, DOWNLOAD_STREAMS,
                MIN_DOWNLOAD_PER_STREAM, MAX_DOWNLOAD_PER_STREAM,
                MAX_DOWNLOAD_TOTAL_BYTES);

        double measured = measureParallelDownload(
                runId, provider, perStream, DOWNLOAD_STREAMS, listener);
        if (measured <= 0) return 0;

        // En enlaces rápidos el warm-up puede estar dominado por TLS/TTFB y elegir
        // una muestra demasiado pequeña. Se permite una única rampa adicional.
        long usedBytes = perStream * DOWNLOAD_STREAMS;
        long remainingBudget = Math.max(0, MAX_DOWNLOAD_TOTAL_BYTES - usedBytes);
        if (remainingBudget >= MIN_DOWNLOAD_PER_STREAM * DOWNLOAD_STREAMS) {
            long refined = choosePerStreamBytes(
                    measured, DOWNLOAD_TARGET_NS, DOWNLOAD_STREAMS,
                    MIN_DOWNLOAD_PER_STREAM, MAX_DOWNLOAD_PER_STREAM,
                    remainingBudget);
            if (shouldRepeatMeasurement(perStream, refined, MAX_DOWNLOAD_PER_STREAM)) {
                double second = measureParallelDownload(
                        runId, provider, refined, DOWNLOAD_STREAMS, listener);
                if (second > 0) measured = Math.max(measured, second);
            }
        }
        return round2(measured);
    }

    private double measureSingleDownload(int runId, StandardProvider provider, long bytes,
                                         SpeedListener listener) throws Exception {
        TransferMeter meter = new TransferMeter(listener);
        long start = System.nanoTime();
        long total = downloadRequest(runId, provider.downloadUrl(bytes, nonce()), bytes, meter);
        long elapsed = System.nanoTime() - start;
        return total > 0 ? round2(calculateMbps(total, elapsed)) : 0;
    }

    private double measureParallelDownload(int runId, StandardProvider provider,
                                           long bytesPerStream, int streams,
                                           SpeedListener listener) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(streams);
        CountDownLatch gate = new CountDownLatch(1);
        TransferMeter meter = new TransferMeter(listener);
        List<Future<Long>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < streams; i++) {
                final long requestNonce = nonce();
                futures.add(pool.submit(() -> {
                    gate.await();
                    return downloadRequest(runId,
                            provider.downloadUrl(bytesPerStream, requestNonce),
                            bytesPerStream, meter);
                }));
            }
            long start = System.nanoTime();
            meter.start(start);
            gate.countDown();

            long total = 0;
            int success = 0;
            for (Future<Long> future : futures) {
                long bytes = future.get(READ_TIMEOUT_MS * 3L, TimeUnit.MILLISECONDS);
                if (bytes > 0) {
                    total += bytes;
                    success++;
                }
            }
            long elapsed = System.nanoTime() - start;
            if (success < Math.max(1, (streams + 1) / 2) || total == 0) return 0;
            return round2(calculateMbps(total, elapsed));
        } finally {
            pool.shutdownNow();
        }
    }

    private long downloadRequest(int runId, String urlString, long maxBytes,
                                 TransferMeter meter) throws Exception {
        ensureActive(runId);
        HttpURLConnection conn = null;
        try {
            conn = openGet(new URL(urlString));
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new IllegalStateException("HTTP " + code);
            }

            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024)) {
                while (total < maxBytes) {
                    ensureActive(runId);
                    int wanted = (int) Math.min(buffer.length, maxBytes - total);
                    int read = in.read(buffer, 0, wanted);
                    if (read < 0) break;
                    total += read;
                    if (meter != null) meter.add(read);
                }
            }
            return total;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Upload normal, multistream
    // ─────────────────────────────────────────────────────────────

    private double measureAdaptiveUpload(int runId, StandardProvider provider,
                                         double estimateMbps, SpeedListener listener)
            throws Exception {
        long perStream = choosePerStreamBytes(
                estimateMbps, UPLOAD_TARGET_NS, UPLOAD_STREAMS,
                MIN_UPLOAD_PER_STREAM, MAX_UPLOAD_PER_STREAM,
                MAX_UPLOAD_TOTAL_BYTES);

        double measured = measureParallelUpload(
                runId, provider, perStream, UPLOAD_STREAMS, listener);
        if (measured <= 0) return 0;

        long usedBytes = perStream * UPLOAD_STREAMS;
        long remainingBudget = Math.max(0, MAX_UPLOAD_TOTAL_BYTES - usedBytes);
        if (remainingBudget >= MIN_UPLOAD_PER_STREAM * UPLOAD_STREAMS) {
            long refined = choosePerStreamBytes(
                    measured, UPLOAD_TARGET_NS, UPLOAD_STREAMS,
                    MIN_UPLOAD_PER_STREAM, MAX_UPLOAD_PER_STREAM,
                    remainingBudget);
            if (shouldRepeatMeasurement(perStream, refined, MAX_UPLOAD_PER_STREAM)) {
                double second = measureParallelUpload(
                        runId, provider, refined, UPLOAD_STREAMS, listener);
                if (second > 0) measured = Math.max(measured, second);
            }
        }
        return round2(measured);
    }

    private double measureSingleUpload(int runId, StandardProvider provider, long bytes,
                                       SpeedListener listener) throws Exception {
        TransferMeter meter = new TransferMeter(listener);
        long start = System.nanoTime();
        long total = uploadRequest(runId, provider.uploadUrl(nonce()), bytes, meter);
        long elapsed = System.nanoTime() - start;
        return total > 0 ? round2(calculateMbps(total, elapsed)) : 0;
    }

    private double measureParallelUpload(int runId, StandardProvider provider,
                                         long bytesPerStream, int streams,
                                         SpeedListener listener) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(streams);
        CountDownLatch gate = new CountDownLatch(1);
        TransferMeter meter = new TransferMeter(listener);
        List<Future<Long>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < streams; i++) {
                final long requestNonce = nonce();
                futures.add(pool.submit(() -> {
                    gate.await();
                    return uploadRequest(runId, provider.uploadUrl(requestNonce),
                            bytesPerStream, meter);
                }));
            }
            long start = System.nanoTime();
            meter.start(start);
            gate.countDown();

            long total = 0;
            int success = 0;
            for (Future<Long> future : futures) {
                long bytes = future.get(READ_TIMEOUT_MS * 3L, TimeUnit.MILLISECONDS);
                if (bytes > 0) {
                    total += bytes;
                    success++;
                }
            }
            long elapsed = System.nanoTime() - start;
            if (success < Math.max(1, (streams + 1) / 2) || total == 0) return 0;
            return round2(calculateMbps(total, elapsed));
        } finally {
            pool.shutdownNow();
        }
    }

    private long uploadRequest(int runId, String urlString, long bytes,
                               TransferMeter meter) throws Exception {
        ensureActive(runId);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            configureConnection(conn);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setFixedLengthStreamingMode(bytes);

            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            try (OutputStream out = new BufferedOutputStream(conn.getOutputStream(), 64 * 1024)) {
                while (written < bytes) {
                    ensureActive(runId);
                    int n = (int) Math.min(buffer.length, bytes - written);
                    out.write(buffer, 0, n);
                    written += n;
                    if (meter != null) meter.add(n);
                }
                out.flush();
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new IllegalStateException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] discard = new byte[1024];
                while (in.read(discard) != -1) {
                    // Respuesta pequeña; solo se consume para liberar la conexión.
                }
            }
            return written;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Segunda etapa: descarga real histórica
    // ─────────────────────────────────────────────────────────────

    private double measureRealDownload(int runId, Callback callback) {
        return measureRealDownloadInternal(
                runId,
                speed -> post(runId, () -> callback.onRealDownloadSpeedUpdate(speed)));
    }

    private double measureRealDownloadInternal(int runId, SpeedListener listener) {
        for (String url : REAL_DOWNLOAD_URLS) {
            if (!isActive(runId)) return 0;
            double result = downloadStaticFile(runId, url, listener);
            if (result > 0) return result;
            Log.w(TAG, "Descarga real falló; probando siguiente servidor");
        }
        return 0;
    }

    private double downloadStaticFile(int runId, String urlString, SpeedListener listener) {
        HttpURLConnection conn = null;
        try {
            Log.d(TAG, "Descarga real → " + urlString);
            conn = openGet(new URL(urlString));
            long start = System.nanoTime();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return 0;

            TransferMeter meter = new TransferMeter(listener);
            meter.start(start);
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    ensureActive(runId);
                    total += read;
                    meter.add(read);
                }
            }
            long elapsed = System.nanoTime() - start;
            if (total <= 0) return 0;
            return round2(calculateMbps(total, elapsed));
        } catch (Exception e) {
            Log.w(TAG, "Descarga real falló (" + urlString + "): " + e.getMessage());
            return 0;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP / helpers
    // ─────────────────────────────────────────────────────────────

    private HttpURLConnection openGet(URL url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        configureConnection(conn);
        conn.setRequestMethod("GET");
        return conn;
    }

    private void configureConnection(HttpURLConnection conn) {
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        conn.setDefaultUseCaches(false);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "WScanner/1.0");
        conn.setRequestProperty("Cache-Control", "no-store, no-cache");
        conn.setRequestProperty("Pragma", "no-cache");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("Connection", "keep-alive");
    }

    private boolean isActive(int runId) {
        return generation.get() == runId;
    }

    private void ensureActive(int runId) throws InterruptedException {
        if (!isActive(runId) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Test cancelado");
        }
    }

    private void post(int runId, Runnable runnable) {
        handler.post(() -> {
            if (isActive(runId)) runnable.run();
        });
    }

    private static long nonce() {
        return System.nanoTime() ^ System.currentTimeMillis();
    }

    static double calculateMbps(long bytes, long elapsedNs) {
        if (bytes <= 0 || elapsedNs <= 0) return 0;
        return (bytes * 8.0 * 1_000_000_000.0) / (elapsedNs * 1_000_000.0);
    }

    static double calculateJitter(List<Double> samples) {
        if (samples == null || samples.size() < 2) return 0;
        double total = 0;
        for (int i = 1; i < samples.size(); i++) {
            total += Math.abs(samples.get(i) - samples.get(i - 1));
        }
        return total / (samples.size() - 1);
    }

    static double percentile(List<Double> values, double percentile) {
        if (values == null || values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double p = Math.max(0, Math.min(1, percentile));
        int index = (int) Math.round((sorted.size() - 1) * p);
        return sorted.get(index);
    }

    static long choosePerStreamBytes(double estimatedMbps, long targetDurationNs, int streams,
                                     long minPerStream, long maxPerStream, long maxTotalBytes) {
        if (streams <= 0) return minPerStream;
        double seconds = targetDurationNs / 1_000_000_000.0;
        double targetTotal = Math.max(0, estimatedMbps) * 1_000_000.0 / 8.0 * seconds;
        long cappedTotal = (long) Math.min(targetTotal, maxTotalBytes);
        long perStream = cappedTotal / streams;
        return Math.max(minPerStream, Math.min(maxPerStream, perStream));
    }

    static boolean shouldRepeatMeasurement(long currentBytes, long refinedBytes, long maxBytes) {
        if (currentBytes <= 0 || refinedBytes <= currentBytes || currentBytes >= maxBytes) return false;
        return refinedBytes >= Math.min(maxBytes, (long) Math.ceil(currentBytes * 1.35));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private interface SpeedListener {
        void onSpeed(double mbps);
    }

    private static final class TransferMeter {
        private final AtomicLong totalBytes = new AtomicLong();
        private final AtomicLong lastEmitNs = new AtomicLong();
        private final SpeedListener listener;
        private volatile long startNs;

        TransferMeter(SpeedListener listener) {
            this.listener = listener;
            this.startNs = System.nanoTime();
            this.lastEmitNs.set(this.startNs);
        }

        void start(long startNs) {
            this.startNs = startNs;
            this.lastEmitNs.set(startNs);
            this.totalBytes.set(0);
        }

        void add(long bytes) {
            if (bytes <= 0) return;
            long total = totalBytes.addAndGet(bytes);
            if (listener == null) return;
            long now = System.nanoTime();
            long previous = lastEmitNs.get();
            if (now - previous < TimeUnit.MILLISECONDS.toNanos(250)) return;
            if (!lastEmitNs.compareAndSet(previous, now)) return;
            double speed = calculateMbps(total, now - startNs);
            listener.onSpeed(round2(speed));
        }
    }

    private static final class StandardResult {
        final double pingMs;
        final double jitterMs;
        final double downMbps;
        final double upMbps;

        StandardResult(double pingMs, double jitterMs, double downMbps, double upMbps) {
            this.pingMs = pingMs;
            this.jitterMs = jitterMs;
            this.downMbps = downMbps;
            this.upMbps = upMbps;
        }
    }

    private interface StandardProvider {
        String name();
        String pingUrl(long nonce);
        String downloadUrl(long bytes, long nonce);
        String uploadUrl(long nonce);
    }

    private static final class CloudflareProvider implements StandardProvider {
        private static final String BASE = "https://speed.cloudflare.com";

        @Override public String name() { return "Cloudflare"; }

        @Override public String pingUrl(long nonce) {
            return BASE + "/__down?bytes=0&measId=" + nonce;
        }

        @Override public String downloadUrl(long bytes, long nonce) {
            return BASE + "/__down?bytes=" + bytes + "&measId=" + nonce;
        }

        @Override public String uploadUrl(long nonce) {
            return BASE + "/__up?measId=" + nonce;
        }
    }

    private static final class LibreSpeedProvider implements StandardProvider {
        private final String name;
        private final String base;
        private final String downloadPath;
        private final String uploadPath;
        private final String pingPath;

        LibreSpeedProvider(String name, String base, String downloadPath,
                           String uploadPath, String pingPath) {
            this.name = name == null || name.trim().isEmpty() ? "LibreSpeed" : name.trim();
            this.base = normalizeBaseUrl(base);
            this.downloadPath = downloadPath;
            this.uploadPath = uploadPath;
            this.pingPath = pingPath;
        }

        @Override public String name() { return "LibreSpeed · " + name; }

        @Override public String pingUrl(long nonce) {
            return appendQuery(resolveUrl(base, pingPath), "r=" + nonce);
        }

        @Override public String downloadUrl(long bytes, long nonce) {
            long mib = Math.max(1L, (bytes + 1024L * 1024L - 1L) / (1024L * 1024L));
            return appendQuery(resolveUrl(base, downloadPath),
                    "ckSize=" + mib + "&r=" + nonce);
        }

        @Override public String uploadUrl(long nonce) {
            return appendQuery(resolveUrl(base, uploadPath), "r=" + nonce);
        }

        private static String normalizeBaseUrl(String value) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.startsWith("//")) normalized = "https:" + normalized;
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                throw new IllegalArgumentException("URL de servidor inválida");
            }
            return normalized.endsWith("/")
                    ? normalized.substring(0, normalized.length() - 1) : normalized;
        }

        private static String resolveUrl(String base, String path) {
            String p = path == null ? "" : path.trim();
            if (p.startsWith("http://") || p.startsWith("https://")) return p;
            if (p.startsWith("//")) return "https:" + p;
            return base + "/" + (p.startsWith("/") ? p.substring(1) : p);
        }

        private static String appendQuery(String url, String query) {
            return url + (url.contains("?") ? "&" : "?") + query;
        }
    }

    private static final class ProviderProbe {
        final StandardProvider provider;
        final double latencyMs;

        ProviderProbe(StandardProvider provider, double latencyMs) {
            this.provider = provider;
            this.latencyMs = latencyMs;
        }
    }
}
