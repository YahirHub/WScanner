package com.thowilabs.wscanner;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Descubrimiento UPnP/SSDP mediante M-SEARCH multicast.
 *
 * Envía M-SEARCH a 239.255.255.250:1900 con ST: ssdp:all,
 * escucha respuestas HTTP/1.1 200 OK durante 4 segundos,
 * y extrae nombres de dispositivo desde headers SERVER y LOCATION.
 *
 * Usa sistema de scoring para elegir el mejor nombre cuando
 * una IP responde con múltiples identidades (ej: TV + Chromecast).
 */
public class SsdpDiscovery {

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

    /**
     * Descubre dispositivos UPnP en la red local.
     *
     * @param localAddr dirección IPv4 de la interfaz local preferida
     * @return Map IP → nombre amigable
     */
    public static Map<String, String> discover(InetAddress localAddr) {
        Map<String, String> bestNames = new HashMap<>();
        Map<String, Integer> bestScores = new HashMap<>();
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

            byte[] queryBytes = MSEARCH.getBytes("UTF-8");
            DatagramPacket queryPacket = new DatagramPacket(queryBytes, queryBytes.length,
                    InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
            socket.send(queryPacket);
            try { Thread.sleep(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            socket.send(queryPacket);
            Log.d(TAG, "M-SEARCH enviado dos veces a " + SSDP_ADDR + ":" + SSDP_PORT);

            int responseCount = 0;
            byte[] buf = new byte[2048];
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;

            while (System.currentTimeMillis() < deadline) {
                try {
                    int remaining = (int) (deadline - System.currentTimeMillis());
                    if (remaining <= 0) break;
                    socket.setSoTimeout(Math.max(remaining, 100));

                    DatagramPacket responsePacket = new DatagramPacket(buf, buf.length);
                    socket.receive(responsePacket);

                    String response = new String(responsePacket.getData(), 0,
                            responsePacket.getLength(), "UTF-8");
                    String remoteIp = responsePacket.getAddress().getHostAddress();

                    if (response.startsWith("HTTP/1.1 200 OK")) {
                        responseCount++;

                        String server = extractHeader(response, "SERVER");
                        String location = extractHeader(response, "LOCATION");
                        String usn = extractHeader(response, "USN");

                        // Evaluar todas las fuentes y elegir la de mayor puntuación
                        String candidate = null;
                        int candidateScore = 0;

                        // 1. USN: contiene la info más rica (marca, modelo)
                        if (usn != null && !usn.isEmpty()) {
                            UsnResult r = parseUsn(usn);
                            if (r != null && r.score > candidateScore) {
                                candidate = r.name;
                                candidateScore = r.score;
                            }
                        }

                        // 2. SERVER header
                        if (server != null && !server.isEmpty()) {
                            ServerResult r = parseServer(server);
                            if (r != null && r.score > candidateScore) {
                                candidate = r.name;
                                candidateScore = r.score;
                            }
                        }

                        // 3. Guardar LOCATION para resolver friendlyName después de
                        // terminar la ventana UDP. Hacer HTTP dentro del receive loop
                        // bloqueaba respuestas SSDP de otros dispositivos.
                        if (location != null && !location.isEmpty()) {
                            locations.put(remoteIp, location);
                        }

                        if (candidate != null && candidateScore > 0) {
                            int currentBest = bestScores.getOrDefault(remoteIp, 0);
                            if (candidateScore > currentBest) {
                                bestNames.put(remoteIp, candidate);
                                bestScores.put(remoteIp, candidateScore);
                                Log.i(TAG, "  🏷️  " + remoteIp + " → \"" + candidate + "\" (score=" + candidateScore + ")");
                            }
                        }
                    }
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
            }

            Log.d(TAG, "SSDP: " + responseCount + " respuestas recibidas");

            // Resolver descripciones UPnP en paralelo una vez terminado el receive
            // loop. Así ningún GET lento impide recibir respuestas multicast restantes.
            if (!locations.isEmpty()) {
                int workers = Math.min(8, locations.size());
                ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, workers));
                for (Map.Entry<String, String> entry : locations.entrySet()) {
                    String ip = entry.getKey();
                    String location = entry.getValue();
                    executor.execute(() -> {
                        String friendlyName = fetchFriendlyName(location, ip);
                        if (friendlyName == null || friendlyName.isEmpty()) return;
                        int score = scoreFriendlyName(friendlyName);
                        synchronized (bestNames) {
                            int currentBest = bestScores.getOrDefault(ip, 0);
                            if (score > currentBest) {
                                bestNames.put(ip, friendlyName);
                                bestScores.put(ip, score);
                                Log.i(TAG, "  🏷️  " + ip + " → \"" + friendlyName
                                        + "\" (XML score=" + score + ")");
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

        long elapsed = System.currentTimeMillis() - t0;
        Log.i(TAG, "✅ SSDP completado en " + elapsed + " ms — " + bestNames.size() + " dispositivos");
        Log.i(TAG, "═══════════════════════════════════════");
        return bestNames;
    }

    // ═══════════════════════ Scoring system ════════════════════════

    /**
     * Puntuación para tipos UPnP extraídos del USN sin depender de fabricantes.
     */
    private static UsnResult parseUsn(String usn) {
        if (usn == null || usn.isEmpty()) return null;

        int colon = usn.indexOf("::");
        String value = colon >= 0 ? usn.substring(colon + 2) : usn;
        String lower = value.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("internetgatewaydevice"))
            return new UsnResult("Router / Puerta de enlace", 8);
        if (lower.contains("wlanaccesspoint"))
            return new UsnResult("Punto de acceso", 7);
        if (lower.contains("mediarenderer"))
            return new UsnResult("Reproductor multimedia", 6);
        if (lower.contains("mediaserver"))
            return new UsnResult("Servidor multimedia", 6);
        if (lower.contains("printer"))
            return new UsnResult("Impresora", 6);
        if (lower.contains("dial-multiscreen"))
            return new UsnResult("Receptor multimedia", 7);

        // USN suele ser UUID + URN. No usar el UUID como nombre: no es identidad humana.
        return null;
    }

    /**
     * Extrae un token de producto útil del header SERVER sin tablas de marcas.
     */
    private static ServerResult parseServer(String server) {
        if (server == null || server.isEmpty()) return null;

        String normalized = server.trim().replaceAll("\\s+", " ");
        String[] tokens = normalized.split("[\\s/,;()]+");
        for (String token : tokens) {
            token = token.trim();
            if (token.length() >= 4 && token.length() <= 48 && !isGenericToken(token)
                    && !token.matches(".*\\d+\\.\\d+\\.\\d+.*")) {
                return new ServerResult(token, 3);
            }
        }
        return null;
    }

    private static boolean isGenericToken(String token) {
        String upper = token.toUpperCase();
        return upper.equals("UPNP") || upper.equals("HTTP") || upper.equals("LINUX")
                || upper.equals("POSIX") || upper.equals("PORTABLE") || upper.equals("SDK")
                || upper.equals("FOR") || upper.equals("DEVICES") || upper.equals("DLNADOC")
                || upper.startsWith("1.") || upper.startsWith("2.") || upper.startsWith("3.")
                || upper.startsWith("4.") || upper.startsWith("5.")
                || upper.startsWith("PLATFORM") || upper.startsWith("HIS/");
    }

    private static int scoreFriendlyName(String name) {
        if (name == null) return 0;
        String cleaned = name.trim();
        if (cleaned.length() < 2 || cleaned.matches("(?i)device|unknown|upnp")) return 1;
        String upper = cleaned.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("IGD") || upper.contains("GATEWAY") || upper.contains("ROUTER")) return 6;
        return 7;
    }

    // ═══════════════════════ XML Friendly Name ═══════════════════

    private static String fetchFriendlyName(String locationUrl, String responderIp) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(locationUrl);
            String scheme = url.getProtocol();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            String host = url.getHost();
            if (host == null || responderIp == null || !host.equalsIgnoreCase(responderIp)) {
                Log.v(TAG, "LOCATION ignorado fuera del host respondedor: " + locationUrl);
                return null;
            }

            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1200);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "WScanner/1.0");
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            if (code != 200) return null;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder xml = new StringBuilder();
            char[] buf = new char[2048];
            int read;
            while ((read = reader.read(buf)) != -1 && xml.length() < 65536) {
                int remaining = 65536 - xml.length();
                xml.append(buf, 0, Math.min(read, remaining));
            }
            reader.close();

            String xmlStr = xml.toString();
            String friendlyName = cleanXmlValue(extractTag(xmlStr, "friendlyName"));
            String manufacturer = cleanXmlValue(extractTag(xmlStr, "manufacturer"));
            String modelName = cleanXmlValue(extractTag(xmlStr, "modelName"));
            String modelNumber = cleanXmlValue(extractTag(xmlStr, "modelNumber"));

            if (friendlyName != null && scoreFriendlyName(friendlyName) >= 5) return friendlyName;
            if (manufacturer != null && modelName != null && !modelName.equalsIgnoreCase(manufacturer))
                return manufacturer + " " + modelName;
            if (modelName != null && modelNumber != null && !modelName.contains(modelNumber))
                return modelName + " " + modelNumber;
            if (modelName != null) return modelName;
            if (manufacturer != null) return manufacturer;
            return friendlyName;
        } catch (Exception e) {
            Log.v(TAG, "Error leyendo XML UPnP local: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
        }
    }

    private static String cleanXmlValue(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty() || cleaned.length() > 96) return null;
        return cleaned;
    }

    private static String extractTag(String xml, String tag) {
        String openTag = "<" + tag + ">";
        String closeTag = "</" + tag + ">";
        int start = xml.indexOf(openTag);
        if (start < 0) {
            openTag = "<" + tag + " ";
            start = xml.indexOf(openTag);
            if (start >= 0) {
                int gt = xml.indexOf(">", start);
                if (gt < 0) return null;
                start = gt + 1;
                int end = xml.indexOf(closeTag, start);
                if (end < 0) return null;
                return xml.substring(start, end).trim();
            }
            return null;
        }
        start += openTag.length();
        int end = xml.indexOf(closeTag, start);
        if (end < 0) return null;
        return xml.substring(start, end).trim();
    }

    // ═══════════════════════ HTTP Header Parser ═══════════════════

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

    // ═══════════════════════ Result holders ═══════════════════════

    private static class UsnResult {
        final String name;
        final int score;
        UsnResult(String name, int score) { this.name = name; this.score = score; }
    }

    private static class ServerResult {
        final String name;
        final int score;
        ServerResult(String name, int score) { this.name = name; this.score = score; }
    }
}
