package com.thowilabs.wscanner;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Descubrimiento de nombres NetBIOS via NBSTAT query (UDP puerto 137).
 *
 * Envía un Node Status Request a cada IP y parsea la respuesta
 * para extraer el nombre NetBIOS principal de la máquina.
 *
 * Solo se ejecuta para IPs que tienen puerto 445 (SMB) o 139 (NetBIOS) abierto,
 * ya que enviar a IPs sin servicio NetBIOS es tráfico innecesario.
 */
public class NetBiosDiscovery {

    private static final String TAG = "WScanner.NetBIOS";
    private static final int NB_PORT = 137;
    private static final int TIMEOUT_PER_HOST_MS = 500;

    /**
     * Consulta nombres NetBIOS para las IPs dadas.
     *
     * @param ips        IPs a consultar (pre-filtradas: puerto 445/139 abierto)
     * @return Map IP → nombre NetBIOS
     */
    public static Map<String, String> discover(Set<String> ips) {
        Map<String, String> results = new HashMap<>();
        long t0 = System.currentTimeMillis();

        Log.i(TAG, "═══════════════════════════════════════");
        Log.i(TAG, "🟠 Iniciando NetBIOS probe en " + ips.size() + " IPs");

        if (ips.isEmpty()) {
            Log.d(TAG, "Sin IPs para consultar (sin puertos SMB/NetBIOS)");
            return results;
        }

        int probed = 0;
        int responded = 0;

        for (String ip : ips) {
            probed++;
            String name = probeNetBios(ip);
            if (name != null) {
                results.put(ip, name);
                responded++;
                Log.i(TAG, "  🏷️  NetBIOS: " + ip + " → " + name);
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        Log.i(TAG, "✅ NetBIOS completado en " + elapsed + " ms — "
                + probed + " consultados, " + responded + " respuestas");
        Log.i(TAG, "═══════════════════════════════════════");
        return results;
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
    private static byte[] buildNbstatQuery() {
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
        System.arraycopy(nameBytes, 0, query, 12, nameBytes.length);

        // Posición después del nombre: 12 + 34 = 46 (el nombre ocupa 34 bytes en total)
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
    private static String parseNbstatResponse(byte[] data, int length) {
        try {
            // Saltar header DNS (12 bytes) + name (34 bytes) + tipo/clase/ttl/datalen
            // Header = 12
            // Answer name = 34 bytes (wildcard response)
            // Answer type = 2 bytes (0x0021)
            // Answer class = 2 bytes (0x0001)
            // TTL = 4 bytes
            // Data length = 2 bytes
            // Total header = 12 + 34 + 2 + 2 + 4 + 2 = 56 bytes
            int pos = 56;

            if (pos >= length) return null;

            // Number of names (1 byte)
            int numNames = data[pos] & 0xFF;
            pos++;

            if (pos + (numNames * 18) > length) return null;

            // Buscar el primer nombre unique + active (flags == 0x0400 o 0x0000 como UNIQUE)
            // Estructura de cada entrada (18 bytes):
            //   - Name (15 bytes ASCII, padded with spaces)
            //   - Type suffix (1 byte)
            //   - Flags (2 bytes): bit 15 = group (1) o unique (0), bit 7 = active
            //     Flags little-endian: byte0 = flags lo, byte1 = flags hi
            String candidateName = null;

            for (int i = 0; i < numNames; i++) {
                int nameStart = pos + (i * 18);

                // Extraer nombre (15 bytes)
                byte[] nameBytes = new byte[15];
                System.arraycopy(data, nameStart, nameBytes, 0, 15);
                String rawName = new String(nameBytes, "US-ASCII").trim();

                // Type suffix (byte 15)
                int typeSuffix = data[nameStart + 15] & 0xFF;

                // Flags (bytes 16-17, little-endian)
                int flagsLo = data[nameStart + 16] & 0xFF;
                int flagsHi = data[nameStart + 17] & 0xFF;
                int flags = (flagsHi << 8) | flagsLo;

                boolean isUnique = (flags & 0x8000) == 0; // bit 15 = 0 → unique name
                boolean isActive = (flags & 0x0080) != 0;  // bit 7 = active

                Log.v(TAG, "    NB name: \"" + rawName + "\" type=" + typeSuffix
                        + " flags=0x" + Integer.toHexString(flags)
                        + " unique=" + isUnique + " active=" + isActive);

                // Ignorar nombres de servicio (type >= 0x20)
                // Tipos comunes: 0x00 = workstation, 0x03 = messenger, 0x20 = server
                if (isUnique && isActive && !rawName.isEmpty()) {
                    // Preferir workstation name (type 0x00) sobre messenger (0x03)
                    if (typeSuffix == 0x00) {
                        Log.v(TAG, "    ✓ Nombre workstation: " + rawName);
                        return rawName;
                    }
                    if (candidateName == null && !rawName.startsWith("__")
                            && !rawName.equals("*")) {
                        candidateName = rawName;
                    }
                }
            }

            return candidateName;

        } catch (Exception e) {
            Log.v(TAG, "Error parseando respuesta NBSTAT: " + e.getMessage());
            return null;
        }
    }
}
