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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Descubrimiento de dispositivos mediante mDNS (Multicast DNS / Bonjour).
 *
 * Usa MulticastSocket crudo (RFC 6762) para observar múltiples tipos de servicio
 * en una sola pasada y mantener compatibilidad desde API 24. En Android 17 / API 37
 * el acceso LAN amplio requerirá ACCESS_LOCAL_NETWORK cuando la app apunte a ese SDK.
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
        Map<String, String> names = new HashMap<>();
        for (Map.Entry<String, Result> entry : discoverServiceDiscoveryDetailed(localAddr).entrySet()) {
            names.put(entry.getKey(), entry.getValue().displayName());
        }
        return names;
    }

    /**
     * Variante estructurada que conserva los tipos de servicio anunciados por cada
     * host. Esto permite clasificar Cast, AirPlay, impresión, SMB, HomeKit, Matter,
     * Android TV, MQTT y otros sin tablas de fabricantes.
     */
    public static Map<String, Result> discoverServiceDiscoveryDetailed(InetAddress localAddr) {
        Map<String, Result> results = new HashMap<>();
        MulticastSocket socket = null;
        long t0 = System.currentTimeMillis();

        Log.i(TAG, "═══════════════════════════════════════");
        Log.i(TAG, "🔵 Iniciando mDNS service discovery");

        try {
            socket = createSocket(localAddr);
            if (socket == null) return results;

            // 1) Descubrir tipos dinámicos. Las respuestas mDNS suelen incluir PTR,
            // SRV y A adicionales; conservar todo evita consultas redundantes.
            Set<String> discoveredTypes = new HashSet<>();
            Map<String, SrvEntry> srvEntries = new HashMap<>();
            Map<String, String> ipToHost = new HashMap<>();
            Map<String, Map<String, String>> txtEntries = new HashMap<>();

            sendQuery(socket, buildDnsQuery("_services._dns-sd._udp.local", (short) 12));
            collectDiscoveryResponses(socket, 1200, discoveredTypes, srvEntries, ipToHost, txtEntries);

            Set<String> serviceTypes = new HashSet<>();
            for (String serviceType : discoveredTypes) {
                String normalized = normalizeServiceType(serviceType);
                if (isServiceType(normalized)) serviceTypes.add(normalized);
            }

            String[] wellKnownServices = {
                    "_googlecast._tcp.local",
                    "_airplay._tcp.local",
                    "_raop._tcp.local",
                    "_spotify-connect._tcp.local",
                    "_http._tcp.local",
                    "_https._tcp.local",
                    "_ipp._tcp.local",
                    "_ipps._tcp.local",
                    "_printer._tcp.local",
                    "_scanner._tcp.local",
                    "_smb._tcp.local",
                    "_ssh._tcp.local",
                    "_workstation._tcp.local",
                    "_device-info._tcp.local",
                    "_hap._tcp.local",
                    "_homekit._tcp.local",
                    "_companion-link._tcp.local",
                    "_adb-tls-connect._tcp.local",
                    "_adb-tls-pairing._tcp.local",
                    "_androidtvremote2._tcp.local",
                    "_rtsp._tcp.local",
                    "_mqtt._tcp.local",
                    "_mqtts._tcp.local",
                    "_nfs._tcp.local",
                    "_ftp._tcp.local",
                    "_sftp-ssh._tcp.local",
                    "_telnet._tcp.local",
                    "_rfb._tcp.local",
                    "_daap._tcp.local",
                    "_matter._tcp.local",
                    "_matterc._udp.local",
                    "_meshcop._udp.local"
            };
            Collections.addAll(serviceTypes, wellKnownServices);
            Log.d(TAG, "Tipos mDNS a consultar: " + serviceTypes.size());

            // 2) Enviar todas las consultas PTR primero y compartir una sola ventana
            // de recepción. Esto evita O(n) timeouts que antes podían superar el join
            // del orquestador y dejar resultados mDNS fuera del escaneo principal.
            for (String serviceType : serviceTypes) {
                try {
                    sendQuery(socket, buildDnsQuery(serviceType, (short) 12));
                } catch (IOException e) {
                    Log.v(TAG, "No se pudo consultar " + serviceType + ": " + e.getMessage());
                }
            }

            Set<String> ptrNames = new HashSet<>();
            collectDiscoveryResponses(socket, 1800, ptrNames, srvEntries, ipToHost, txtEntries);

            Set<String> instances = new HashSet<>();
            for (String ptrName : ptrNames) {
                if (isServiceInstanceName(ptrName)) instances.add(ptrName);
            }
            Log.d(TAG, "Instancias mDNS encontradas: " + instances.size());

            // 3) Solicitar SRV de todas las instancias en lote. Muchas respuestas ya
            // llegaron como adicionales en el paso anterior; repetir la consulta es
            // barato y mejora cobertura frente a responders parciales.
            for (String instance : instances) {
                try {
                    sendQuery(socket, buildDnsQuery(normalizeSrvQueryName(instance, ""), (short) 33));
                    sendQuery(socket, buildDnsQuery(normalizeSrvQueryName(instance, ""), (short) 16));
                } catch (IOException e) {
                    Log.v(TAG, "No se pudo resolver SRV/TXT de " + instance + ": " + e.getMessage());
                }
            }
            collectDiscoveryResponses(socket, 1800, null, srvEntries, ipToHost, txtEntries);

            Map<String, Set<String>> hostToInstances = buildHostToInstances(srvEntries);
            mergeResolvedHostsDetailed(results, ipToHost, hostToInstances, srvEntries, txtEntries);

            // 4) Pedir A únicamente para targets SRV todavía no resueltos. También se
            // hace en lote para mantener el tiempo total acotado.
            Set<String> resolvedHosts = new HashSet<>();
            for (String host : ipToHost.values()) resolvedHosts.add(cleanDotLocal(host));
            for (String host : hostToInstances.keySet()) {
                if (resolvedHosts.contains(host)) continue;
                try {
                    sendQuery(socket, buildDnsQuery(host + ".local", (short) 1));
                } catch (IOException e) {
                    Log.v(TAG, "No se pudo resolver A de " + host + ": " + e.getMessage());
                }
            }
            collectDiscoveryResponses(socket, 1500, null, srvEntries, ipToHost, txtEntries);
            mergeResolvedHostsDetailed(results, ipToHost, buildHostToInstances(srvEntries), srvEntries, txtEntries);

            leaveGroup(socket, localAddr);

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

            leaveGroup(socket, localAddr);

        } catch (Exception e) {
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
            MulticastSocket socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(MDNS_PORT));
            socket.setSoTimeout(TIMEOUT_MS);

            NetworkInterface netIf = NetworkInterface.getByInetAddress(localAddr);
            if (netIf != null) {
                InetAddress group = InetAddress.getByName(MDNS_ADDR);
                socket.joinGroup(new InetSocketAddress(group, MDNS_PORT), netIf);
                Log.d(TAG, "Unido a grupo multicast " + MDNS_ADDR + " en " + netIf.getDisplayName());
            } else {
                socket.joinGroup(new InetSocketAddress(InetAddress.getByName(MDNS_ADDR), MDNS_PORT), null);
            }
            return socket;
        } catch (IOException e) {
            Log.e(TAG, "Error creando socket mDNS: " + e.getMessage());
            return null;
        }
    }

    private static void leaveGroup(MulticastSocket socket, InetAddress localAddr) {
        try {
            NetworkInterface netIf = NetworkInterface.getByInetAddress(localAddr);
            socket.leaveGroup(new InetSocketAddress(InetAddress.getByName(MDNS_ADDR), MDNS_PORT), netIf);
        } catch (Exception ignored) {
        }
    }

    // ═══════════════════════ PTR collector ════════════════════════

    /**
     * Recibe respuestas PTR y recolecta los nombres PTR.
     * Si ipResults != null, también recolecta mapeos IP → nombre.
     */
    private static void collectDiscoveryResponses(MulticastSocket socket, int timeoutMs,
                                                  Set<String> ptrNames,
                                                  Map<String, SrvEntry> srvEntries,
                                                  Map<String, String> ipToHost,
                                                  Map<String, Map<String, String>> txtEntries) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int remaining = (int) (deadline - System.currentTimeMillis());
            if (remaining <= 50) break;
            byte[] response = receivePacket(socket, remaining);
            if (response == null) break;

            if (ptrNames != null) extractPtrNames(response, ptrNames);
            if (srvEntries != null) srvEntries.putAll(parseSrvResponse(response));
            if (ipToHost != null) ipToHost.putAll(parseDnsResponse(response));
            if (txtEntries != null) mergeTxtEntries(txtEntries, parseTxtResponse(response));
        }
    }

    private static Map<String, Set<String>> buildHostToInstances(Map<String, SrvEntry> srvEntries) {
        Map<String, Set<String>> hostToInstances = new HashMap<>();
        for (Map.Entry<String, SrvEntry> entry : srvEntries.entrySet()) {
            String host = cleanDotLocal(entry.getValue().target);
            String instance = cleanDotLocal(entry.getKey());
            if (!host.isEmpty() && !instance.isEmpty()) {
                hostToInstances.computeIfAbsent(host, ignored -> new HashSet<>()).add(instance);
            }
        }
        return hostToInstances;
    }

    private static void mergeResolvedHostsDetailed(Map<String, Result> results,
                                                   Map<String, String> ipToHost,
                                                   Map<String, Set<String>> hostToInstances,
                                                   Map<String, SrvEntry> srvEntries,
                                                   Map<String, Map<String, String>> txtEntries) {
        Map<String, Set<String>> hostServices = buildHostServices(srvEntries);
        for (Map.Entry<String, String> entry : ipToHost.entrySet()) {
            String host = cleanDotLocal(entry.getValue());
            Result result = results.computeIfAbsent(entry.getKey(), ignored -> new Result());
            result.hostName = host;

            Set<String> instances = hostToInstances.get(host);
            if (instances != null) {
                for (String fullInstance : instances) {
                    String instance = cleanServiceInstanceName(fullInstance);
                    if (!instance.isEmpty()) result.offerName(instance, 8);
                    Map<String, String> txt = findTxtForInstance(txtEntries, fullInstance);
                    applyTxtMetadata(result, txt);
                }
            }
            if (result.name == null && !host.isEmpty()) {
                result.offerName(host, 4);
            }

            Set<String> services = hostServices.get(host);
            if (services != null) {
                for (String service : services) result.addService(service);
            }
            Log.i(TAG, "  🏷️  mDNS: " + entry.getKey() + " → " + result.displayName()
                    + (result.services.isEmpty() ? "" : " " + result.services));
        }
    }

    private static Map<String, String> findTxtForInstance(
            Map<String, Map<String, String>> txtEntries, String instance) {
        if (txtEntries == null || txtEntries.isEmpty() || instance == null) return Collections.emptyMap();
        String wanted = cleanDotLocal(instance).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Map<String, String>> entry : txtEntries.entrySet()) {
            if (cleanDotLocal(entry.getKey()).toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.getValue();
            }
        }
        return Collections.emptyMap();
    }

    static void applyTxtMetadata(Result result, Map<String, String> txt) {
        if (result == null || txt == null || txt.isEmpty()) return;

        String friendly = firstUseful(txt, "fn", "name", "displayname", "nickname");
        if (friendly != null) result.offerName(friendly, 10);

        if (result.manufacturer == null) {
            result.manufacturer = firstUseful(txt, "manufacturer", "mfg", "mf");
        }
        if (result.model == null) {
            result.model = firstUseful(txt, "model", "md", "ty", "product", "am");
        }
        if (result.osHint == null) {
            result.osHint = firstUseful(txt, "os", "osvers", "platform");
        }
        if (result.mac == null) {
            String possibleMac = firstUseful(txt, "deviceid", "mac", "macaddress");
            if (isMac(possibleMac)) result.mac = normalizeMac(possibleMac);
        }

        for (Map.Entry<String, String> entry : txt.entrySet()) {
            result.addTxt(entry.getKey(), entry.getValue());
        }
    }

    private static String firstUseful(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value == null) continue;
            String cleaned = cleanTxtValue(value);
            if (cleaned != null) return cleaned;
        }
        return null;
    }

    private static boolean isMac(String value) {
        if (value == null) return false;
        return value.trim().matches("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}");
    }

    private static String normalizeMac(String value) {
        return value.trim().replace('-', ':').toUpperCase(Locale.ROOT);
    }

    private static Map<String, Set<String>> buildHostServices(Map<String, SrvEntry> srvEntries) {
        Map<String, Set<String>> result = new HashMap<>();
        for (Map.Entry<String, SrvEntry> entry : srvEntries.entrySet()) {
            String host = cleanDotLocal(entry.getValue().target);
            String service = extractServiceTypeFromInstance(entry.getKey());
            if (host.isEmpty() || service.isEmpty()) continue;
            result.computeIfAbsent(host, ignored -> new HashSet<>()).add(service);
        }
        return result;
    }

    static String extractServiceTypeFromInstance(String instance) {
        String clean = cleanDotLocal(instance);
        int marker = clean.indexOf("._");
        if (marker < 0 || marker + 1 >= clean.length()) return "";
        String service = clean.substring(marker + 1);
        return isServiceType(service) ? service : "";
    }

    static String normalizeServiceType(String serviceType) {
        String type = cleanDotLocal(serviceType);
        if (type.isEmpty()) return "";
        return type + ".local";
    }

    static boolean isServiceType(String value) {
        if (value == null) return false;
        String clean = cleanDotLocal(value).toLowerCase(java.util.Locale.ROOT);
        return clean.startsWith("_") && (clean.endsWith("._tcp") || clean.endsWith("._udp"));
    }

    static boolean isServiceInstanceName(String value) {
        if (value == null) return false;
        String clean = cleanDotLocal(value).toLowerCase(java.util.Locale.ROOT);
        int first = clean.indexOf("._");
        if (first <= 0) return false;
        return clean.indexOf("._", first + 2) > first;
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


    static String cleanServiceInstanceName(String instance, String serviceType) {
        String inst = cleanDotLocal(instance);
        String type = cleanDotLocal(serviceType);
        String suffix = "." + type;
        if (!type.isEmpty() && inst.toLowerCase(java.util.Locale.ROOT)
                .endsWith(suffix.toLowerCase(java.util.Locale.ROOT))) {
            inst = inst.substring(0, inst.length() - suffix.length());
        }
        return inst.trim();
    }

    static String cleanServiceInstanceName(String instance) {
        String inst = cleanDotLocal(instance);
        return inst.replaceFirst("(?i)\\._[^.]+\\._(?:tcp|udp)$", "").trim();
    }

    static String normalizeSrvQueryName(String instance, String serviceType) {
        String inst = cleanDotLocal(instance);
        String type = cleanDotLocal(serviceType);
        if (inst.isEmpty()) return type + ".local";

        String lowerInst = inst.toLowerCase(java.util.Locale.ROOT);
        String lowerType = type.toLowerCase(java.util.Locale.ROOT);
        if (lowerInst.endsWith(lowerType) || lowerInst.contains("._tcp") || lowerInst.contains("._udp")) {
            return inst + ".local";
        }
        return inst + "." + type + ".local";
    }

    // ═══════════════════════ DNS Packet Builder ═══════════════════

    static byte[] buildDnsQuery(String name, short qtype) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // mDNS multicast usa transaction ID 0; evita depender de IDs aleatorios
            // que algunos responders ignoran en tráfico multicast.
            out.write(0x00);
            out.write(0x00);
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
                        // Un A record ya es evidencia válida IP → hostname. La versión
                        // anterior lo guardaba en mapas temporales pero nunca lo publicaba.
                        results.put(ip, host);
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

    /**
     * Extrae registros TXT DNS-SD. Los valores son autoanunciados por el propio
     * dispositivo y se usan solo como metadatos; no existe una tabla de marcas.
     */
    static Map<String, Map<String, String>> parseTxtResponse(byte[] data) {
        Map<String, Map<String, String>> results = new HashMap<>();
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
                pos = ((Integer) r[0]).intValue();
                if (pos + 4 > data.length) return results;
                pos += 4;
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
                int rdataEnd = rdataStart + rdlength;
                if (rdataEnd > data.length) break;

                if (rtype == 16) {
                    Map<String, String> values = results.computeIfAbsent(
                            cleanDotLocal(recordName), ignored -> new LinkedHashMap<>());
                    int cursor = rdataStart;
                    while (cursor < rdataEnd) {
                        int txtLength = data[cursor++] & 0xFF;
                        if (txtLength == 0) continue;
                        if (cursor + txtLength > rdataEnd) break;
                        String raw = new String(data, cursor, txtLength,
                                java.nio.charset.StandardCharsets.UTF_8).trim();
                        cursor += txtLength;
                        if (raw.isEmpty()) continue;

                        int eq = raw.indexOf('=');
                        String key = (eq >= 0 ? raw.substring(0, eq) : raw)
                                .trim().toLowerCase(Locale.ROOT);
                        String value = eq >= 0 ? raw.substring(eq + 1).trim() : "true";
                        if (!key.isEmpty() && key.length() <= 64) {
                            String cleanedValue = cleanTxtValue(value);
                            if (cleanedValue != null) values.put(key, cleanedValue);
                        }
                    }
                }
                pos = rdataEnd;
            }
        } catch (Exception e) {
            Log.v(TAG, "Error en parseTxt: " + e.getMessage());
        }
        return results;
    }

    private static void mergeTxtEntries(Map<String, Map<String, String>> target,
                                        Map<String, Map<String, String>> source) {
        for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
            Map<String, String> current = target.computeIfAbsent(
                    entry.getKey(), ignored -> new LinkedHashMap<>());
            current.putAll(entry.getValue());
        }
    }

    private static String cleanTxtValue(String value) {
        if (value == null) return null;
        String cleaned = value.replace('\u0000', ' ').trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty() || cleaned.equalsIgnoreCase("unknown")) return null;
        return cleaned.length() > 160 ? cleaned.substring(0, 160) : cleaned;
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

    public static final class Result {
        public String name;
        public String hostName;
        public String manufacturer;
        public String model;
        public String osHint;
        public String mac;
        public final java.util.List<String> services = new java.util.ArrayList<>();
        public final Map<String, String> txt = new LinkedHashMap<>();
        private int nameScore;

        void offerName(String candidate, int score) {
            if (candidate == null) return;
            String clean = candidate.trim();
            if (clean.isEmpty() || score <= nameScore) return;
            name = clean;
            nameScore = score;
        }

        void addService(String service) {
            if (service == null || service.trim().isEmpty() || services.contains(service)) return;
            services.add(service);
        }

        void addTxt(String key, String value) {
            if (key == null || value == null || txt.size() >= 24) return;
            txt.put(key, value);
        }

        public String displayName() {
            if (name != null && !name.trim().isEmpty()) return name;
            if (hostName != null && !hostName.trim().isEmpty()) return hostName;
            return "Dispositivo mDNS";
        }

        public String detail() {
            StringBuilder detail = new StringBuilder();
            if (hostName != null && !hostName.trim().isEmpty()) detail.append(hostName).append(".local");
            if (!services.isEmpty()) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append("Servicios: ").append(String.join(", ", services));
            }
            if (manufacturer != null && !manufacturer.trim().isEmpty()) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append("Fabricante anunciado: ").append(manufacturer);
            }
            if (model != null && !model.trim().isEmpty()) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append("Modelo anunciado: ").append(model);
            }
            if (osHint != null && !osHint.trim().isEmpty()) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append("Plataforma anunciada: ").append(osHint);
            }
            return detail.length() == 0 ? null : detail.toString();
        }
    }

    static class SrvEntry {
        final String target;
        final int port;
        SrvEntry(String target, int port) { this.target = target; this.port = port; }
    }
}
