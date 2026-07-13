package com.thowilabs.wscanner;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Descubrimiento de dispositivos mediante mDNS (Multicast DNS / Bonjour).
 *
 * Usa MulticastSocket crudo (RFC 6762) en vez de NsdManager, porque
 * NsdManager en API 34+ (targetSdk 36) requiere ACCESS_LOCAL_NETWORK.
 *
 * Dos estrategias:
 *   1. Service discovery (PTR _services._dns-sd._udp.local) → SRV → A
 *   2. Reverse lookup: para cada IP, query PTR a X.X.X.X.in-addr.arpa
 *      (exactamente como Fing, según análisis con tcpdump)
 */
public class MdnsDiscovery {

    private static final String TAG = "WScanner.mDNS";
    private static final String MDNS_ADDR = "224.0.0.251";
    private static final int MDNS_PORT = 5353;
    private static final int TIMEOUT_MS = 4000;

    /**
     * Descubre nombres mDNS para las IPs ya encontradas.
     *
     * @param ips        IPs a consultar (vía reverse lookup)
     * @param localAddr  dirección local de la interfaz WiFi
     * @return Map IP → nombre .local
     */
    public static Map<String, String> discover(Set<String> ips, InetAddress localAddr) {
        Map<String, String> results = new HashMap<>();
        MulticastSocket socket = null;
        long t0 = System.currentTimeMillis();

        Log.i(TAG, "═══════════════════════════════════════");
        Log.i(TAG, "🔵 Iniciando descubrimiento mDNS");

        try {
            socket = new MulticastSocket(MDNS_PORT);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.setReuseAddress(true);

            // Unirse al grupo multicast en la interfaz WiFi
            NetworkInterface netIf = NetworkInterface.getByInetAddress(localAddr);
            if (netIf != null) {
                InetAddress group = InetAddress.getByName(MDNS_ADDR);
                socket.joinGroup(new InetSocketAddress(group, MDNS_PORT), netIf);
                Log.d(TAG, "Unido a grupo multicast " + MDNS_ADDR + " en interfaz " + netIf.getDisplayName());
            } else {
                Log.w(TAG, "No se encontró NetworkInterface para " + localAddr + ", joinGroup sin interfaz");
                socket.joinGroup(InetAddress.getByName(MDNS_ADDR));
            }

            // ── Paso 1: Service discovery (PTR _services._dns-sd._udp.local) ──
            Log.d(TAG, "Enviando query PTR: _services._dns-sd._udp.local");
            byte[] queryServices = buildDnsQuery("_services._dns-sd._udp.local", (short) 12); // PTR
            sendQuery(socket, queryServices);

            // Esperar respuestas de servicios
            Map<String, String> serviceIps = new HashMap<>();
            try {
                long deadline = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < deadline) {
                    byte[] response = receivePacket(socket, (int) (1500 - (System.currentTimeMillis() - deadline + 3000)));
                    if (response == null) break;
                    Map<String, String> parsed = parseDnsResponse(response, MDNS_ADDR);
                    serviceIps.putAll(parsed);
                }
            } catch (Exception e) {
                Log.v(TAG, "Service discovery timeout (esperado): " + e.getMessage());
            }

            for (Map.Entry<String, String> e : serviceIps.entrySet()) {
                Log.i(TAG, "  🏷️  mDNS service: " + e.getKey() + " → " + e.getValue());
                results.put(e.getKey(), e.getValue());
            }

            // ── Paso 2: Reverse lookup para cada IP ──
            Log.d(TAG, "Reverse lookup mDNS para " + ips.size() + " IPs");
            for (String ip : ips) {
                String reverseName = ipToReverseArpa(ip);
                if (reverseName == null) continue;

                byte[] query = buildDnsQuery(reverseName, (short) 12); // PTR
                sendQuery(socket, query);
            }

            // Recibir respuestas de reverse lookup
            try {
                long deadline = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < deadline) {
                    int remaining = (int) (deadline - System.currentTimeMillis());
                    if (remaining <= 0) break;
                    socket.setSoTimeout(Math.max(remaining, 100));
                    byte[] response = receivePacket(socket, remaining);
                    if (response == null) break;
                    Map<String, String> parsed = parseDnsResponse(response, MDNS_ADDR);
                    for (Map.Entry<String, String> e : parsed.entrySet()) {
                        if (!results.containsKey(e.getKey())) {
                            Log.i(TAG, "  🏷️  mDNS reverse: " + e.getKey() + " → " + e.getValue());
                            results.put(e.getKey(), e.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                Log.v(TAG, "Reverse lookup timeout (esperado): " + e.getMessage());
            }

            socket.leaveGroup(InetAddress.getByName(MDNS_ADDR));

        } catch (IOException e) {
            Log.e(TAG, "Error en mDNS discovery: " + e.getMessage(), e);
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        Log.i(TAG, "✅ mDNS completado en " + elapsed + " ms — " + results.size() + " dispositivos");
        Log.i(TAG, "═══════════════════════════════════════");
        return results;
    }

    // ═══════════════════════ DNS Packet Builder ═══════════════════

    /**
     * Construye un paquete DNS query (RFC 1035 / 6762).
     *
     * @param name  nombre del registro (ej: "_services._dns-sd._udp.local")
     * @param qtype tipo de query (12 = PTR, 255 = ANY, 33 = SRV, 1 = A)
     */
    static byte[] buildDnsQuery(String name, short qtype) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // Header (12 bytes)
            short txId = (short) new Random().nextInt(65536);
            out.write((txId >> 8) & 0xFF);
            out.write(txId & 0xFF);
            // Flags: 0x0000 (standard query, not truncated, no recursion)
            out.write(0x00);
            out.write(0x00);
            // QDCOUNT = 1
            out.write(0x00);
            out.write(0x01);
            // ANCOUNT = 0
            out.write(0x00);
            out.write(0x00);
            // NSCOUNT = 0
            out.write(0x00);
            out.write(0x00);
            // ARCOUNT = 0
            out.write(0x00);
            out.write(0x00);

            // Question section
            encodeDnsName(out, name);
            // QTYPE
            out.write((qtype >> 8) & 0xFF);
            out.write(qtype & 0xFF);
            // QCLASS = 1 (IN) but mDNS uses 0x0001 for IN or 0x8001 for cache-flush
            out.write(0x00);
            out.write(0x01);

            return out.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream never throws IOException
            return new byte[0];
        }
    }

    /**
     * Codifica un nombre DNS en formato label (3www6google3com0).
     */
    static void encodeDnsName(ByteArrayOutputStream out, String name) throws IOException {
        String[] labels = name.split("\\.");
        for (String label : labels) {
            byte[] bytes = label.getBytes("UTF-8");
            out.write(bytes.length);
            out.write(bytes);
        }
        out.write(0x00); // terminador
    }

    // ═══════════════════════ DNS Response Parser ═══════════════════

    /**
     * Parsea una respuesta DNS y extrae mapeo IP → hostname.
     *
     * Busca ANY, PTR y SRV records. Si encuentra SRV, resuelve el target
     * a una IP buscando records A/AAAA en la sección de adicionales.
     */
    static Map<String, String> parseDnsResponse(byte[] data, String sourceAddr) {
        Map<String, String> results = new HashMap<>();

        if (data == null || data.length < 12) return results;

        try {
            // Leer header
            int txId = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            int flags = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            boolean isResponse = (flags & 0x8000) != 0;
            if (!isResponse) return results; // ignorar queries entrantes

            int qdcount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            int ancount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            int nscount = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
            int arcount = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);

            Log.v(TAG, "  DNS response: txId=" + txId + " answers=" + ancount
                    + " additional=" + arcount + " from=" + sourceAddr);

            DnsNameReader reader = new DnsNameReader(data);

            // Saltar sección de preguntas
            int pos = 12;
            for (int i = 0; i < qdcount; i++) {
                Object[] result = skipName(data, pos);
                pos = ((Integer) result[0]).intValue() + 4; // name + QTYPE(2) + QCLASS(2)
            }

            // Parsear answers, authorities, y additional (todos igual estructura)
            parseRecords(data, pos, ancount + nscount + arcount, reader, results, sourceAddr);

        } catch (Exception e) {
            Log.v(TAG, "Error parseando DNS: " + e.getMessage());
        }

        return results;
    }

