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
     * @return Map IP → nombre amigable
     */
    public static Map<String, String> discover() {
        Map<String, String> bestNames = new HashMap<>();
        Map<String, Integer> bestScores = new HashMap<>();
        long t0 = System.currentTimeMillis();

        Log.i(TAG, "═══════════════════════════════════════");
        Log.i(TAG, "🟢 Iniciando descubrimiento SSDP/UPnP");

        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.bind(new InetSocketAddress(0));

            byte[] queryBytes = MSEARCH.getBytes("UTF-8");
            DatagramPacket queryPacket = new DatagramPacket(queryBytes, queryBytes.length,
                    InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
            socket.send(queryPacket);
            Log.d(TAG, "M-SEARCH enviado a " + SSDP_ADDR + ":" + SSDP_PORT);

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

                        // 3. XML friendlyName (solo si no tenemos buen nombre aún)
                        if (candidateScore < 5 && location != null && !location.isEmpty()) {
                            String friendlyName = fetchFriendlyName(location);
                            if (friendlyName != null && !friendlyName.isEmpty()) {
                                int fnScore = scoreFriendlyName(friendlyName);
                                if (fnScore > candidateScore) {
                                    candidate = friendlyName;
                                    candidateScore = fnScore;
                                }
                            }
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
     * Puntuación para nombres extraídos del USN.
     * Prioridad: marca conocida > tipo UPnP conocido > router/gateway > otros.
     */
    private static UsnResult parseUsn(String usn) {
        // 1. Marca conocida en el UUID → score alto
        String[] knownBrands = {"HISENSE", "SAMSUNG", "LG", "SONY", "PHILIPS",
                "PANASONIC", "SHARP", "TCL", "TOSHIBA", "VIZIO", "SKYWORTH"};
        for (String brand : knownBrands) {
            String upper = usn.toUpperCase();
            int bIdx = upper.indexOf(brand);
            if (bIdx >= 0) {
                String rest = usn.substring(bIdx);
                int endIdx = rest.indexOf("::");
                if (endIdx < 0) endIdx = rest.length();
                rest = rest.substring(0, endIdx);

                rest = rest.replace("SMARTTV=", "Smart TV ")
                        .replace("KANDROIDTV-", "")
                        .replace("NFANDROID2-PRV-MTK96124", "")
                        .replace("-", " ")
                        .replace("=", " ")
                        .replaceAll("\\d{5,}", "");
                rest = rest.trim().replaceAll("\\s+", " ");
                if (rest.length() > 2 && rest.length() < 60) {
                    return new UsnResult(rest, 10);
                }
            }
        }

        // 2. Tipo UPnP estándar
        int colon = usn.indexOf("::");
        if (colon > 0) {
            String deviceType = usn.substring(colon + 2);
            if (deviceType.contains("InternetGatewayDevice"))
                return new UsnResult("Router/Gateway", 8);
            if (deviceType.contains("WANDevice") || deviceType.contains("WANConnectionDevice"))
                return new UsnResult("Router (WAN)", 7);
            if (deviceType.contains("MediaRenderer"))
                return new UsnResult("Media Renderer", 6);
            if (deviceType.contains("MediaServer"))
                return new UsnResult("Media Server", 6);
            if (deviceType.contains("WLANAccessPoint"))
                return new UsnResult("Access Point", 6);
            if (deviceType.contains("Printer"))
                return new UsnResult("Impresora", 6);
            if (deviceType.contains("dial-multiscreen"))
                return new UsnResult("Cast Receiver", 8);
        }

        return null;
    }

    /**
     * Puntuación para nombres extraídos del header SERVER.
     * Chromecast > marcas conocidas > otros servicios > genérico.
     */
    private static ServerResult parseServer(String server) {
        if (server == null || server.isEmpty()) return null;

        String upper = server.toUpperCase();

        // Chromecast → muy específico, score alto
        if (upper.contains("CHROMECAST"))
            return new ServerResult("Chromecast", 8);

        // Marcas/servicios específicos
        if (upper.contains("MINIDLNA")) return new ServerResult("MiniDLNA", 7);
        if (upper.contains("PLEX")) return new ServerResult("Plex Server", 7);

        // Buscar marcas conocidas en cualquier parte del string
        for (String part : server.split("\\s+")) {
            String lower = part.toLowerCase();
            if (lower.contains("samsung") || lower.contains("lg")
                    || lower.contains("sony") || lower.contains("xbox")
                    || lower.contains("playstation") || lower.contains("roku")
                    || lower.contains("plex") || lower.contains("minidlna")
                    || lower.contains("ready") || lower.contains("synology")
                    || lower.contains("qnap") || lower.contains("buffalo")) {
                return new ServerResult(part, 7);
            }
        }

        // Buscar parte significativa antes de "UPnP"
        int upnpIdx = server.indexOf("UPnP");
        if (upnpIdx > 0) {
            String before = server.substring(0, upnpIdx).trim();
            int comma = before.indexOf(",");
            if (comma > 0) before = before.substring(0, comma).trim();
            if (before.endsWith("/")) before = before.substring(0, before.length() - 1).trim();

            // Rechazar kernels de Linux y strings genéricos
            if (!isGenericServer(before)) {
                return new ServerResult(before, 4);
            }
        }

        // Buscar token significativo (no genérico, no versión de kernel)
        String[] tokens = server.split("[\\s/,]");
        for (String token : tokens) {
            token = token.trim();
            if (token.length() >= 4 && !isGenericToken(token)) {
                return new ServerResult(token, 3);
            }
        }

        return null;
    }

    /** Rechaza strings que son versiones de kernel, genéricos o sin valor. */
    private static boolean isGenericServer(String s) {
        if (s == null || s.isEmpty()) return true;
        String lower = s.toLowerCase();

        // Versión de kernel: "4.19.116+", "3.10.0", "Linux/5.4"
        if (lower.matches(".*\\d+\\.\\d+\\.\\d+[+]?.*")) return true;
        // Genéricos
        if (lower.equals("linux") || lower.startsWith("linux/")) return true;
        if (lower.equals("posix")) return true;
        if (lower.startsWith("portable")) return true;
        if (lower.startsWith("sdk")) return true;
        if (lower.equals("upnp")) return true;
        if (lower.equals("http")) return true;
        if (lower.length() < 4) return true;
        // "Platform 1.0" genérico de DLNA
        if (lower.startsWith("platform")) return true;

        return false;
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
        String upper = name.toUpperCase();
        // friendlyName "UPNP IGD" es mejor que nada
        if (upper.contains("IGD") || upper.contains("GATEWAY") || upper.contains("ROUTER"))
            return 6;
        // Chromecast/Living Room TV/etc
        if (upper.contains("CHROMECAST") || upper.contains("TV") || upper.contains("LIVING"))
            return 7;
        return 5;
    }

    // ═══════════════════════ XML Friendly Name ═══════════════════

    private static String fetchFriendlyName(String locationUrl) {
        try {
            URL url = new URL(locationUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "WScanner/1.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder xml = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                xml.append(line);
            }
            reader.close();
            conn.disconnect();

            String xmlStr = xml.toString();

            String friendlyName = extractTag(xmlStr, "friendlyName");
            if (friendlyName != null) return friendlyName;

            String manufacturer = extractTag(xmlStr, "manufacturer");
            String modelName = extractTag(xmlStr, "modelName");

            if (manufacturer != null && modelName != null) {
                return manufacturer + " " + modelName;
            }
            if (manufacturer != null) return manufacturer;
            if (modelName != null) return modelName;

            return null;
        } catch (Exception e) {
            Log.v(TAG, "Error fetching XML from " + locationUrl + ": " + e.getMessage());
            return null;
        }
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
        String search = headerName + ":";
        int idx = http.indexOf(search);
        if (idx < 0) {
            search = headerName.toUpperCase() + ":";
            idx = http.indexOf(search);
        }
        if (idx < 0) return null;
        int start = idx + search.length();
        int end = http.indexOf("\r\n", start);
        if (end < 0) end = http.length();
        return http.substring(start, end).trim();
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
