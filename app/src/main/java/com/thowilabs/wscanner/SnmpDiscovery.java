package com.thowilabs.wscanner;

import android.net.Network;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Descubrimiento SNMP v2c de mejor esfuerzo usando la comunidad convencional
 * de solo lectura "public". Consulta únicamente sysName.0 y sysDescr.0.
 *
 * No es requisito para detectar un dispositivo y no se usa como diccionario:
 * solo aprovecha metadatos que el propio equipo decide exponer localmente.
 */
public final class SnmpDiscovery {

    private static final String TAG = "WScanner.SNMP";
    private static final int PORT = 161;
    private static final int TIMEOUT_MS = 280;
    private static final byte[] OID_SYS_DESCR = {0x2b, 0x06, 0x01, 0x02, 0x01, 0x01, 0x01, 0x00};
    private static final byte[] OID_SYS_NAME  = {0x2b, 0x06, 0x01, 0x02, 0x01, 0x01, 0x05, 0x00};

    private SnmpDiscovery() {}

    public static Map<String, Result> discover(List<String> hosts) {
        return discover(hosts, null);
    }

    public static Map<String, Result> discover(List<String> hosts, Network network) {
        Map<String, Result> results = new ConcurrentHashMap<>();
        if (hosts == null || hosts.isEmpty()) return new HashMap<>();

        int workers = Math.min(32, Math.max(1, hosts.size()));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        AtomicInteger requestId = new AtomicInteger((int) (System.nanoTime() & 0x7FFFFFFF));

        for (String ip : hosts) {
            executor.execute(() -> {
                Result result = probe(ip, requestId.incrementAndGet(), network);
                if (result != null) results.put(ip, result);
            });
        }
        executor.shutdown();
        try {
            executor.awaitTermination(Math.max(3, hosts.size() / workers + 2L), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }
        Log.i(TAG, "SNMP: " + results.size() + " respuestas de " + hosts.size() + " hosts");
        return new HashMap<>(results);
    }

    private static Result probe(String ip, int requestId, Network network) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.bind(new InetSocketAddress(0));
            if (network != null) network.bindSocket(socket);
            socket.setSoTimeout(TIMEOUT_MS);
            byte[] query = buildGetRequest(requestId);
            DatagramPacket packet = new DatagramPacket(query, query.length,
                    InetAddress.getByName(ip), PORT);
            socket.send(packet);

            byte[] buffer = new byte[2048];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            return parseResponse(buffer, response.getLength());
        } catch (Exception ignored) {
            return null;
        }
    }

    static byte[] buildGetRequest(int requestId) {
        byte[] version = tlv(0x02, new byte[]{0x01}); // SNMP v2c
        byte[] community = tlv(0x04, "public".getBytes(StandardCharsets.US_ASCII));
        byte[] requestIdTlv = tlv(0x02, integerBytes(requestId));
        byte[] errorStatus = tlv(0x02, new byte[]{0x00});
        byte[] errorIndex = tlv(0x02, new byte[]{0x00});

        byte[] varDescr = sequence(concat(tlv(0x06, OID_SYS_DESCR), tlv(0x05, new byte[0])));
        byte[] varName = sequence(concat(tlv(0x06, OID_SYS_NAME), tlv(0x05, new byte[0])));
        byte[] varBinds = sequence(concat(varDescr, varName));
        byte[] pdu = tlv(0xA0, concat(requestIdTlv, errorStatus, errorIndex, varBinds));
        return sequence(concat(version, community, pdu));
    }

    static Result parseResponse(byte[] data, int length) {
        if (data == null || length <= 0) return null;
        String descr = findOidValue(data, length, OID_SYS_DESCR);
        String name = findOidValue(data, length, OID_SYS_NAME);
        if (!isUseful(name) && !isUseful(descr)) return null;

        Result result = new Result();
        result.sysName = clean(name);
        result.sysDescr = clean(descr);
        result.deviceType = inferType(result.sysDescr);
        return result;
    }

    private static String findOidValue(byte[] data, int length, byte[] oid) {
        for (int i = 0; i + oid.length + 2 < length; i++) {
            if ((data[i] & 0xFF) != 0x06) continue;
            int[] oidLen = readLength(data, i + 1, length);
            if (oidLen == null || oidLen[0] != oid.length) continue;
            int valueStart = oidLen[1];
            if (valueStart + oid.length > length) continue;
            boolean match = true;
            for (int j = 0; j < oid.length; j++) {
                if (data[valueStart + j] != oid[j]) { match = false; break; }
            }
            if (!match) continue;

            int pos = valueStart + oid.length;
            if (pos >= length) return null;
            int tag = data[pos] & 0xFF;
            int[] valueLen = readLength(data, pos + 1, length);
            if (valueLen == null) return null;
            int len = valueLen[0];
            int start = valueLen[1];
            if (start + len > length) return null;
            if (tag == 0x04 || tag == 0x16 || tag == 0x13) {
                return new String(data, start, len, StandardCharsets.UTF_8);
            }
            return null;
        }
        return null;
    }

    private static int[] readLength(byte[] data, int pos, int limit) {
        if (pos >= limit) return null;
        int first = data[pos] & 0xFF;
        if ((first & 0x80) == 0) return new int[]{first, pos + 1};
        int count = first & 0x7F;
        if (count <= 0 || count > 4 || pos + count >= limit) return null;
        int value = 0;
        for (int i = 0; i < count; i++) value = (value << 8) | (data[pos + 1 + i] & 0xFF);
        return new int[]{value, pos + 1 + count};
    }

    private static String inferType(String description) {
        if (description == null) return null;
        String value = description.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("printer") || value.contains("print server")) return "Impresora";
        if (value.contains("router") || value.contains("gateway")) return "Infraestructura de red";
        if (value.contains("switch")) return "Switch de red";
        if (value.contains("access point") || value.contains("wireless ap")) return "Punto de acceso";
        if (value.contains("camera") || value.contains("video")) return "Cámara / dispositivo de video";
        if (value.contains("nas") || value.contains("storage")) return "Servidor / NAS";
        return null;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > 240 ? cleaned.substring(0, 240) : cleaned;
    }

    private static boolean isUseful(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static byte[] integerBytes(int value) {
        byte[] raw = new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value
        };
        int start = 0;
        while (start < raw.length - 1 && raw[start] == 0 && (raw[start + 1] & 0x80) == 0) start++;
        byte[] out = new byte[raw.length - start];
        System.arraycopy(raw, start, out, 0, out.length);
        if ((out[0] & 0x80) != 0) {
            byte[] positive = new byte[out.length + 1];
            System.arraycopy(out, 0, positive, 1, out.length);
            return positive;
        }
        return out;
    }

    private static byte[] sequence(byte[] value) {
        return tlv(0x30, value);
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, value.length);
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
        } else if (length <= 255) {
            out.write(0x81);
            out.write(length);
        } else {
            out.write(0x82);
            out.write((length >>> 8) & 0xFF);
            out.write(length & 0xFF);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }

    public static final class Result {
        public String sysName;
        public String sysDescr;
        public String deviceType;

        public String displayName() {
            if (isUseful(sysName)) return sysName;
            if (isUseful(deviceType)) return deviceType;
            return "Dispositivo SNMP";
        }

        public String detail() {
            return sysDescr;
        }
    }
}