    private static void parseRecords(byte[] data, int pos, int count,
                                      DnsNameReader reader,
                                      Map<String, String> results,
                                      String sourceAddr) {
        for (int i = 0; i < count && pos + 10 <= data.length; i++) {
            try {
                // NAME (puede ser comprimido con pointer)
                Object[] nameResult = readName(data, pos);
                String recordName = (String) nameResult[1];
                pos = ((Integer) nameResult[0]).intValue();

                if (pos + 10 > data.length) break;

                int rtype = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                // int rclass = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                // int ttl = ((data[pos + 4] & 0xFF) << 24) | ...;
                int rdlength = ((data[pos + 8] & 0xFF) << 8) | (data[pos + 9] & 0xFF);
                int rdataStart = pos + 10;

                if (rdataStart + rdlength > data.length) break;

                Log.v(TAG, "    record#" + i + " name=" + recordName + " type=" + rtype
                        + " rdlength=" + rdlength);

                if (rtype == 1 && rdlength == 4) {
                    // A record — mapear IP → nombre
                    String ip = (data[rdataStart] & 0xFF) + "."
                            + (data[rdataStart + 1] & 0xFF) + "."
                            + (data[rdataStart + 2] & 0xFF) + "."
                            + (data[rdataStart + 3] & 0xFF);
                    String hostname = cleanDotLocal(recordName);
                    if (!hostname.isEmpty()) {
                        results.put(ip, hostname);
                        Log.i(TAG, "  A record: " + ip + " → " + hostname);
                    }
                } else if (rtype == 12) {
                    // PTR record — el RDATA contiene un nombre
                    Object[] ptrResult = readName(data, rdataStart);
                    String ptrName = cleanDotLocal((String) ptrResult[1]);
                    Log.v(TAG, "    PTR: " + recordName + " → " + ptrName);
                    // Si el nombre de la pregunta es un reverse (in-addr.arpa)
                    // y la respuesta PTR apunta a un .local
                    if (recordName.contains("in-addr.arpa") && ptrName.endsWith(".local")) {
                        // Extraer IP de la pregunta
                        String ip = extractIpFromArpa(recordName);
                        if (ip != null) {
                            results.put(ip, ptrName);
                            Log.i(TAG, "  PTR reverse: " + ip + " → " + ptrName);
                        }
                    }
                    // También capturar PTRs de service discovery (_http._tcp.local → hostname.local)
                    if (!ptrName.isEmpty() && recordName.contains("._")) {
                        Log.i(TAG, "  PTR service: " + recordName + " → " + ptrName);
                        // No tenemos la IP aún, la guardamos con key=nombre para búsqueda posterior
                    }
                } else if (rtype == 33 && rdlength >= 6) {
                    // SRV record — extraer el target (nombre del host)
                    // priority(2) + weight(2) + port(2) + target(variable)
                    Object[] targetResult = readName(data, rdataStart + 6);
                    String targetName = cleanDotLocal((String) targetResult[1]);
                    Log.i(TAG, "  SRV target: " + recordName + " → " + targetName + " (port=" 
                            + (((data[rdataStart + 4] & 0xFF) << 8) | (data[rdataStart + 5] & 0xFF)) + ")");
                } else if (rtype == 16) {
                    // TXT record
                    Log.v(TAG, "    TXT record: " + recordName);
                } else if (rtype == 28) {
                    // AAAA record (IPv6)
                    Log.v(TAG, "    AAAA record: " + recordName);
                } else {
                    Log.v(TAG, "    Tipo desconocido: " + rtype + " name=" + recordName);
                }

                pos = rdataStart + rdlength;
            } catch (Exception e) {
                Log.v(TAG, "Error en record #" + i + ": " + e.getMessage());
                break;
            }
        }
    }

