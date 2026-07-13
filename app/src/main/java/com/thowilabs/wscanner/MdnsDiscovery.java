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
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Descubrimiento de dispositivos mediante mDNS (Multicast DNS / Bonjour).
 *
 * Usa MulticastSocket crudo (RFC 6762) en vez de NsdManager, porque
 * NsdManager en API 34+ (targetSdk 36) requiere ACCESS_LOCAL_NETWORK.
 *
 * Dos modos:
 *   1. Service discovery: PTR _services._dns-sd._udp.local → A
 *   2. Reverse lookup: Para cada IP, query PTR a X.X.X.X.in-addr.arpa
 */
public class MdnsDiscovery {

    private static final String TAG = "WScanner.mDNS";
    private static final String MDNS_ADDR = "224.0.0.251";
    private static final int MDNS_PORT = 5353;
    private static final int TIMEOUT_MS = 4000;

    /**
     * Descubrimiento de servicios mDNS (no necesita IPs previas).
     * Busca _services._dns-sd._udp.local → PTRs de tipos de servicio
     * → instancias → SRV targets → direcciones A.
     *
     * @param localAddr  dirección local de la interfaz WiFi
     * @return Map IP → nombre .local
     */
    public static Map<String, String> discoverServiceDiscovery(InetAddress localAddr) {
        Map<String, String> results = new HashMap<>();
        MulticastSocket socket = null;
        long t0 = System.currentTimeMillis();

        Log.i(TAG, "═══════════════════════════════════════");
        Log.i(TAG, "🔵 Iniciando mDNS service discovery");

        try {
            socket = createSocket(localAddr);
            if (socket == null) return results;

            // ── Paso 1: Descubrir tipos de servicio ──
            Log.d(TAG, "Query PTR: _services._dns-sd._udp.local");
            byte[] queryServices = buildDnsQuery("_services._dns-sd._udp.local", (short) 12);
            sendQuery(socket, queryServices);

            // Recibir PTRs → nombres de tipo de servicio (_http._tcp.local, _googlecast._tcp.local...)
            Set<String> serviceTypes = new HashSet<>();
            collectPtrResponses(socket, 2000, serviceTypes, null);

            Log.d(TAG, "Tipos de servicio encontrados: " + serviceTypes.size());
            for (String st : serviceTypes) {
                Log.v(TAG, "  Service type: " + st);
            }

            // ── Paso 2: Para servicios bien conocidos, buscar instancias ──
            String[] wellKnownServices = {
                    "_googlecast._tcp.local",
                    "_airplay._tcp.local",
                    "_raop._tcp.local",
                    "_http._tcp.local",
                    "_printer._tcp.local",
                    "_smb._tcp.local",
                    "_ssh._tcp.local",
                    "_workstation._tcp.local",
                    "_device-info._tcp.local",
                    "_hap._tcp.local",
                    "_homekit._tcp.local",
                    "_companion-link._tcp.local"
            };

            // También añadir los tipos descubiertos dinámicamente
            for (String st : serviceTypes) {
                if (st.endsWith(".local") || st.endsWith(".local.")) {
                    serviceTypes.add(st);
                }
            }

            // Incluir servicios descubiertos que no estén en la lista predefinida
            for (String wt : wellKnownServices) {
                serviceTypes.add(wt);
            }

            // ── Paso 3: Para cada tipo de servicio, buscar instancias ──
            Map<String, String> instanceToType = new HashMap<>();
            Map<String, Integer> instanceToPort = new HashMap<>();

            for (String serviceType : serviceTypes) {
                // Query PTR para este tipo de servicio → obtener nombres de instancia
                byte[] query = buildDnsQuery(serviceType, (short) 12);
                try {
                    sendQuery(socket, query);
                } catch (IOException ignored) { continue; }

                Set<String> instances = new HashSet<>();
                collectPtrResponses(socket, 800, instances, null);

                for (String inst : instances) {
                    instanceToType.put(inst, serviceType);
                }
                Log.v(TAG, "  " + serviceType + " → " + instances.size() + " instancias");
            }

            // ── Paso 4: Para cada instancia, resolver SRV → host:port + A → IP ──
            Map<String, String> hostToInstance = new HashMap<>();

            for (Map.Entry<String, String> e : instanceToType.entrySet()) {
                String instance = e.getKey();
                String type = e.getValue();

                // SRV query: instance.type
                String srvName = instance + "." + type;
                if (srvName.endsWith(".")) srvName = srvName.substring(0, srvName.length() - 1);
                if (!srvName.endsWith(".local")) srvName += ".local";

                byte[] srvQuery = buildDnsQuery(srvName, (short) 33);
                try {
                    sendQuery(socket, srvQuery);
                } catch (IOException ignored) { continue; }

                // Recibir SRV response
                byte[] response = receivePacket(socket, 1000);
                if (response != null) {
                    Map<String, SrvEntry> srvResults = parseSrvResponse(response);
                    for (Map.Entry<String, SrvEntry> sr : srvResults.entrySet()) {
                        String host = sr.getValue().target;
                        int port = sr.getValue().port;
                        hostToInstance.put(host, instance);
                        instanceToPort.put(instance, port);
                        Log.d(TAG, "  SRV: " + instance + " → " + host + ":" + port);
                    }
                }
            }

            // ── Paso 5: Resolver nombres de host a IPs ──
            for (Map.Entry<String, String> e : hostToInstance.entrySet()) {
                String hostname = e.getKey();
                String instance = e.getValue();

                String queryName = hostname;
                if (!queryName.endsWith(".local") && !queryName.endsWith(".")) {
                    queryName += ".local";
                }

                byte[] aQuery = buildDnsQuery(queryName, (short) 1); // A record
                try {
                    sendQuery(socket, aQuery);
                } catch (IOException ignored) { continue; }

                byte[] response = receivePacket(socket, 1000);
                if (response != null) {
                    Map<String, String> aResults = parseDnsResponse(response);
                    for (Map.Entry<String, String> ar : aResults.entrySet()) {
                        String displayName;
                        if (instance.endsWith(".")) instance = instance.substring(0, instance.length() - 1);
                        displayName = instance;
                        results.put(ar.getKey(), displayName);
                        Log.i(TAG, "  🏷️  mDNS: " + ar.getKey() + " → " + displayName);
                    }
                }
            }

            socket.leaveGroup(InetAddress.getByName(MDNS_ADDR));

        } catch (IOException e) {
            Log.e(TAG, "Error en mDNS: " + e.getMessage(), e);
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

    /**
     * Reverse lookup: para cada IP → query PTR X.X.X.X.in-addr.arpa.
     *
     * @param ips       IPs a consultar
     * @param localAddr dirección local de la interfaz WiFi
     * @return Map IP → nombre .local
     */
    public static Map<String, String> discoverReverseLookups(Set<String> ips, InetAddress localAddr) {
        Map<String, String> results = new HashMap<>();
        MulticastSocket socket = null;

        try {
            socket = createSocket(localAddr);
            if (socket == null) return results;

            // Enviar queries PTR para cada IP
            int sent = 0;
            for (String ip : ips) {
                String reverseName = ipToReverseArpa(ip);
                if (reverseName == null) continue;

                byte[] query = buildDnsQuery(reverseName, (short) 12);
                try {
                    sendQuery(socket, query);
                    sent++;
                } catch (IOException ignored) {
                }
            }
            Log.d(TAG, "Reverse lookup: " + sent + " queries enviadas");

            // Recibir respuestas
            long deadline = System.currentTimeMillis() + 3000;
            int recovered = 0;
            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) (deadline - System.currentTimeMillis());
                if (remaining <= 0) break;
                byte[] response = receivePacket(socket, remaining);
                if (response == null) break;
                Map<String, String> parsed = parseDnsResponse(response);
                for (Map.Entry<String, String> e : parsed.entrySet()) {
                    results.put(e.getKey(), e.getValue());
                    recovered++;
                    Log.i(TAG, "  🏷️  mDNS reverse: " + e.getKey() + " → " + e.getValue());
                }
            }

            Log.d(TAG, "Reverse lookup: " + recovered + " respuestas con IP");

            socket.leaveGroup(InetAddress.getByName(MDNS_ADDR));

        } catch (IOException e) {
            Log.e(TAG, "Error en reverse mDNS: " + e.getMessage(), e);
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        return results;
    }

    // ═══════════════════════ Socket helpers ════════════════════════

    private static MulticastSocket createSocket(InetAddress localAddr) {
        try {
            MulticastSocket socket = new MulticastSocket(MDNS_PORT);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.setReuseAddress(true);

            NetworkInterface netIf = NetworkInterface.getByInetAddress(localAddr);
            if (netIf != null) {
                InetAddress group = InetAddress.getByName(MDNS_ADDR);
                socket.joinGroup(new InetSocketAddress(group, MDNS_PORT), netIf);
                Log.d(TAG, "Unido a grupo multicast " + MDNS_ADDR + " en " + netIf.getDisplayName());
            } else {
                socket.joinGroup(InetAddress.getByName(MDNS_ADDR));
            }
            return socket;
        } catch (IOException e) {
            Log.e(TAG, "Error creando socket mDNS: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════ PTR collector ════════════════════════

    /**
     * Recibe respuestas PTR y recolecta los nombres PTR.
     * Si ipResults != null, también recolecta mapeos IP → nombre.
     */
    private static void collectPtrResponses(MulticastSocket socket, int timeoutMs,
                                            Set<String> ptrNames,
                                            Map<String, String> ipResults) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int remaining = (int) (deadline - System.currentTimeMillis());
            if (remaining <= 50) break;
            byte[] response = receivePacket(socket, remaining);
            if (response == null) break;

            try {
                if (response.length < 12) continue;

                int ancount = ((response[6] & 0xFF) << 8) | (response[7] & 0xFF);
                if (ancount == 0) {
                    // También mirar additional records (no los contamos en ancount pero no importa)
                    int arcount = ((response[10] & 0xFF) << 8) | (response[11] & 0xFF);
                    if (arcount == 0) continue;
                }

                Map<String, String> parsed = parseDnsResponse(response);

                if (ipResults != null) {
                    ipResults.putAll(parsed);
                }

                // Extraer nombres PTR del response (para service discovery)
                if (ptrNames != null) {
                    extractPtrNames(response, ptrNames);
                }

            } catch (Exception ex) {
                Log.v(TAG, "Error recolectando PTR: " + ex.getMessage());
            }
        }
    }

    /**
     * Extrae nombres PTR (service types, instance names) de una respuesta DNS.
     */
    private static void extractPtrNames(byte[] data, Set<String> names) {
        try {
            if (data.length < 12) return;
            int ancount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            int nscount = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
            int arcount = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);

            int pos = 12;
            // Saltar questions
            int qdcount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            for (int i = 0; i < qdcount && pos < data.length; i++) {
                int len;
                while ((len = data[pos++] & 0xFF) != 0) {
                    if ((len & 0xC0) == 0xC0) { pos++; break; }
                    pos += len;
                }
                if (pos >= data.length) break;
                pos += 4; // QTYPE + QCLASS
            }

            int totalRecords = ancount + nscount + arcount;
            for (int i = 0; i < totalRecords && pos + 10 <= data.length; i++) {
                // NAME
                Object[] nameResult = readName(data, pos);
                pos = ((Integer) nameResult[0]).intValue();
                if (pos + 10 > data.length) break;

                int rtype = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                int rdlength = ((data[pos + 8] & 0xFF) << 8) | (data[pos + 9] & 0xFF);
                int rdataStart = pos + 10;
                if (rdataStart + rdlength > data.length) break;

                if (rtype == 12) {
                    // PTR: el RDATA es un nombre
                    Object[] ptrResult = readName(data, rdataStart);
                    String ptrName = (String) ptrResult[1];
                    if (!ptrName.isEmpty()) {
                        // Normalizar: quitar .local final y trailing dot
                        String clean = ptrName.replaceAll("\\.local\\.?$", "");
                        if (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1);
                        if (!clean.isEmpty()) {
                            names.add(clean);
                        }
                    }
                }

                pos = rdataStart + rdlength;
            }
        } catch (Exception e) {
            Log.v(TAG, "Error extrayendo PTR names: " + e.getMessage());
        }
    }

    // ═══════════════════════ DNS Packet Builder ═══════════════════

    static byte[] buildDnsQuery(String name, short qtype) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            short txId = (short) new Random().nextInt(65536);
            out.write((txId >> 8) & 0xFF);
            out.write(txId & 0xFF);
            out.write(0x00); out.write(0x00); // flags
            out.write(0x00); out.write(0x01); // QDCOUNT=1
            out.write(0x00); out.write(0x00); // ANCOUNT=0
            out.write(0x00); out.write(0x00); // NSCOUNT=0
            out.write(0x00); out.write(0x00); // ARCOUNT=0
            encodeDnsName(out, name);
            out.write((qtype >> 8) & 0xFF);
            out.write(qtype & 0xFF);
            out.write(0x00); out.write(0x01); // QCLASS=IN
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    static void encodeDnsName(ByteArrayOutputStream out, String name) throws IOException {
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes("UTF-8");
            out.write(bytes.length);
            out.write(bytes);
        }
        out.write(0x00);
    }

