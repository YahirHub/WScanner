package com.thowilabs.wscanner;

import android.net.Network;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Descubrimiento UPnP/SSDP mediante M-SEARCH multicast.
 *
 * Mantiene todo el trabajo dentro de la LAN. Además del nombre, conserva
 * metadatos autoanunciados por el propio dispositivo (fabricante, modelo,
 * tipo UPnP y SERVER) para mejorar la identificación sin depender de una API
 * ni de un diccionario remoto.
 */
public final class SsdpDiscovery {

    private static final String TAG = "WScanner.SSDP";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int TIMEOUT_MS = 4000;

    private static final String MSEARCH =
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: ssdp:all\r\n" +
            "\r\n";

    private SsdpDiscovery() {}

    /** Compatibilidad con el API anterior: IP -> nombre. */
    public static Map<String, String> discover(InetAddress localAddr) {
        Map<String, String> names = new HashMap<>();
        for (Map.Entry<String, Result> entry : discoverDetailed(localAddr, null).entrySet()) {
            names.put(entry.getKey(), entry.getValue().displayName());
        }
        return names;
    }

    /**
     * Descubre dispositivos UPnP y devuelve metadatos estructurados.
     *
     * @param localAddr IPv4 local desde la que se debe emitir M-SEARCH.
     * @param network red Android seleccionada; se usa para fijar las peticiones
     *                HTTP de LOCATION a la misma WiFi/Ethernet cuando existe.
     */
    public static Map<String, Result> discoverDetailed(InetAddress localAddr, Network network) {
        Map<String, Result> results = new HashMap<>();
        Map<String, String> locations = new HashMap<>();
        long t0 = System.currentTimeMillis();

        Log.i(TAG, "═══════════════════════════════════════");
        Log.i(TAG, "🟢 Iniciando descubrimiento SSDP/UPnP");

        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.bind(localAddr != null
                    ? new InetSocketAddress(localAddr, 0)
                    : new InetSocketAddress(0));

            byte[] queryBytes = MSEARCH.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            DatagramPacket queryPacket = new DatagramPacket(queryBytes, queryBytes.length,
                    InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
            socket.send(queryPacket);
            try { Thread.sleep(120); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            socket.send(queryPacket);

            int responseCount = 0;
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;

            while (System.currentTimeMillis() < deadline) {
                try {
                    int remaining = (int) (deadline - System.currentTimeMillis());
                    if (remaining <= 0) break;
                    socket.setSoTimeout(Math.max(remaining, 100));

                    DatagramPacket responsePacket = new DatagramPacket(buf, buf.length);
                    socket.receive(responsePacket);
                    String response = new String(responsePacket.getData(), 0,
                            responsePacket.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                    if (!response.regionMatches(true, 0, "HTTP/1.1 200", 0, 12)) continue;

                    responseCount++;
                    String ip = responsePacket.getAddress().getHostAddress();
                    String server = cleanValue(extractHeader(response, "SERVER"), 160);
                    String location = cleanValue(extractHeader(response, "LOCATION"), 512);
                    String usn = cleanValue(extractHeader(response, "USN"), 512);
                    String st = cleanValue(extractHeader(response, "ST"), 256);

                    Result result = results.computeIfAbsent(ip, ignored -> new Result());
                    result.ip = ip;
                    if (server != null) result.server = server;
                    if (usn != null) result.usn = usn;
                    if (location != null) {
                        result.location = location;
                        locations.put(ip, location);
                    }
                    result.addService("UPnP/SSDP");
                    if (st != null) result.addService(shortUpnpType(st));

                    String typeSignals = joinSignals(usn, st);
                    String inferredType = inferUpnpDeviceType(typeSignals);
                    if (inferredType != null) result.deviceType = inferredType;

                    NameCandidate candidate = nameFromUpnpType(typeSignals);
                    if (candidate != null) result.offerName(candidate.name, candidate.score);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
            }

            Log.d(TAG, "SSDP: " + responseCount + " respuestas recibidas");

            // Las descripciones XML se resuelven fuera del receive loop para no perder
            // anuncios de otros equipos por una petición HTTP lenta.
            if (!locations.isEmpty()) {
                int workers = Math.min(8, locations.size());
                ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, workers));
                for (Map.Entry<String, String> entry : locations.entrySet()) {
                    String ip = entry.getKey();
                    String location = entry.getValue();
                    executor.execute(() -> {
                        Description description = fetchDescription(location, ip, network);
                        if (description == null) return;
                        synchronized (results) {
                            Result result = results.computeIfAbsent(ip, ignored -> new Result());
                            result.ip = ip;
                            if (description.friendlyName != null) {
                                result.offerName(description.friendlyName,
                                        scoreFriendlyName(description.friendlyName));
                            }
                            if (description.manufacturer != null) result.manufacturer = description.manufacturer;
                            if (description.model != null) result.model = description.model;
                            if (description.deviceType != null) {
                                String inferred = inferUpnpDeviceType(description.deviceType);
                                if (inferred != null) result.deviceType = inferred;
                                result.addService(shortUpnpType(description.deviceType));
                            }

                            // Si no hay friendlyName, fabricante+modelo sigue siendo una
                            // identidad autoanunciada mucho mejor que el software SERVER.
                            if (description.friendlyName == null) {
                                String product = joinProduct(description.manufacturer, description.model);
                                if (product != null) result.offerName(product, 6);
                            }
                        }
                    });
                }
                executor.shutdown();
                try {
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    executor.shutdownNow();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error en SSDP discovery: " + e.getMessage(), e);
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        // Dar siempre una identidad útil sin convertir SERVER (p.ej. lighttpd) en
        // nombre del equipo. SERVER se conserva únicamente como evidencia técnica.
        for (Result result : results.values()) {
            if (!isUseful(result.name)) {
                if (isUseful(result.deviceType)) result.name = result.deviceType;
                else result.name = "Dispositivo UPnP";
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        Log.i(TAG, "✅ SSDP completado en " + elapsed + " ms — " + results.size() + " dispositivos");
        Log.i(TAG, "═══════════════════════════════════════");
        return results;
    }

    private static Description fetchDescription(String locationUrl, String responderIp, Network network) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(locationUrl);
            String scheme = url.getProtocol();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;

            String host = url.getHost();
            if (host == null || responderIp == null || !host.equalsIgnoreCase(responderIp)) {
                Log.v(TAG, "LOCATION ignorado fuera del host respondedor: " + locationUrl);
                return null;
            }

            conn = (HttpURLConnection) (network != null
                    ? network.openConnection(url) : url.openConnection());
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1200);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "WScanner/1.0");
            conn.setInstanceFollowRedirects(false);

            if (conn.getResponseCode() != 200) return null;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder xml = new StringBuilder();
            char[] buf = new char[2048];
            int read;
            while ((read = reader.read(buf)) != -1 && xml.length() < 65536) {
                int remaining = 65536 - xml.length();
                xml.append(buf, 0, Math.min(read, remaining));
            }
            reader.close();
            return parseDescription(xml.toString());
        } catch (Exception e) {
            Log.v(TAG, "Error leyendo XML UPnP local: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    static Description parseDescription(String xml) {
        if (xml == null || xml.isEmpty()) return null;
        Description result = new Description();
        result.friendlyName = cleanXmlValue(extractTag(xml, "friendlyName"));
        result.manufacturer = cleanXmlValue(extractTag(xml, "manufacturer"));
        String modelName = cleanXmlValue(extractTag(xml, "modelName"));
        String modelNumber = cleanXmlValue(extractTag(xml, "modelNumber"));
        result.model = joinModel(modelName, modelNumber);
        result.deviceType = cleanXmlValue(extractTag(xml, "deviceType"));
        if (!isUseful(result.friendlyName) && !isUseful(result.manufacturer)
                && !isUseful(result.model) && !isUseful(result.deviceType)) return null;
        return result;
    }

    static String inferUpnpDeviceType(String raw) {
        if (raw == null) return null;
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("internetgatewaydevice")) return "Router / Puerta de enlace";
        if (lower.contains("wlanaccesspoint") || lower.contains("accesspoint")) return "Punto de acceso";
        if (lower.contains("mediarenderer") || lower.contains("dial-multiscreen")) return "Reproductor multimedia";
        if (lower.contains("mediaserver")) return "Servidor multimedia";
        if (lower.contains("printer")) return "Impresora";
        if (lower.contains("scanner")) return "Escáner";
        if (lower.contains("networkvideotransmitter") || lower.contains("digital_security_camera")
                || lower.contains("camera")) return "Cámara / dispositivo de video";
        return null;
    }

    private static NameCandidate nameFromUpnpType(String raw) {
        String type = inferUpnpDeviceType(raw);
        return type == null ? null : new NameCandidate(type, 5);
    }

    private static int scoreFriendlyName(String name) {
        if (!isUseful(name)) return 0;
        String cleaned = name.trim();
        if (cleaned.matches("(?i)device|unknown|upnp|rootdevice")) return 1;
        return 8;
    }

    private static String shortUpnpType(String raw) {
        if (!isUseful(raw)) return null;
        String value = raw.trim();
        int lastColon = value.lastIndexOf(':');
        if (lastColon > 0 && lastColon + 1 < value.length()
                && value.substring(lastColon + 1).matches("\\d+")) {
            value = value.substring(0, lastColon);
            lastColon = value.lastIndexOf(':');
        }
        if (lastColon >= 0 && lastColon + 1 < value.length()) value = value.substring(lastColon + 1);
        value = value.replace("uuid:", "").trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static String joinSignals(String... values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (!isUseful(value)) continue;
            if (out.length() > 0) out.append(' ');
            out.append(value);
        }
        return out.toString();
    }

    private static String joinProduct(String manufacturer, String model) {
        if (isUseful(manufacturer) && isUseful(model)) {
            if (model.toLowerCase(Locale.ROOT).contains(manufacturer.toLowerCase(Locale.ROOT))) return model;
            return manufacturer + " " + model;
        }
        if (isUseful(model)) return model;
        if (isUseful(manufacturer)) return manufacturer;
        return null;
    }

    private static String joinModel(String modelName, String modelNumber) {
        if (isUseful(modelName) && isUseful(modelNumber)
                && !modelName.toLowerCase(Locale.ROOT).contains(modelNumber.toLowerCase(Locale.ROOT))) {
            return modelName + " " + modelNumber;
        }
        if (isUseful(modelName)) return modelName;
        return isUseful(modelNumber) ? modelNumber : null;
    }

    private static String cleanXmlValue(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty() || cleaned.length() > 160) return null;
        return cleaned;
    }

    private static String extractTag(String xml, String tag) {
        // Acepta prefijos de namespace simples: <u:friendlyName> además del tag plano.
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?is)<(?:[A-Za-z0-9_\\-]+:)?" + java.util.regex.Pattern.quote(tag)
                        + "(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_\\-]+:)?"
                        + java.util.regex.Pattern.quote(tag) + "\\s*>");
        java.util.regex.Matcher matcher = pattern.matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String extractHeader(String http, String headerName) {
        if (http == null || headerName == null) return null;
        for (String line : http.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(headerName)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String cleanValue(String value, int max) {
        if (value == null) return null;
        String cleaned = value.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private static boolean isUseful(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class NameCandidate {
        final String name;
        final int score;
        NameCandidate(String name, int score) { this.name = name; this.score = score; }
    }

    static final class Description {
        String friendlyName;
        String manufacturer;
        String model;
        String deviceType;
    }

    public static final class Result {
        public String ip;
        public String name;
        public String manufacturer;
        public String model;
        public String deviceType;
        public String server;
        public String usn;
        public String location;
        public final List<String> services = new ArrayList<>();
        private int nameScore;

        void offerName(String candidate, int score) {
            if (!isUseful(candidate) || score <= nameScore) return;
            name = candidate.trim();
            nameScore = score;
        }

        void addService(String service) {
            if (!isUseful(service) || services.contains(service)) return;
            services.add(service);
        }

        public String displayName() {
            if (isUseful(name)) return name;
            if (isUseful(deviceType)) return deviceType;
            return "Dispositivo UPnP";
        }

        public String detail() {
            List<String> values = new ArrayList<>();
            if (isUseful(manufacturer)) values.add("Fabricante: " + manufacturer);
            if (isUseful(model)) values.add("Modelo: " + model);
            if (isUseful(server)) values.add("SERVER: " + server);
            if (isUseful(usn)) values.add("USN: " + usn);
            return values.isEmpty() ? null : String.join(" · ", values);
        }
    }
}
