package com.thowilabs.wscanner;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descubrimiento WS-Discovery (UDP 3702), usado por ONVIF, Windows WSD,
 * impresoras, escáneres y otros equipos de red.
 *
 * El protocolo es completamente local y no depende de bases de fabricantes.
 */
public final class WsDiscovery {

    private static final String TAG = "WScanner.WSD";
    private static final String MULTICAST_ADDR = "239.255.255.250";
    private static final int PORT = 3702;
    private static final int TIMEOUT_MS = 3500;

    private WsDiscovery() {}

    public static Map<String, Result> discover(InetAddress localAddr) {
        Map<String, Result> results = new HashMap<>();
        DatagramSocket socket = null;
        long started = System.currentTimeMillis();

        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(localAddr != null
                    ? new InetSocketAddress(localAddr, 0)
                    : new InetSocketAddress(0));

            byte[] probe = buildProbe().getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(probe, probe.length,
                    InetAddress.getByName(MULTICAST_ADDR), PORT);
            socket.send(packet);
            try { Thread.sleep(120); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            socket.send(packet);

            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            byte[] buffer = new byte[8192];
            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) (deadline - System.currentTimeMillis());
                if (remaining <= 0) break;
                try {
                    socket.setSoTimeout(Math.max(100, remaining));
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);
                    String ip = response.getAddress().getHostAddress();
                    String xml = new String(response.getData(), 0, response.getLength(),
                            StandardCharsets.UTF_8);
                    Result parsed = parseResponse(xml, ip);
                    if (parsed != null) {
                        Result existing = results.get(ip);
                        results.put(ip, existing == null ? parsed : existing.merge(parsed));
                    }
                } catch (java.net.SocketTimeoutException timeout) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error en WS-Discovery: " + e.getMessage());
        } finally {
            if (socket != null) socket.close();
        }

        Log.i(TAG, "WS-Discovery completado en "
                + (System.currentTimeMillis() - started) + " ms — " + results.size() + " dispositivos");
        return results;
    }

    static String buildProbe() {
        String messageId = "uuid:" + UUID.randomUUID();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" "
                + "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">"
                + "<s:Header>"
                + "<a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>"
                + "<a:MessageID>" + messageId + "</a:MessageID>"
                + "<a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>"
                + "</s:Header><s:Body><d:Probe/></s:Body></s:Envelope>";
    }

    static Result parseResponse(String xml, String sourceIp) {
        if (xml == null || xml.isEmpty() || sourceIp == null) return null;
        String types = extractTag(xml, "Types");
        String scopes = extractTag(xml, "Scopes");
        String xaddrs = extractTag(xml, "XAddrs");
        if (types == null && scopes == null && xaddrs == null
                && !xml.toLowerCase(Locale.ROOT).contains("probematches")) {
            return null;
        }

        Result result = new Result();
        result.ip = sourceIp;
        result.types = clean(types);
        result.scopes = clean(scopes);
        result.xAddrs = clean(xaddrs);

        if (result.types != null) {
            for (String token : result.types.split("\\s+")) {
                if (!token.isEmpty()) result.services.add(token);
            }
        }

        if (result.scopes != null) {
            for (String rawScope : result.scopes.split("\\s+")) {
                String scope = decode(rawScope);
                String lower = scope.toLowerCase(Locale.ROOT);
                String name = tailAfter(scope, lower, "/name/");
                if (isUseful(name) && result.name == null) result.name = name;

                String hardware = tailAfter(scope, lower, "/hardware/");
                if (isUseful(hardware) && result.model == null) result.model = hardware;

                if (lower.contains("onvif.org")) {
                    result.services.add("ONVIF");
                }
            }
        }

        String combined = ((result.types == null ? "" : result.types) + " "
                + (result.scopes == null ? "" : result.scopes)).toLowerCase(Locale.ROOT);
        if (combined.contains("networkvideotransmitter") || combined.contains("onvif")) {
            result.deviceType = "Cámara / dispositivo de video";
        } else if (combined.contains("printbasic") || combined.contains("printer")) {
            result.deviceType = "Impresora";
        } else if (combined.contains("scan") || combined.contains("scanner")) {
            result.deviceType = "Escáner";
        } else if (combined.contains("computer")) {
            result.deviceType = "PC / estación de trabajo";
        } else {
            result.deviceType = "Dispositivo WS-Discovery";
        }

        if (!isUseful(result.name)) result.name = result.deviceType;
        return result;
    }

    private static String extractTag(String xml, String localName) {
        Pattern pattern = Pattern.compile("<(?:[A-Za-z0-9_.-]+:)?" + Pattern.quote(localName)
                + "(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_.-]+:)?" + Pattern.quote(localName) + ">",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String tailAfter(String original, String lower, String marker) {
        int index = lower.indexOf(marker);
        if (index < 0) return null;
        String tail = original.substring(index + marker.length());
        int slash = tail.indexOf('/');
        if (slash >= 0) tail = tail.substring(0, slash);
        return clean(tail.replace('+', ' '));
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > 512 ? cleaned.substring(0, 512) : cleaned;
    }

    private static boolean isUseful(String value) {
        if (value == null) return false;
        String v = value.trim();
        return v.length() >= 2 && !v.equalsIgnoreCase("unknown") && !v.equalsIgnoreCase("device");
    }

    public static final class Result {
        public String ip;
        public String name;
        public String deviceType;
        public String model;
        public String types;
        public String scopes;
        public String xAddrs;
        public final List<String> services = new ArrayList<>();

        Result merge(Result other) {
            if (other == null) return this;
            if (!isUseful(name) && isUseful(other.name)) name = other.name;
            if (!isUseful(model) && isUseful(other.model)) model = other.model;
            if (!isUseful(deviceType) && isUseful(other.deviceType)) deviceType = other.deviceType;
            if (types == null) types = other.types;
            if (scopes == null) scopes = other.scopes;
            if (xAddrs == null) xAddrs = other.xAddrs;
            for (String service : other.services) {
                if (!services.contains(service)) services.add(service);
            }
            return this;
        }

        public String detail() {
            StringBuilder detail = new StringBuilder();
            if (model != null) detail.append("Modelo: ").append(model);
            if (types != null) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append("Tipos: ").append(types);
            }
            if (xAddrs != null) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append("XAddr: ").append(xAddrs);
            }
            return detail.length() == 0 ? null : detail.toString();
        }
    }
}