    // ═══════════════════════ DNS Response Parser ═══════════════════

    /**
     * Parsea una respuesta DNS → Map<IP, hostname>.
     * Captura A records, PTR reverse, y SRV → A.
     */
    static Map<String, String> parseDnsResponse(byte[] data) {
        Map<String, String> results = new HashMap<>();
        if (data == null || data.length < 12) return results;

        try {
            int flags = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            if ((flags & 0x8000) == 0) return results; // es query, no response

            int qdcount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            int ancount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            int nscount = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
            int arcount = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);

            // Saltar questions
            int pos = 12;
            for (int i = 0; i < qdcount && pos < data.length; i++) {
                Object[] r = skipName(data, pos);
                pos = ((Integer) r[0]).intValue() + 4;
            }

            // Parsear answers + additional
            int total = ancount + nscount + arcount;
            parseRecords(data, pos, total, results);

        } catch (Exception e) {
            Log.v(TAG, "Error parseando DNS: " + e.getMessage());
        }
        return results;
    }

    private static void parseRecords(byte[] data, int pos, int count,
                                      Map<String, String> results) {
        // Primera pasada: recolectar A records y SRV targets
        Map<String, String> hostToIp = new HashMap<>(); // hostname.local → IP
        Map<String, String> ipToHost = new HashMap<>(); // IP → hostname.local

        for (int i = 0; i < count && pos + 10 <= data.length; i++) {
            try {
                Object[] nameResult = readName(data, pos);
                String recordName = (String) nameResult[1];
                pos = ((Integer) nameResult[0]).intValue();
                if (pos + 10 > data.length) break;

                int rtype = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                int rdlength = ((data[pos + 8] & 0xFF) << 8) | (data[pos + 9] & 0xFF);
                int rdataStart = pos + 10;
                if (rdataStart + rdlength > data.length) break;

                if (rtype == 1 && rdlength == 4) {
                    // A record
                    String ip = (data[rdataStart] & 0xFF) + "."
                            + (data[rdataStart + 1] & 0xFF) + "."
                            + (data[rdataStart + 2] & 0xFF) + "."
                            + (data[rdataStart + 3] & 0xFF);
                    String host = cleanDotLocal(recordName);
                    if (!host.isEmpty() && isValidHostname(host)) {
                        hostToIp.put(host, ip);
                        ipToHost.put(ip, host);
                    }
                } else if (rtype == 12) {
                    // PTR — puede ser reverse (in-addr.arpa) o service discovery
                    Object[] ptrResult = readName(data, rdataStart);
                    String ptrName = (String) ptrResult[1];

                    if (recordName.contains("in-addr.arpa")) {
                        // Reverse lookup: IP → hostname.local
                        String ip = extractIpFromArpa(recordName);
                        if (ip != null) {
                            String host = cleanDotLocal(ptrName);
                            if (!host.isEmpty() && isValidHostname(host)) {
                                results.put(ip, host);
                            }
                        }
                    }
                    // Service PTR (ignoramos aquí, se maneja en extractPtrNames)
                }

                pos = rdataStart + rdlength;
            } catch (Exception e) {
                break;
            }
        }
    }

    /**
     * Parsea respuesta SRV → Map<instance, SrvEntry>.
     */
    private static Map<String, SrvEntry> parseSrvResponse(byte[] data) {
        Map<String, SrvEntry> results = new HashMap<>();
        if (data == null || data.length < 12) return results;

        try {
            int flags = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            if ((flags & 0x8000) == 0) return results;

            int qdcount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            int ancount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            int nscount = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
            int arcount = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);

            int pos = 12;
            for (int i = 0; i < qdcount && pos < data.length; i++) {
                Object[] r = skipName(data, pos);
                pos = ((Integer) r[0]).intValue() + 4;
            }

            int total = ancount + nscount + arcount;
            for (int i = 0; i < total && pos + 10 <= data.length; i++) {
                Object[] nameResult = readName(data, pos);
                String recordName = (String) nameResult[1];
                pos = ((Integer) nameResult[0]).intValue();
                if (pos + 10 > data.length) break;

                int rtype = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                int rdlength = ((data[pos + 8] & 0xFF) << 8) | (data[pos + 9] & 0xFF);
                int rdataStart = pos + 10;
                if (rdataStart + rdlength > data.length) break;

                if (rtype == 33 && rdlength >= 6) {
                    int port = ((data[rdataStart + 4] & 0xFF) << 8) | (data[rdataStart + 5] & 0xFF);
                    Object[] targetResult = readName(data, rdataStart + 6);
                    String target = (String) targetResult[1];
                    results.put(recordName, new SrvEntry(target, port));
                }
                pos = rdataStart + rdlength;
            }
        } catch (Exception e) {
            Log.v(TAG, "Error en parseSrv: " + e.getMessage());
        }
        return results;
    }

    // ═══════════════════════ DNS Name Helpers ═══════════════════════

    static Object[] readName(byte[] data, int pos) {
        StringBuilder name = new StringBuilder();
        int originalPos = pos;
        boolean jumped = false;

        for (int hops = 0; hops < 20; hops++) {
            if (pos >= data.length) break;
            int len = data[pos] & 0xFF;
            if (len == 0) { if (!jumped) pos++; break; }
            if ((len & 0xC0) == 0xC0) {
                if (pos + 1 >= data.length) break;
                int offset = ((len & 0x3F) << 8) | (data[pos + 1] & 0xFF);
                if (!jumped) { originalPos = pos + 2; jumped = true; }
                pos = offset;
            } else {
                pos++;
                if (pos + len > data.length) break;
                if (name.length() > 0) name.append(".");
                name.append(new String(data, pos, len, java.nio.charset.StandardCharsets.UTF_8));
                pos += len;
            }
        }
        return new Object[]{Integer.valueOf(jumped ? originalPos : pos), name.toString()};
    }

    static Object[] skipName(byte[] data, int pos) {
        boolean jumped = false;
        int originalPos = pos;
        for (int hops = 0; hops < 20; hops++) {
            if (pos >= data.length) break;
            int len = data[pos] & 0xFF;
            if (len == 0) { if (!jumped) pos++; break; }
            if ((len & 0xC0) == 0xC0) {
                if (!jumped) originalPos = pos + 2;
                pos += 2;
                jumped = true;
                break;
            } else {
                pos += 1 + len;
            }
        }
        return new Object[]{Integer.valueOf(jumped ? originalPos : pos), ""};
    }

    // ═══════════════════════ Conversion helpers ═══════════════════

    static String ipToReverseArpa(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return null;
        return parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0] + ".in-addr.arpa";
    }

    static String extractIpFromArpa(String arpaName) {
        if (!arpaName.contains("in-addr.arpa")) return null;
        String[] parts = arpaName.split("\\.");
        if (parts.length >= 5) {
            return parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0];
        }
        return null;
    }

    /**
     * Limpia nombre .local → quitar dominio y trailing dot.
     * NO filtra nombres que empiecen con _ porque son nombres de servicio válidos.
     */
    static String cleanDotLocal(String name) {
        if (name == null || name.isEmpty()) return "";
        String cleaned = name.replaceAll("\\.local\\.?$", "");
        if (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        if (cleaned.isEmpty() || cleaned.equals("@") || cleaned.equals("*")) return "";
        return cleaned;
    }

    /** Rechaza IPs (no queremos "192.168.1.80" como hostname). */
    private static boolean isValidHostname(String host) {
        return !host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") && host.length() >= 2;
    }

    // ═══════════════════════ I/O ══════════════════════════════════

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

    // ═══════════════════════ Data classes ═════════════════════════

    static class SrvEntry {
        final String target;
        final int port;
        SrvEntry(String target, int port) { this.target = target; this.port = port; }
    }
}
