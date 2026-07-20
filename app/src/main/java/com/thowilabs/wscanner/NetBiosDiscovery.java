package com.thowilabs.wscanner;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Descubrimiento de nombres NetBIOS via NBSTAT query (UDP puerto 137).
 *
 * Envía un Node Status Request a cada IP y parsea la respuesta
 * para extraer el nombre NetBIOS principal de la máquina.
 *
 * El orquestador lo usa sobre candidatos locales todavía sin una identidad fuerte.
 * Las consultas se ejecutan en paralelo y tienen timeout corto por host.
 */
public class NetBiosDiscovery {

    private static final String TAG = "WScanner.NetBIOS";
    private static final int NB_PORT = 137;
    private static final int TIMEOUT_PER_HOST_MS = 500;

    /**
     * Consulta nombres NetBIOS para las IPs dadas.
     *
     * @param ips        IPs locales candidatas a consultar
     * @return Map IP → nombre NetBIOS
     */
    public static Map<String, String> discover(Set<String> ips) {
        Map<String, String> results = new ConcurrentHashMap<>();
        long t0 = System.currentTimeMillis();

        Log.i(TAG, "═══════════════════════════════════════");
        Log.i(TAG, "🟠 Iniciando NetBIOS probe en " + ips.size() + " IPs");

        if (ips.isEmpty()) {
            Log.d(TAG, "Sin IPs para consultar");
            return results;
        }

        int workers = Math.min(12, Math.max(1, ips.size()));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        for (String ip : ips) {
            executor.execute(() -> {
                String name = probeNetBios(ip);
                if (name != null) {
                    results.put(ip, name);
                    Log.i(TAG, "  🏷️  NetBIOS: " + ip + " → " + name);
                }
            });
        }
        executor.shutdown();
        try {
            executor.awaitTermination(Math.max(2, ips.size() / workers + 1), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        long elapsed = System.currentTimeMillis() - t0;
        Log.i(TAG, "✅ NetBIOS completado en " + elapsed + " ms — "
                + ips.size() + " consultados, " + results.size() + " respuestas");
        Log.i(TAG, "═══════════════════════════════════════");
        return new HashMap<>(results);
    }

    /**
     * Envía un NBSTAT query a una IP y retorna el nombre NetBIOS principal.
     */
    private static String probeNetBios(String ip) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setSoTimeout(TIMEOUT_PER_HOST_MS);
            socket.bind(new InetSocketAddress(0));

            // Construir paquete NBSTAT (Node Status Request)
            byte[] query = buildNbstatQuery();
            DatagramPacket packet = new DatagramPacket(query, query.length,
                    InetAddress.getByName(ip), NB_PORT);
            socket.send(packet);

            // Recibir respuesta
            byte[] buf = new byte[512];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);

            if (response.getLength() >= 57) {
                return parseNbstatResponse(buf, response.getLength());
            }

        } catch (java.net.SocketTimeoutException e) {
            // Timeout: el host no soporta NetBIOS o no responde
            Log.v(TAG, "  NetBIOS timeout en " + ip + " (esperado si no es Windows)");
        } catch (Exception e) {
            Log.v(TAG, "  Error NetBIOS en " + ip + ": " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Construye un paquete NBSTAT query (50 bytes).
     *
     * Estructura:
     *   - Transaction ID (2 bytes, aleatorio)
     *   - Flags (2 bytes, 0x0000 = request)
     *   - Questions (2 bytes, 1)
     *   - Answer RRs (2 bytes, 0)
     *   - Authority RRs (2 bytes, 0)
     *   - Additional RRs (2 bytes, 0)
     *   - Query name: "*\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0" (16 bytes, wildcard)
     *     encoded: 32 bytes de half-ASCII (cada byte → 2 bytes uppercase)
     *   - Query type (2 bytes, 0x0021 = NBSTAT)
     *   - Query class (2 bytes, 0x0001 = IN)
     */
    static byte[] buildNbstatQuery() {
        byte[] query = new byte[50];

        // Transaction ID aleatorio
        short txId = (short) new Random().nextInt(65536);
        query[0] = (byte) ((txId >> 8) & 0xFF);
        query[1] = (byte) (txId & 0xFF);

        // Flags: 0x0010 (recursion desired? Standard NetBIOS query uses 0x0010 or 0x0000)
        query[2] = 0x00;
        query[3] = 0x10; // RD (recursion desired)

        // Questions = 1
        query[4] = 0x00;
        query[5] = 0x01;

        // Answer RRs = 0
        query[6] = 0x00;
        query[7] = 0x00;

        // Authority RRs = 0
        query[8] = 0x00;
        query[9] = 0x00;

        // Additional RRs = 0
        query[10] = 0x00;
        query[11] = 0x00;

        // Query name: "*" (wildcard) encoded as NetBIOS half-ASCII
        // "*" tiene código ASCII 0x2A, pero en NetBIOS encoding cada byte
        // se divide en 2 nibbles y se suma 0x41 ('A')
        // "*" = 0x2A → 'C' (0x2 + 0x41) + 'K' (0xA + 0x41)
        String encodedName = encodeNetBiosName("*");
        byte[] nameBytes = encodedName.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        // Nombre DNS-style: longitud 0x20 + 32 bytes half-ASCII + terminador 0x00.
        // La versión anterior omitía longitud/terminador y generaba una consulta inválida.
        query[12] = 0x20;
        System.arraycopy(nameBytes, 0, query, 13, nameBytes.length);
        query[45] = 0x00;

        // Query type: NBSTAT = 0x0021
        query[46] = 0x00;
        query[47] = 0x21;

        // Query class: IN = 0x0001
        query[48] = 0x00;
        query[49] = 0x01;

        return query;
    }

    /**
     * Codifica un nombre NetBIOS en formato half-ASCII (RFC 883).
     *
     * Cada carácter se divide en 2 nibbles (4 bits cada uno).
     * Cada nibble se mapea a un byte sumando 0x41 ('A').
     * El nombre resultante tiene 32 bytes y representa el nombre
     * original + padding con espacios hasta 15 caracteres, + 1 byte de sufijo.
     *
     * Para un wildcard "*", usamos el formato "*" (1 char + 14 espacios + sufijo 0x00).
     */
    private static String encodeNetBiosName(String name) {
        // El nombre NetBIOS tiene 16 bytes (15 chars + 1 type suffix)
        // Cada byte se codifica en 2 caracteres → 32 caracteres
        StringBuilder encoded = new StringBuilder(32);

        // Padding: nombre rellenado con espacios hasta 15 chars + sufijo type
        char[] padded = new char[16];
        for (int i = 0; i < 15; i++) {
            padded[i] = (i < name.length()) ? name.charAt(i) : ' ';
        }
        padded[15] = 0x00; // suffix type para NBSTAT query = 0x00

        for (int i = 0; i < 16; i++) {
            int b = padded[i] & 0xFF;
            encoded.append((char) (((b >> 4) & 0x0F) + 'A'));
            encoded.append((char) ((b & 0x0F) + 'A'));
        }

        return encoded.toString();
    }

    /**
     * Parsea la respuesta NBSTAT y extrae el nombre principal de la máquina.
     *
     * Estructura de respuesta:
     *   - Header (12 bytes): txId, flags, questions, answers, auth, additional
     *   - Answer section:
     *     - Name (34 bytes, half-ASCII)
     *     - Type (2 bytes, 0x0021)
     *     - Class (2 bytes, 0x0001)
     *     - TTL (4 bytes)
     *     - Data length (2 bytes)
     *     - Number of names (1 byte)
     *     - Names array: cada nombre = 15 bytes padded + 1 byte type + 2 bytes flags
     *   Cada entrada de nombre: 18 bytes
     */
    static String parseNbstatResponse(byte[] data, int length) {
        try {
            if (data == null || length < 12) return null;

            int qdCount = readU16(data, 4);
            int anCount = readU16(data, 6);
            int pos = 12;

            for (int i = 0; i < qdCount; i++) {
                pos = skipEncodedName(data, pos, length);
                if (pos < 0 || pos + 4 > length) return null;
                pos += 4;
            }

            String fallback = null;
            for (int answer = 0; answer < anCount && pos < length; answer++) {
                pos = skipEncodedName(data, pos, length);
                if (pos < 0 || pos + 10 > length) return fallback;

                int type = readU16(data, pos);
                int rdLength = readU16(data, pos + 8);
                int rdata = pos + 10;
                if (rdata + rdLength > length) return fallback;

                if (type == 0x0021 && rdLength >= 1) {
                    int numNames = data[rdata] & 0xFF;
                    int namesStart = rdata + 1;
                    if (namesStart + numNames * 18 > rdata + rdLength) return fallback;

                    for (int i = 0; i < numNames; i++) {
                        int nameStart = namesStart + i * 18;
                        String rawName = new String(data, nameStart, 15,
                                java.nio.charset.StandardCharsets.US_ASCII).trim();
                        int typeSuffix = data[nameStart + 15] & 0xFF;
                        int flags = ((data[nameStart + 16] & 0xFF) << 8)
                                | (data[nameStart + 17] & 0xFF);

                        boolean isUnique = (flags & 0x8000) == 0;
                        boolean isActive = (flags & 0x0400) != 0 || flags == 0;
                        if (!isUnique || !isActive || rawName.isEmpty()
                                || rawName.equals("*") || rawName.startsWith("__")) {
                            continue;
                        }

                        // 0x00 = workstation: normalmente el nombre humano más útil.
                        if (typeSuffix == 0x00) return rawName;
                        // 0x20 = file server; conservar como fallback.
                        if (fallback == null && typeSuffix == 0x20) fallback = rawName;
                        if (fallback == null) fallback = rawName;
                    }
                }

                pos = rdata + rdLength;
            }
            return fallback;
        } catch (Exception e) {
            Log.v(TAG, "Error parseando respuesta NBSTAT: " + e.getMessage());
            return null;
        }
    }

    private static int readU16(byte[] data, int pos) {
        return ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
    }

    private static int skipEncodedName(byte[] data, int pos, int length) {
        if (pos >= length) return -1;
        int first = data[pos] & 0xFF;
        if ((first & 0xC0) == 0xC0) {
            return pos + 2 <= length ? pos + 2 : -1;
        }
        while (pos < length) {
            int labelLength = data[pos++] & 0xFF;
            if (labelLength == 0) return pos;
            if ((labelLength & 0xC0) == 0xC0) {
                return pos < length ? pos + 1 : -1;
            }
            if (pos + labelLength > length) return -1;
            pos += labelLength;
        }
        return -1;
    }
}
