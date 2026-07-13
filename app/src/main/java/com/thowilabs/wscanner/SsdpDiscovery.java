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
 * Paso opcional: GET al XML de LOCATION y extrae <friendlyName>.
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
     * @return Map IP → nombre amigable (friendlyName o SERVER header)
     */
    public static Map<String, String> discover() {
        Map<String, String> results = new HashMap<>();
        long t0 = System.currentTimeMillis();

        Log.i(TAG, "═══════════════════════════════════════");
        Log.i(TAG, "🟢 Iniciando descubrimiento SSDP/UPnP");

        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.bind(new InetSocketAddress(0));

            // Enviar M-SEARCH
            byte[] queryBytes = MSEARCH.getBytes("UTF-8");
            DatagramPacket queryPacket = new DatagramPacket(queryBytes, queryBytes.length,
                    InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
            socket.send(queryPacket);
            Log.d(TAG, "M-SEARCH enviado a " + SSDP_ADDR + ":" + SSDP_PORT);

            // Recibir respuestas
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
                        Log.v(TAG, "Respuesta SSDP de " + remoteIp);

                        // Extraer headers
                        String server = extractHeader(response, "SERVER");
                        String location = extractHeader(response, "LOCATION");
                        String usn = extractHeader(response, "USN");

                        Log.v(TAG, "  SERVER: " + server);
                        Log.v(TAG, "  LOCATION: " + location);
                        Log.v(TAG, "  USN: " + usn);

                        // Construir el nombre — prioridad: USN > SERVER > XML friendlyName
                        String deviceName = null;

                        // 1. USN: contiene la info más rica (marca, modelo)
                        if (usn != null && !usn.isEmpty()) {
                            deviceName = cleanUsn(usn);
                            if (deviceName != null) Log.i(TAG, "  🏷️  USN → " + deviceName);
                        }

                        // 2. SERVER header
                        if (deviceName == null && server != null && !server.isEmpty()) {
                            deviceName = cleanServerName(server);
                            if (deviceName != null) Log.i(TAG, "  🏷️  SERVER → " + deviceName);
                        }

                        // 3. XML friendlyName (último recurso: hace HTTP request al XML)
                        if (deviceName == null && location != null && !location.isEmpty()) {
                            String friendlyName = fetchFriendlyName(location, remoteIp);
                            if (friendlyName != null && !friendlyName.isEmpty()) {
                                deviceName = friendlyName;
                                Log.i(TAG, "  🏷️  friendlyName → " + deviceName);
                            }
                        }

                        // 3. XML friendlyName
                        if (deviceName == null && location != null && !location.isEmpty()) {
                            String friendlyName = fetchFriendlyName(location, remoteIp);
                            if (friendlyName != null && !friendlyName.isEmpty()) {
                                deviceName = friendlyName;
                                Log.i(TAG, "  🏷️  friendlyName → " + deviceName);
                            }
                        }

                        if (deviceName != null && !deviceName.isEmpty()) {
                            results.put(remoteIp, deviceName);
                        }
                    }

                } catch (java.net.SocketTimeoutException e) {
                    Log.v(TAG, "Timeout SSDP (esperado)");
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
        Log.i(TAG, "✅ SSDP completado en " + elapsed + " ms — " + results.size() + " dispositivos");
        Log.i(TAG, "═══════════════════════════════════════");
        return results;
    }

    // ═══════════════════════ XML Friendly Name ═══════════════════

    /**
     * Hace GET al XML de LOCATION y extrae <friendlyName>.
     * Timeout corto: 1500 ms.
     */
    private static String fetchFriendlyName(String locationUrl, String fallbackIp) {
        try {
            // Si la URL usa un hostname, intentar resolverlo
            URL url = new URL(locationUrl);
            String host = url.getHost();

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

            // Extraer <friendlyName>...</friendlyName>
            String friendlyName = extractTag(xmlStr, "friendlyName");
            if (friendlyName != null) return friendlyName;

            // Extraer <manufacturer>...</manufacturer>
            String manufacturer = extractTag(xmlStr, "manufacturer");
            // Extraer <modelName>...</modelName>
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
        // Buscar <tagName>contenido</tagName>
        String openTag = "<" + tag + ">";
        String closeTag = "</" + tag + ">";
        int start = xml.indexOf(openTag);
        if (start < 0) {
            openTag = "<" + tag + " ";
            start = xml.indexOf(openTag);
            if (start >= 0) {
                // Tag con atributos: <tag attr="val">
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

    // ═══════════════════════ Header & Name Helpers ═══════════════

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

    /**
     * Extrae nombre significativo del USN.
     * Ej: "uuid:NFANDROID2-PRV-MTK96124KANDROIDTV-HISENSMARTTV=4K-...::upnp:rootdevice" 
     *     → extrae "HISENSMARTTV=4K" → "Hisense Smart TV 4K"
     * Ej: "uuid:20809696-...::urn:schemas-upnp-org:device:InternetGatewayDevice:1"
     *     → "Router/Gateway"
     */
    private static String cleanUsn(String usn) {
        if (usn == null || usn.isEmpty()) return null;

        // 1. Buscar patrones de fabricante+modelo en el UUID (formato NFANDROID2-...)
        //    Ej: KANDROIDTV-HISENSMARTTV=4K
        String[] knownBrands = {"HISENSE", "SAMSUNG", "LG", "SONY", "PHILIPS",
                "PANASONIC", "SHARP", "TCL", "TOSHIBA", "VIZIO", "SKYWORTH"};
        for (String brand : knownBrands) {
            String upper = usn.toUpperCase();
            int brandIdx = upper.indexOf(brand);
            if (brandIdx >= 0) {
                // Extraer desde la marca hasta el próximo '=' o ':' o fin del UUID
                String rest = usn.substring(brandIdx);
                // Limpiar: tomar hasta el "::" o hasta el final del UUID
                int endIdx = rest.indexOf("::");
                if (endIdx < 0) endIdx = rest.length();
                rest = rest.substring(0, endIdx);

                // Expandir: "SMARTTV=4K" → "Smart TV 4K"
                rest = rest.replace("SMARTTV=", "Smart TV ")
                        .replace("KANDROIDTV-", "")
                        .replace("NFANDROID2-PRV-MTK96124", "")
                        .replace("-", " ")
                        .replace("=", " ")
                        .replaceAll("\\d{5,}", ""); // eliminar seriales largos
                rest = rest.trim().replaceAll("\\s+", " ");
                if (rest.length() > 2 && rest.length() < 60) {
                    return rest;
                }
            }
        }

        // 2. Buscar tipo de dispositivo UPnP estándar
        int colon = usn.indexOf("::");
        if (colon > 0) {
            String deviceType = usn.substring(colon + 2);
            if (deviceType.contains("InternetGatewayDevice")) return "Router/Gateway";
            if (deviceType.contains("WANDevice") || deviceType.contains("WANConnectionDevice"))
                return "Router (WAN)";
            if (deviceType.contains("MediaRenderer")) return "Media Renderer";
            if (deviceType.contains("MediaServer")) return "Media Server";
            if (deviceType.contains("WLANAccessPoint")) return "Access Point";
            if (deviceType.contains("Printer")) return "Impresora";
            if (deviceType.contains("Scanner")) return "Escáner";
            if (deviceType.contains("Camera")) return "Cámara IP";
            if (deviceType.contains("DimmableLight")) return "Luz inteligente";
            // Chromecast
            if (deviceType.contains("dial-multiscreen")) return "Cast Receiver";
        }

        return null;
    }

    /**
     * Limpia el header SERVER para extraer un nombre utilizable.
     */
    private static String cleanServerName(String server) {
        if (server == null || server.isEmpty()) return null;

        // Patrones muy reconocibles
        if (server.toUpperCase().contains("CHROMECAST")) return "Chromecast";
        if (server.toUpperCase().contains("MINIDLNA")) return "MiniDLNA";
        if (server.toUpperCase().contains("PLEX")) return "Plex Server";

        // Buscar marcas conocidas
        for (String part : server.split("\\s+")) {
            String lower = part.toLowerCase();
            if (lower.contains("samsung") || lower.contains("lg")
                    || lower.contains("sony") || lower.contains("xbox")
                    || lower.contains("playstation") || lower.contains("roku")
                    || lower.contains("plex") || lower.contains("minidlna")
                    || lower.contains("ready") || lower.contains("synology")
                    || lower.contains("qnap") || lower.contains("buffalo")) {
                return part;
            }
        }

        // Si tiene "UPnP", devolver la parte antes (hasta la coma)
        int upnpIdx = server.indexOf("UPnP");
        if (upnpIdx > 0) {
            String before = server.substring(0, upnpIdx).trim();
            // Cortar en la coma si hay
            int comma = before.indexOf(",");
            if (comma > 0) before = before.substring(0, comma).trim();
            if (before.endsWith("/")) before = before.substring(0, before.length() - 1).trim();
            // Si empieza con "Linux" o similar genérico, no es útil
            if (!before.isEmpty() && !before.equalsIgnoreCase("Linux")
                    && !before.startsWith("Linux/") && !before.startsWith("POSIX")) {
                return before;
            }
        }

        // Último recurso: tomar la primera palabra significativa
        String[] tokens = server.split("[\\s/,]");
        for (String token : tokens) {
            token = token.trim();
            if (token.length() >= 3 && !token.equals("UPnP") && !token.equals("HTTP")
                    && !token.startsWith("1.") && !token.startsWith("2.")
                    && !token.equalsIgnoreCase("Linux") && !token.equalsIgnoreCase("POSIX")
                    && !token.equalsIgnoreCase("Portable") && !token.equalsIgnoreCase("SDK")
                    && !token.equalsIgnoreCase("for") && !token.equalsIgnoreCase("devices")) {
                return token;
            }
        }

        return null;
    }
}
