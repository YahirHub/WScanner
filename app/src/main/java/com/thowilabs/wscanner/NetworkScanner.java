package com.thowilabs.wscanner;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

public class NetworkScanner {

    private static final String TAG = "WScanner.Scanner";

    public interface Callback {
        /**
         * Llamado cada vez que se encuentra o actualiza un dispositivo.
         * El caller debe reemplazar/actualizar usando IP como clave.
         */
        void onDeviceFound(Device device);

        void onProgress(int percent, int scanned, int total);

        void onFinished(int totalFound);
    }

    private VendorResolver vendorResolver;
    private final AtomicLong scanGeneration = new AtomicLong(0);

    private static final int MAX_SCAN_HOSTS = 1024;
    private static final int PRESENCE_WORKERS = 24;
    private static final int[] DISCOVERY_PORTS = {80, 443, 22, 445, 554, 8080, 9100, 631};
    private static final int[] PROBE_PORTS = {
            21, 22, 23, 53, 80, 81, 139, 443, 445, 515, 548, 554, 631,
            1883, 3389, 5000, 5353, 5900, 8000, 8008, 8009, 8080, 8081,
            8443, 8883, 9000, 9100, 10000, 32400
    };

    // ════════════════════ Service name resolver ═══════════════════

    private static final Map<Integer, String> SERVICE_NAMES = new HashMap<>();
    static {
        SERVICE_NAMES.put(80, "HTTP");
        SERVICE_NAMES.put(81, "HTTP-Alt");
        SERVICE_NAMES.put(443, "HTTPS");
        SERVICE_NAMES.put(22, "SSH");
        SERVICE_NAMES.put(139, "NetBIOS-SSN");
        SERVICE_NAMES.put(445, "SMB");
        SERVICE_NAMES.put(515, "LPD");
        SERVICE_NAMES.put(548, "AFP");
        SERVICE_NAMES.put(631, "IPP");
        SERVICE_NAMES.put(8080, "HTTP-Alt");
        SERVICE_NAMES.put(23, "Telnet");
        SERVICE_NAMES.put(21, "FTP");
        SERVICE_NAMES.put(554, "RTSP");
        SERVICE_NAMES.put(1883, "MQTT");
        SERVICE_NAMES.put(8883, "MQTT-TLS");
        SERVICE_NAMES.put(53, "DNS");
        SERVICE_NAMES.put(3389, "RDP");
        SERVICE_NAMES.put(5900, "VNC");
        SERVICE_NAMES.put(5000, "HTTP/UPnP");
        SERVICE_NAMES.put(8000, "HTTP-Alt");
        SERVICE_NAMES.put(8008, "Cast-HTTP");
        SERVICE_NAMES.put(8009, "Cast");
        SERVICE_NAMES.put(8081, "HTTP-Alt");
        SERVICE_NAMES.put(8443, "HTTPS-Alt");
        SERVICE_NAMES.put(9000, "Service-9000");
        SERVICE_NAMES.put(10000, "Service-10000");
        SERVICE_NAMES.put(32400, "Media-Server");
        SERVICE_NAMES.put(5353, "mDNS");
        SERVICE_NAMES.put(9100, "RAW-Print");
    }

    /** Retorna el nombre del servicio para un puerto, o null si es desconocido. */
    public static String serviceName(int port) {
        return SERVICE_NAMES.get(port);
    }

    public void cancel() {
        scanGeneration.incrementAndGet();
    }

    private boolean isCancelled(long generation) {
        return scanGeneration.get() != generation || Thread.currentThread().isInterrupted();
    }