    // ═══════════════════════ DNS Name Helpers ═══════════════════════

    /**
     * Lee un nombre DNS (posiblemente comprimido con puntero 0xC000).
     * Retorna [nuevaPosición (Integer), nombreLeído (String)].
     */
    static Object[] readName(byte[] data, int pos) {
        StringBuilder name = new StringBuilder();
        int originalPos = pos;
        boolean jumped = false;
        int maxJumps = 10;

        while (maxJumps-- > 0) {
            if (pos >= data.length) break;

            int len = data[pos] & 0xFF;
            if (len == 0) {
                if (!jumped) pos++;
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                // Puntero comprimido
                if (pos + 1 >= data.length) break;
                int offset = ((len & 0x3F) << 8) | (data[pos + 1] & 0xFF);
                if (!jumped) {
                    originalPos = pos + 2;
                    jumped = true;
                }
                pos = offset;
            } else {
                // Label normal
                pos++;
                if (pos + len > data.length) break;
                if (name.length() > 0) name.append(".");
                name.append(new String(data, pos, len, java.nio.charset.StandardCharsets.UTF_8));
                pos += len;
            }
        }

        return new Object[]{Integer.valueOf(jumped ? originalPos : pos), name.toString()};
    }

    /**
     * Salta un nombre DNS sin leerlo. Retorna [nuevaPosición (Integer), ""].
     */
    static Object[] skipName(byte[] data, int pos) {
        boolean jumped = false;
        int originalPos = pos;
        int maxJumps = 10;

        while (maxJumps-- > 0) {
            if (pos >= data.length) break;
            int len = data[pos] & 0xFF;
            if (len == 0) {
                if (!jumped) pos++;
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                if (!jumped) originalPos = pos + 2;
                pos = (pos + 2 < data.length) ? ((len & 0x3F) << 8) | (data[pos + 1] & 0xFF) : pos;
                jumped = true;
            } else {
                pos += 1 + len;
            }
        }

        return new Object[]{Integer.valueOf(jumped ? originalPos : pos), ""};
    }