    public void scan(Context context, Callback callback) {
        final long generation = scanGeneration.incrementAndGet();
        new Thread(() -> {
            Log.i(TAG, "═══════════════════════════════════════");
            Log.i(TAG, "🚀 INICIANDO ESCANEO DE RED");
            Log.i(TAG, "═══════════════════════════════════════");

            NetworkRange range = detectNetworkRange(context);
            if (range == null) {
                Log.e(TAG, "❌ No se pudo detectar una red IPv4 local. Abortando.");
                if (!isCancelled(generation)) callback.onFinished(0);
                return;
            }

            String gateway = range.gateway != null ? range.gateway : detectGateway(context);
            List<String> hosts = range.hosts(MAX_SCAN_HOSTS);
            int total = hosts.size();

            Log.i(TAG, "📡 Red detectada: " + range.localIp + "/" + range.prefixLength
                    + " — " + total + " hosts a comprobar");
            Log.i(TAG, "🚪 Gateway detectado: " + gateway);

            AtomicInteger scanned = new AtomicInteger(0);
            Set<String> foundIps = ConcurrentHashMap.newKeySet();

            // La IP local y el gateway son candidatos válidos aunque bloqueen ICMP.
            if (range.localIp != null) {
                foundIps.add(range.localIp);
                callback.onDeviceFound(new Device("Este dispositivo", range.localIp,
                        "N/A", "Desconocido", "Local", range.localIp));
            }
            if (gateway != null && range.contains(gateway)) {
                foundIps.add(gateway);
                callback.onDeviceFound(new Device("Router / Puerta de enlace", gateway,
                        "N/A", "Desconocido", "Heurística", "Gateway de la red"));
            }

            // ── Adquirir MulticastLock ──
            WifiManager.MulticastLock multicastLock = acquireMulticastLock(context);

            // ── FASE 1: Ping sweep → emitir cada dispositivo inmediatamente ──
            Log.i(TAG, "────────────────────────────────────────");
            int threads = Math.min(PRESENCE_WORKERS, Math.max(1, total));
            Log.i(TAG, "🔍 FASE 1: Presencia ICMP/TCP + DNS inverso (" + threads + " hilos)");
            Thread[] workers = new Thread[threads];

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                workers[t] = new Thread(() -> {
                    for (int i = threadId; i < total && !isCancelled(generation); i += threads) {
                        String host = hosts.get(i);
                        try {
                            InetAddress addr = InetAddress.getByName(host);
                            if (isHostResponsive(addr, host)) {
                                foundIps.add(host);

                                String dnsName = null;
                                String canonical = addr.getCanonicalHostName();
                                if (canonical != null && !canonical.equals(host)
                                        && !canonical.isEmpty() && !canonical.equals(addr.getHostAddress())) {
                                    dnsName = canonical;
                                } else {
                                    String h2 = addr.getHostName();
                                    if (h2 != null && !h2.equals(host)
                                            && !h2.isEmpty() && !h2.equals(addr.getHostAddress())) {
                                        dnsName = h2;
                                    }
                                }

                                // Emitir dispositivo básico inmediatamente
                                String name = DeviceIdentity.classifyBySignals(host, gateway,
                                        java.util.Collections.emptyList(), java.util.Collections.emptyList());
                                String discoverySource = "Heurística";
                                String discoveryDetail = null;
                                if (dnsName != null) {
                                    name = dnsName;
                                    discoverySource = "DNS";
                                    discoveryDetail = dnsName;
                                }
                                callback.onDeviceFound(new Device(name, host,
                                        "N/A", "Desconocido", discoverySource, discoveryDetail));
                            }
                        } catch (Exception ignored) {
                        }
                        int done = scanned.incrementAndGet();
                        int pct = (done * 70) / Math.max(total, 1);
                        if (!isCancelled(generation)) callback.onProgress(pct, done, total);
                    }
                });
                workers[t].start();
            }

            // ── FASE 1.5: mDNS (paralelo al ping, service discovery) ──
            final Map<String, String> mdnsNames = new ConcurrentHashMap<>();
            Thread mdnsThread = new Thread(() -> {
                try {
                    InetAddress localAddr = InetAddress.getByName(range.localIp);
                    if (localAddr != null) {
                        // Service discovery inmediato, no necesita IPs
                        Map<String, String> mdns = MdnsDiscovery.discoverServiceDiscovery(localAddr);
                        mdnsNames.putAll(mdns);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error en mDNS: " + e.getMessage());
                }
            });
            mdnsThread.start();

            // ── FASE 1.6: SSDP (paralelo) ──
            final Map<String, String> ssdpNames = new ConcurrentHashMap<>();
            Thread ssdpThread = new Thread(() -> {
                try {
                    Thread.sleep(500);
                    InetAddress localAddr = InetAddress.getByName(range.localIp);
                    Map<String, String> ssdp = SsdpDiscovery.discover(localAddr);
                    ssdpNames.putAll(ssdp);
                } catch (Exception e) {
                    Log.w(TAG, "Error en SSDP: " + e.getMessage());
                }
            });
            ssdpThread.start();

            // Esperar que termine el ping
            for (Thread w : workers) {
                try { w.join(); } catch (InterruptedException ignored) {}
            }
            Log.i(TAG, "✅ FASE 1 completada: " + foundIps.size() + " hosts vivos");

            if (isCancelled(generation)) {
                releaseMulticastLock(multicastLock);
                return;
            }
            callback.onProgress(70, total, total);

            // ── FASE 1.5: Esperar mDNS service discovery → emitir/actualizar ──
            try { mdnsThread.join(8000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (isCancelled(generation)) {
                releaseMulticastLock(multicastLock);
                return;
            }
            Log.i(TAG, "🔵 mDNS service discovery: " + mdnsNames.size() + " nombres");
            for (Map.Entry<String, String> e : mdnsNames.entrySet()) {
                String ip = e.getKey();
                String mdnsName = e.getValue();
                if (range.contains(ip)) {
                    foundIps.add(ip);
                    callback.onDeviceFound(new Device(mdnsName, ip,
                            "N/A", "Desconocido", "mDNS", mdnsName));
                }
            }

            // ── FASE 1.55: mDNS reverse lookup (con lista completa de IPs) ──
            Log.i(TAG, "🔵 mDNS reverse lookup para " + foundIps.size() + " IPs");
            try {
                InetAddress localAddr = InetAddress.getByName(range.localIp);
                if (localAddr != null) {
                    Map<String, String> reverseMdns = MdnsDiscovery.discoverReverseLookups(
                            new HashSet<>(foundIps), localAddr);
                    Log.i(TAG, "🔵 mDNS reverse: " + reverseMdns.size() + " nombres adicionales");
                    for (Map.Entry<String, String> e : reverseMdns.entrySet()) {
                        String ip = e.getKey();
                        if (range.contains(ip) && !mdnsNames.containsKey(ip)) {
                            foundIps.add(ip);
                            callback.onDeviceFound(new Device(e.getValue(), ip,
                                    "N/A", "Desconocido", "mDNS", e.getValue()));
                        }
                    }
                }
            } catch (Exception ex) {
                Log.w(TAG, "Error en mDNS reverse: " + ex.getMessage());
            }

            if (isCancelled(generation)) {
                releaseMulticastLock(multicastLock);
                return;
            }

            // ── FASE 1.6: Esperar SSDP → emitir/actualizar ──
            try { ssdpThread.join(6000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (isCancelled(generation)) {
                releaseMulticastLock(multicastLock);
                return;
            }
            Log.i(TAG, "🟢 SSDP: " + ssdpNames.size() + " dispositivos");
            for (Map.Entry<String, String> e : ssdpNames.entrySet()) {
                String ip = e.getKey();
                String ssdpName = e.getValue();
                if (range.contains(ip)) {
                    foundIps.add(ip);
                    callback.onDeviceFound(new Device(ssdpName, ip,
                            "N/A", "Desconocido", "SSDP", ssdpName));
                }
            }

            if (isCancelled(generation)) {
                releaseMulticastLock(multicastLock);
                return;
            }
            callback.onProgress(75, total, total);

            // ── FASE 2: caché local de vecinos (mejor esfuerzo; no es requisito para detectar) ──
            Log.i(TAG, "🔍 FASE 2: Caché ARP/vecinos (mejor esfuerzo)");
            Map<String, String> arpTable = readNeighborCache();
            Log.i(TAG, "   ARP: " + arpTable.size() + " entradas");

            // La base OUI es solo un enriquecimiento opcional cuando Android expone una MAC.
            if (!arpTable.isEmpty() && vendorResolver == null) {
                vendorResolver = new VendorResolver();
                vendorResolver.load(context);
            }
            for (Map.Entry<String, String> e : arpTable.entrySet()) {
                String ip = e.getKey();
                if (!range.contains(ip)) continue;
                String mac = e.getValue();
                String vendor = vendorResolver != null ? vendorResolver.resolve(mac) : "Desconocido";
                foundIps.add(ip);
                callback.onDeviceFound(new Device(
                        vendor.equals("Desconocido") ? ("Equipo ." + ip.substring(ip.lastIndexOf('.') + 1)) : vendor,
                        ip, mac, vendor, "OUI DB", vendor));
            }

            callback.onProgress(80, total, total);

            // ── FASE 3: Port scanning + HTTP banner → emitir/actualizar ──
            Log.i(TAG, "🔍 FASE 3: Port scanning en " + foundIps.size() + " hosts");

            List<String> portTargets = new ArrayList<>(foundIps);
            AtomicInteger portScanned = new AtomicInteger(0);
            int portWorkers = Math.min(12, Math.max(1, portTargets.size()));
            ExecutorService portExecutor = Executors.newFixedThreadPool(portWorkers);
            for (String ip : portTargets) {
                portExecutor.execute(() -> {
                    if (!isCancelled(generation)) {
                        scanPortsForHost(ip, gateway, mdnsNames, ssdpNames, callback);
                    }
                    int done = portScanned.incrementAndGet();
                    int pct2 = 80 + (done * 10 / Math.max(portTargets.size(), 1));
                    if (!isCancelled(generation)) {
                        callback.onProgress(Math.min(pct2, 90), total + done, total);
                    }
                });
            }
            portExecutor.shutdown();
            try {
                portExecutor.awaitTermination(Math.max(10, portTargets.size() * 2L), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                portExecutor.shutdownNow();
            }
            Log.i(TAG, "✅ FASE 3 completada");

            if (isCancelled(generation)) {
                releaseMulticastLock(multicastLock);
                return;
            }

            // ── FASE 3.5: NetBIOS → emitir/actualizar ──
            Log.i(TAG, "🔍 FASE 3.5: NetBIOS");
            Set<String> smbIps = new HashSet<>();
            for (String ip : foundIps) {
                if (!mdnsNames.containsKey(ip) && !ssdpNames.containsKey(ip)
                        && !ip.equals(gateway) && !ip.equals(range.localIp)) {
                    smbIps.add(ip);
                }
            }
            Map<String, String> netbiosNames = NetBiosDiscovery.discover(smbIps);
            for (Map.Entry<String, String> e : netbiosNames.entrySet()) {
                String ip = e.getKey();
                if (foundIps.contains(ip)) {
                    callback.onDeviceFound(new Device(e.getValue(), ip,
                            "N/A", "Desconocido", "NetBIOS", e.getValue()));
                }
            }
            Log.i(TAG, "   NetBIOS: " + netbiosNames.size() + " nombres");

            if (!isCancelled(generation)) {
                callback.onProgress(100, total, total);
            }

            // ── Liberar MulticastLock ──
            releaseMulticastLock(multicastLock);

            Log.i(TAG, "═══════════════════════════════════════");
            Log.i(TAG, "🏁 ESCANEO COMPLETO: " + foundIps.size() + " hosts");
            Log.i(TAG, "═══════════════════════════════════════");

            if (!isCancelled(generation)) callback.onFinished(foundIps.size());
        }).start();
    }

    // ════════════════════ MulticastLock ══════════════════════════

    private WifiManager.MulticastLock acquireMulticastLock(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                WifiManager.MulticastLock lock = wifi.createMulticastLock("WScannerDiscovery");
                lock.setReferenceCounted(true);
                lock.acquire();
                Log.d(TAG, "🔒 MulticastLock adquirido");
                return lock;
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudo adquirir MulticastLock: " + e.getMessage());
        }
        return null;
    }

    private void releaseMulticastLock(WifiManager.MulticastLock lock) {
        if (lock != null) {
            try { if (lock.isHeld()) lock.release(); } catch (Exception ignored) {}
        }
    }

    // ════════════════════ HTTP Banner Grab ═══════════════════════

    private String grabHttpBanner(String ip, List<Integer> openPorts) {
        int[] httpPorts = {80, 81, 8000, 8008, 8080, 8081};
        for (int port : httpPorts) {
            if (openPorts.contains(port)) {
                String result = grabHttpBannerPort(ip, port);
                if (result != null) return result;
            }
        }
        return null;
    }

    private boolean hasHttpPort(List<Integer> openPorts) {
        int[] httpPorts = {80, 81, 8000, 8008, 8080, 8081};
        for (int port : httpPorts) if (openPorts.contains(port)) return true;
        return false;
    }

    private String grabHttpBannerPort(String ip, int port) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":" + port + "/");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1200);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "WScanner/1.0");
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            String server = conn.getHeaderField("Server");
            String contentType = conn.getHeaderField("Content-Type");

            StringBuilder result = new StringBuilder();
            if (server != null && !server.isEmpty()) {
                String clean = server.replaceAll("\\s+", " ").trim();
                if (clean.length() > 60) clean = clean.substring(0, 57) + "...";
                result.append(clean);
            }
            if (contentType != null && contentType.contains("html") && code == 200) {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder html = new StringBuilder();
                    String line;
                    int lines = 0;
                    while ((line = reader.readLine()) != null && lines < 50) {
                        html.append(line);
                        lines++;
                    }
                    reader.close();
                    String title = extractTitle(html.toString());
                    if (title != null && !title.isEmpty()) {
                        if (result.length() > 0) result.append(" | ");
                        result.append(title);
                    }
                } catch (Exception ignored) {}
            }
            conn.disconnect();
            return result.length() > 0 ? result.toString() : null;
        } catch (Exception ignored) {
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
        }
        return null;
    }

    private String extractTitle(String html) {
        String lower = html.toLowerCase();
        int start = lower.indexOf("<title>");
        if (start < 0) {
            start = lower.indexOf("<title ");
            if (start >= 0) {
                int gt = lower.indexOf(">", start);
                if (gt < 0) return null;
                start = gt + 1;
            } else {
                return null;
            }
        } else {
            start += 7;
        }
        int end = lower.indexOf("</title>", start);
        if (end < 0) return null;
        String title = html.substring(start, end).trim()
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return title.isEmpty() ? null : (title.length() > 80 ? title.substring(0, 77) + "..." : title);
    }

    private void scanPortsForHost(String ip, String gateway,
                                  Map<String, String> mdnsNames,
                                  Map<String, String> ssdpNames,
                                  Callback callback) {
        StringBuilder ports = new StringBuilder();
        List<Integer> openPortsList = new ArrayList<>();
        List<String> serviceNamesList = new ArrayList<>();

        for (int port : PROBE_PORTS) {
            if (isPortOpen(ip, port, 120)) {
                if (ports.length() > 0) ports.append(' ');
                ports.append(port);
                openPortsList.add(port);
                String svc = serviceName(port);
                if (svc != null) serviceNamesList.add(svc);
            }
        }
        if (openPortsList.isEmpty()) return;

        String portStr = ports.toString();
        Log.i(TAG, "  🔌 " + ip + " → [" + portStr + "]");
        String classified = DeviceIdentity.classifyBySignals(ip, gateway,
                openPortsList, serviceNamesList);
        Device portDevice = new Device(classified, ip, "N/A", "Desconocido",
                "TCP", "Puertos: " + portStr);
        portDevice.openPorts = openPortsList;
        portDevice.serviceNames = serviceNamesList;

        if (hasHttpPort(openPortsList)) {
            String banner = grabHttpBanner(ip, openPortsList);
            if (banner != null && !mdnsNames.containsKey(ip) && !ssdpNames.containsKey(ip)) {
                Device httpDevice = new Device(banner, ip, "N/A", "Desconocido", "HTTP", banner);
                httpDevice.openPorts = openPortsList;
                httpDevice.serviceNames = serviceNamesList;
                callback.onDeviceFound(httpDevice);
                return;
            }
            if (banner != null) portDevice.discoveryDetail += " · HTTP: " + banner;
        }
        callback.onDeviceFound(portDevice);
    }

    private boolean isHostResponsive(InetAddress address, String ip) {
        try {
            if (address.isReachable(220)) return true;
        } catch (Exception ignored) {}

        // Muchos equipos bloquean ICMP. Un servicio TCP abierto sigue siendo una señal
        // válida de presencia y evita perder cámaras, impresoras, NAS y dispositivos IoT.
        for (int port : DISCOVERY_PORTS) {
            if (isPortOpen(ip, port, 90)) return true;
        }
        return false;
    }

    // ════════════════════ Port scanning ══════════════════════════

    private boolean isPortOpen(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ════════════════════ Caché ARP / vecinos ════════════════════

    /**
     * Recupera MACs que el sistema ya tenga en caché. En Android moderno este acceso
     * puede estar restringido, por lo que nunca se usa como requisito para descubrir
     * un dispositivo: solo enriquece resultados obtenidos por señales de red.
     */
    private Map<String, String> readNeighborCache() {
        Map<String, String> entries = readArpFile("/proc/net/arp");
        if (!entries.isEmpty()) return entries;

        entries = runShell(new String[]{"/system/bin/ip", "neigh", "show"});
        if (!entries.isEmpty()) return entries;

        entries = runShell(new String[]{"ip", "neigh", "show"});
        if (entries.isEmpty()) {
            Log.d(TAG, "Caché ARP/vecinos no disponible; se continúa sin MAC/OUI");
        }
        return entries;
    }

    private Map<String, String> readArpFile(String path) {
        Map<String, String> arp = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(path))) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; }
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    String ip = parts[0], mac = parts[3], flags = parts.length >= 3 ? parts[2] : "";
                    if (!"00:00:00:00:00:00".equals(mac)
                            && mac.matches("[0-9a-fA-F:]{17}")
                            && (flags.contains("2") || flags.contains("02"))) {
                        arp.put(ip, mac.toUpperCase());
                    }
                }
            }
        } catch (Exception ignored) {}
        return arp;
    }

    private Map<String, String> runShell(String[] command) {
        Map<String, String> arp = new HashMap<>();
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                String parsed = parseArpLine(line);
                if (parsed != null) {
                    String[] kv = parsed.split("\t");
                    arp.put(kv[0], kv[1]);
                }
            }
            br.close();
            p.waitFor();
        } catch (Exception ignored) {}
        return arp;
    }

    private String parseArpLine(String line) {
        line = line.trim();
        if (line.isEmpty()) return null;

        if (line.contains("lladdr")) {
            String[] parts = line.split("\\s+");
            String ip = null, mac = null;
            boolean ok = false;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].matches("\\d+\\.\\d+\\.\\d+\\.\\d+") && ip == null) ip = parts[i];
                if (parts[i].equals("lladdr") && i + 1 < parts.length) mac = parts[i + 1];
                String u = parts[i].toUpperCase();
                if (u.equals("REACHABLE") || u.equals("STALE")
                        || u.equals("DELAY") || u.equals("PROBE")) ok = true;
            }
            if (ip != null && mac != null && ok && mac.matches("[0-9a-fA-F:]{17}"))
                return ip + "\t" + mac.toUpperCase();
        }

        String[] parts = line.split("\\s+");
        if (parts.length >= 4) {
            String ip = parts[0], mac = parts[3], flags = parts.length >= 3 ? parts[2] : "";
            if (ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
                    && mac.matches("[0-9a-fA-F:]{17}")
                    && !"00:00:00:00:00:00".equals(mac)
                    && (flags.contains("2") || flags.contains("02")))
                return ip + "\t" + mac.toUpperCase();
        }

        String[] tokens = line.split("\\s+");
        if (tokens.length >= 2) {
            String ip = null, mac = null;
            for (String t : tokens) {
                if (t.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) ip = t;
                if (t.matches("[0-9a-fA-F:]{17}")) mac = t;
            }
            if (ip != null && mac != null && !"00:00:00:00:00:00".equals(mac))
                return ip + "\t" + mac.toUpperCase();
        }
        return null;
    }

    // ════════════════════ Subred y Gateway ═══════════════════════

    private NetworkRange detectNetworkRange(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                // Preferir la red activa cuando sea WiFi/Ethernet. Si Android usa
                // celular como red por defecto pero mantiene una LAN conectada, buscar
                // después entre las demás redes disponibles.
                Network active = cm.getActiveNetwork();
                NetworkRange activeRange = networkRangeFrom(cm, active);
                if (activeRange != null) return activeRange;

                for (Network network : cm.getAllNetworks()) {
                    if (active != null && active.equals(network)) continue;
                    NetworkRange candidate = networkRangeFrom(cm, network);
                    if (candidate != null) return candidate;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudo leer LinkProperties: " + e.getMessage());
        }

        // Fallback compatible con Android antiguos / ROMs que no exponen LinkProperties.
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    String localIp = String.format("%d.%d.%d.%d",
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    return new NetworkRange(localIp, 24, detectGateway(context));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private NetworkRange networkRangeFrom(ConnectivityManager cm, Network network) {
        if (cm == null || network == null) return null;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) return null;
        boolean localTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        if (!localTransport || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null;

        LinkProperties props = cm.getLinkProperties(network);
        if (props == null) return null;

        String gateway = null;
        for (RouteInfo route : props.getRoutes()) {
            if (route.isDefaultRoute() && route.getGateway() instanceof java.net.Inet4Address) {
                gateway = route.getGateway().getHostAddress();
                break;
            }
        }
        for (LinkAddress link : props.getLinkAddresses()) {
            if (link.getAddress() instanceof java.net.Inet4Address
                    && !link.getAddress().isLoopbackAddress()) {
                return new NetworkRange(link.getAddress().getHostAddress(),
                        link.getPrefixLength(), gateway);
            }
        }
        return null;
    }

    private String detectGateway(Context context) {
        try {
            WifiManager wifi = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                android.net.DhcpInfo dhcp = wifi.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    int gw = dhcp.gateway;
                    return String.format("%d.%d.%d.%d",
                            (gw & 0xff), (gw >> 8 & 0xff),
                            (gw >> 16 & 0xff), (gw >> 24 & 0xff));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    static final class NetworkRange {
        final String localIp;
        final int prefixLength;
        final String gateway;
        final long network;
        final long broadcast;

        NetworkRange(String localIp, int prefixLength, String gateway) {
            this.localIp = localIp;
            this.prefixLength = Math.max(0, Math.min(32, prefixLength));
            this.gateway = gateway;
            long ip = ipv4ToLong(localIp);
            long mask = this.prefixLength == 0 ? 0 : (0xFFFFFFFFL << (32 - this.prefixLength)) & 0xFFFFFFFFL;
            this.network = ip & mask;
            this.broadcast = this.network | (~mask & 0xFFFFFFFFL);
        }

        boolean contains(String ip) {
            long value = ipv4ToLong(ip);
            return value >= 0 && value >= network && value <= broadcast;
        }

        List<String> hosts(int maxHosts) {
            long first = prefixLength >= 31 ? network : network + 1;
            long last = prefixLength >= 31 ? broadcast : broadcast - 1;
            long count = Math.max(0, last - first + 1);

            // ponytail: redes mayores de 1024 hosts se acotan al /24 del teléfono para
            // evitar escaneos de decenas de miles de IPs. Revisar si se agrega escaneo
            // configurable de redes empresariales grandes.
            if (count > maxHosts) {
                long local = ipv4ToLong(localIp);
                first = local & 0xFFFFFF00L;
                last = first + 255;
                first += 1;
                last -= 1;
            }

            List<String> result = new ArrayList<>();
            for (long value = first; value <= last && result.size() < maxHosts; value++) {
                result.add(longToIpv4(value));
            }
            return result;
        }

        private static long ipv4ToLong(String ip) {
            if (ip == null) return -1;
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return -1;
            long value = 0;
            try {
                for (String part : parts) {
                    int octet = Integer.parseInt(part);
                    if (octet < 0 || octet > 255) return -1;
                    value = (value << 8) | octet;
                }
                return value;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private static String longToIpv4(long value) {
            return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "."
                    + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
        }
    }
}