    // ═══════════════════════ Helpers ═══════════════════════════════

    /**
     * Convierte "192.168.1.80" → "80.1.168.192.in-addr.arpa"
     */
    static String ipToReverseArpa(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return null;
        return parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0] + ".in-addr.arpa";
    }

    /**
     * Extrae la IP de un nombre arpa inverso.
     * "80.1.168.192.in-addr.arpa" → "192.168.1.80"
     */
    static String extractIpFromArpa(String arpaName) {
        if (!arpaName.contains("in-addr.arpa")) return null;
        String[] parts = arpaName.split("\\.");
        if (parts.length >= 5) {
            return parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0];
        }
        return null;
    }

    /**
     * Limpia un nombre .local quitando el dominio .local y
     * nombres internos (como @, *, etc).
     */
    static String cleanDotLocal(String name) {
        if (name == null || name.isEmpty()) return "";
        String cleaned = name.replaceAll("\\.local\\.?$", "");
        // Ignorar nombres de servicio DNS-SD internos
        if (cleaned.startsWith("_") || cleaned.isEmpty()
                || cleaned.equals("@") || cleaned.equals("*")) {
            return "";
        }
        return cleaned;
    }

    static void sendQuery(MulticastSocket socket, byte[] data) throws IOException {
        DatagramPacket packet = new DatagramPacket(data, data.length,
                InetAddress.getByName(MDNS_ADDR), MDNS_PORT);
        socket.send(packet);
    }

    static byte[] receivePacket(MulticastSocket socket, int timeoutMs) {
        try {
            socket.setSoTimeout(Math.max(timeoutMs, 50));
            byte[] buf = new byte[1500];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            socket.receive(p);
            byte[] result = new byte[p.getLength()];
            System.arraycopy(buf, 0, result, 0, p.getLength());
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Clase auxiliar para lectura pos-declarativa de nombres DNS.
     */
    static class DnsNameReader {
        final byte[] data;
        DnsNameReader(byte[] data) { this.data = data; }
    }
}
