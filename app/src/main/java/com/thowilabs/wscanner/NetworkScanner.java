package com.thowilabs.wscanner;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class NetworkScanner {

    private static final String TAG = "WScanner.Scanner";

    public interface Callback {
        void onDeviceFound(Device device);
        void onProgress(int percent, int scanned, int total);
        void onFinished(int totalFound);
    }

    private VendorResolver vendorResolver;

    // Puertos comunes para inferir tipo de dispositivo
    private static final int[] PROBE_PORTS = {80, 443, 22, 445, 8080, 23, 21, 554, 1883};

    public void scan(Context context, Callback callback) {
        new Thread(() -> {
            Log.i(TAG, "═══════════════════════════════════════");
            Log.i(TAG, "🚀 INICIANDO ESCANEO DE RED");
            Log.i(TAG, "═══════════════════════════════════════");

            String subnet = detectSubnet(context);
            String gateway = detectGateway(context);

            Log.i(TAG, "📡 Subred detectada: " + subnet + "0/24");
            Log.i(TAG, "🚪 Gateway detectado: " + gateway);

            if (subnet == null) {
                Log.e(TAG, "❌ No se pudo detectar la subred. Abortando.");
                callback.onFinished(0);
                return;
            }

            if (vendorResolver == null) {
                Log.d(TAG, "Inicializando VendorResolver...");
                vendorResolver = new VendorResolver();
                vendorResolver.load(context);
                Log.d(TAG, "VendorResolver tiene " + vendorResolver.getEntryCount() + " entradas");
            }

            // ── Adquirir MulticastLock para mDNS y SSDP ──
            WifiManager.MulticastLock multicastLock = null;
            try {
                WifiManager wifi = (WifiManager) context.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    multicastLock = wifi.createMulticastLock("WScannerDiscovery");
                    multicastLock.setReferenceCounted(true);
                    multicastLock.acquire();
                    Log.d(TAG, "🔒 MulticastLock adquirido");
                }
            } catch (Exception e) {
                Log.w(TAG, "No se pudo adquirir MulticastLock: " + e.getMessage());
            }

            int total = 254;
            AtomicInteger scanned = new AtomicInteger(0);
            Set<String> foundIps = ConcurrentHashMap.newKeySet();
            Map<String, String> hostnames = new ConcurrentHashMap<>();
            Map<String, String> openPorts = new ConcurrentHashMap<>();
            Map<String, String> httpBanners = new ConcurrentHashMap<>();

            // ── FASE 1: Ping sweep + DNS inverso ──
            int threads = 10;
            Thread[] workers = new Thread[threads];

            Log.i(TAG, "────────────────────────────────────────");
            Log.i(TAG, "🔍 FASE 1: Ping sweep + DNS inverso (" + threads + " hilos)");

            long tPhase1 = System.currentTimeMillis();
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                workers[t] = new Thread(() -> {
                    for (int i = 1 + threadId; i <= total; i += threads) {
                        String host = subnet + i;
                        try {
                            InetAddress addr = InetAddress.getByName(host);
                            if (addr.isReachable(300)) {
                                foundIps.add(host);
                                Log.v(TAG, "  ✅ Ping OK: " + host);

                                String hostname = addr.getCanonicalHostName();
                                if (hostname != null && !hostname.equals(host)
                                        && !hostname.isEmpty() && !hostname.equals(addr.getHostAddress())) {
                                    hostnames.put(host, hostname);
                                    Log.v(TAG, "  🏷️  DNS (canonical): " + host + " → " + hostname);
                                } else {
                                    String h2 = addr.getHostName();
                                    if (h2 != null && !h2.equals(host)
                                            && !h2.isEmpty() && !h2.equals(addr.getHostAddress())) {
                                        hostnames.put(host, h2);
                                        Log.v(TAG, "  🏷️  DNS (hostname): " + host + " → " + h2);
                                    }
                                }
                            }
                        } catch (Exception ignored) {}

                        int done = scanned.incrementAndGet();
                        int pct = (done * 100) / total;
                        callback.onProgress(pct, done, total);
                    }
                });
                workers[t].start();
            }

            // ── FASE 1.5: mDNS discovery (en paralelo con el ping) ──
            final Map<String, String> mdnsNames = new ConcurrentHashMap<>();
            Thread mdnsThread = new Thread(() -> {
                try {
                    // Obtener la IP local para unicast en el socket multicast
                    InetAddress localAddr = getLocalInetAddress(context);
                    if (localAddr != null) {
                        // Esperar a que el ping encuentre algunas IPs primero
                        Thread.sleep(1500);
                        Set<String> ipsSnapshot = new HashSet<>(foundIps);
                        Map<String, String> mdns = MdnsDiscovery.discover(ipsSnapshot, localAddr);
                        mdnsNames.putAll(mdns);
                    } else {
                        Log.w(TAG, "No se pudo obtener InetAddress local para mDNS");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error en mDNS: " + e.getMessage());
                }
            });
            mdnsThread.start();

            // ── FASE 1.6: SSDP discovery (en paralelo con ping y mDNS) ──
            final Map<String, String> ssdpNames = new ConcurrentHashMap<>();
            Thread ssdpThread = new Thread(() -> {
                try {
                    Thread.sleep(500); // pequeño delay para no saturar
                    Map<String, String> ssdp = SsdpDiscovery.discover();
                    ssdpNames.putAll(ssdp);
                } catch (Exception e) {
                    Log.w(TAG, "Error en SSDP: " + e.getMessage());
                }
            });
            ssdpThread.start();

            // Esperar a que termine el ping
            for (Thread w : workers) { try { w.join(); } catch (InterruptedException ignored) {} }

            long elapsed1 = System.currentTimeMillis() - tPhase1;
            Log.i(TAG, "✅ FASE 1 completada en " + elapsed1 + " ms");
            Log.i(TAG, "   Hosts vivos (ping): " + foundIps.size());
            Log.i(TAG, "   Hostnames DNS: " + hostnames.size());

            callback.onProgress(100, total, total);

            // Esperar a que terminen mDNS y SSDP
            try { mdnsThread.join(8000); } catch (InterruptedException ignored) {}
            try { ssdpThread.join(6000); } catch (InterruptedException ignored) {}

            Log.i(TAG, "────────────────────────────────────────");
            Log.i(TAG, "🔵 FASE 1.5: mDNS → " + mdnsNames.size() + " nombres");
            Log.i(TAG, "🟢 FASE 1.6: SSDP → " + ssdpNames.size() + " nombres");

            // ── FASE 2: ARP table (seguirá fallando, mantenemos para consistencia) ──
            Log.i(TAG, "────────────────────────────────────────");
            Log.i(TAG, "🔍 FASE 2: Lectura de tabla ARP (14 métodos)");

            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            long tPhase2 = System.currentTimeMillis();
            Map<String, String> arpTable = readArpEveryWay();
            long elapsed2 = System.currentTimeMillis() - tPhase2;
            Log.i(TAG, "✅ FASE 2 completada en " + elapsed2 + " ms");
            Log.i(TAG, "   Entradas ARP obtenidas: " + arpTable.size());
            if (arpTable.isEmpty()) {
                Log.w(TAG, "   ⚠️  Todos los métodos ARP fallaron (Android 10+)");
                Log.w(TAG, "   ⚠️  Sin MACs no se puede resolver fabricante desde la OUI DB");
                Log.w(TAG, "   💡 Se usarán mDNS/SSDP/NetBIOS/HTTP para identificar dispositivos");
            }

            // ── FASE 3: Port scanning + HTTP banner grab ──
            Log.i(TAG, "────────────────────────────────────────");
            Log.i(TAG, "🔍 FASE 3: Port scanning + HTTP fingerprint en " + foundIps.size() + " hosts");

            long tPhase3 = System.currentTimeMillis();
            int portScanned = 0;
            for (String ip : foundIps) {
                StringBuilder ports = new StringBuilder();
                for (int port : PROBE_PORTS) {
                    if (isPortOpen(ip, port, 150)) {
                        ports.append(port).append(" ");
                    }
                }
                if (ports.length() > 0) {
                    String portStr = ports.toString().trim();
                    openPorts.put(ip, portStr);
                    Log.i(TAG, "  🔌 " + ip + " → puertos abiertos: [" + portStr + "]");

                    // HTTP banner grab en puertos 80, 443, 8080
                    if (portStr.contains("80") || portStr.contains("8080") || portStr.contains("443")) {
                        String banner = grabHttpBanner(ip);
                        if (banner != null) {
                            httpBanners.put(ip, banner);
                            Log.i(TAG, "  🌐 HTTP banner en " + ip + ": " + banner);
                        }
                    }
                }
                portScanned++;
                int pct2 = 100 + (portScanned * 10 / Math.max(foundIps.size(), 1));
                callback.onProgress(Math.min(pct2, 100), total + portScanned, total);
            }
            long elapsed3 = System.currentTimeMillis() - tPhase3;
            Log.i(TAG, "✅ FASE 3 completada en " + elapsed3 + " ms");
            Log.i(TAG, "   Hosts con puertos abiertos: " + openPorts.size());
            Log.i(TAG, "   HTTP banners: " + httpBanners.size());

            // ── FASE 3.5: NetBIOS probe (solo IPs con SMB/NetBIOS) ──
            Log.i(TAG, "────────────────────────────────────────");
            Log.i(TAG, "🔍 FASE 3.5: NetBIOS probe en IPs con SMB");

            Set<String> smbIps = new HashSet<>();
            for (String ip : foundIps) {
                String ports = openPorts.get(ip);
                if (ports != null && (ports.contains("445") || ports.contains("139"))) {
                    smbIps.add(ip);
                }
            }
            // También intentar en IPs sin puertos (por si el firewall bloquea TCP pero permite UDP 137)
            // Solo en IPs que no respondieron a nada más (genéricas)
            if (smbIps.isEmpty()) {
                for (String ip : foundIps) {
                    if (!openPorts.containsKey(ip) && !mdnsNames.containsKey(ip)
                            && !ssdpNames.containsKey(ip) && !ip.equals(gateway)) {
                        smbIps.add(ip);
                        if (smbIps.size() >= 10) break; // máximo 10 intentos sin pistas
                    }
                }
            }
            Map<String, String> netbiosNames = NetBiosDiscovery.discover(smbIps);

            // ── FASE 4: Construir dispositivos con naming multicapa ──
            Log.i(TAG, "────────────────────────────────────────");
            Log.i(TAG, "🔍 FASE 4: Construyendo dispositivos (" + foundIps.size() + " IPs)");

            int found = 0;
            for (String ip : foundIps) {
                String mac = arpTable.getOrDefault(ip, "N/A");
                String vendor = vendorResolver.resolve(mac);
                String hostname = hostnames.get(ip);
                String ports = openPorts.get(ip);
                String httpBanner = httpBanners.get(ip);
                String mdnsName = mdnsNames.get(ip);
                String ssdpName = ssdpNames.get(ip);
                String netbiosName = netbiosNames.get(ip);

                Log.d(TAG, "┌─ Dispositivo " + (found + 1) + " ────────────────────");
                Log.d(TAG, "│  IP:          " + ip);
                Log.d(TAG, "│  MAC:         " + mac);
                Log.d(TAG, "│  Vendor:      " + vendor);
                Log.d(TAG, "│  mDNS:        " + (mdnsName != null ? mdnsName : "(no)"));
                Log.d(TAG, "│  SSDP:        " + (ssdpName != null ? ssdpName : "(no)"));
                Log.d(TAG, "│  NetBIOS:     " + (netbiosName != null ? netbiosName : "(no)"));
                Log.d(TAG, "│  DNS:         " + (hostname != null ? hostname : "(no)"));
                Log.d(TAG, "│  HTTP:        " + (httpBanner != null ? httpBanner : "(no)"));
                Log.d(TAG, "│  Puertos:     " + (ports != null ? ports : "(ninguno)"));
                Log.d(TAG, "│  Gateway?:    " + ip.equals(gateway));

                DeviceNameResult nameResult = buildDeviceName(ip, gateway,
                        vendor,
                        mdnsName, ssdpName, netbiosName,
                        hostname, httpBanner, ports);

                Log.i(TAG, "│  🏷️  Nombre [" + nameResult.source + "]: " + nameResult.name);
                Log.d(TAG, "└──────────────────────────────────────");

                callback.onDeviceFound(new Device(nameResult.name, ip, mac, vendor,
                        nameResult.source, nameResult.detail));
                found++;
            }

            // ── Liberar MulticastLock ──
            if (multicastLock != null) {
                try {
                    multicastLock.release();
                    Log.d(TAG, "🔓 MulticastLock liberado");
                } catch (Exception ignored) {}
            }

            Log.i(TAG, "═══════════════════════════════════════");
            Log.i(TAG, "🏁 ESCANEO COMPLETO: " + found + " dispositivos encontrados");
            Log.i(TAG, "═══════════════════════════════════════");

            callback.onFinished(found);
        }).start();
    }

    // ════════════════════ Naming multicapa ═══════════════════════

    /**
     * Resultado del naming: nombre + fuente de dónde se obtuvo.
     */
    static class DeviceNameResult {
        final String name;
        final String source;
        final String detail;

        DeviceNameResult(String name, String source, String detail) {
            this.name = name;
            this.source = source;
            this.detail = detail;
        }
    }

    /**
     * Prioridad de nombrado (de mayor a menor confianza):
     *   1. Vendor OUI DB (solo si tenemos MAC real)
     *   2. mDNS hostname
     *   3. SSDP friendlyName / SERVER
     *   4. NetBIOS name
     *   5. DNS hostname
     *   6. HTTP server header / title
     *   7. Heurística por puertos + IP
     */
    private DeviceNameResult buildDeviceName(String ip, String gateway,
                                              String vendor,
                                              String mdnsName, String ssdpName,
                                              String netbiosName,
                                              String dnsHostname,
                                              String httpBanner,
                                              String ports) {

        // 1. Vendor desde MAC OUI
        if (!vendor.equals("Desconocido")) {
            return new DeviceNameResult(vendor, "OUI DB", vendor);
        }

        // 2. mDNS
        if (mdnsName != null && !mdnsName.isEmpty()) {
            return new DeviceNameResult(mdnsName, "mDNS", mdnsName);
        }

        // 3. SSDP
        if (ssdpName != null && !ssdpName.isEmpty()) {
            return new DeviceNameResult(ssdpName, "SSDP", ssdpName);
        }

        // 4. NetBIOS
        if (netbiosName != null && !netbiosName.isEmpty()) {
            return new DeviceNameResult(netbiosName, "NetBIOS", netbiosName);
        }

        // 5. DNS
        if (dnsHostname != null && !dnsHostname.isEmpty()) {
            return new DeviceNameResult(dnsHostname, "DNS", dnsHostname);
        }

        // 6. HTTP banner
        if (httpBanner != null && !httpBanner.isEmpty()) {
            return new DeviceNameResult(httpBanner, "HTTP", httpBanner);
        }

        // 7. Heurística
        return new DeviceNameResult(guessType(ip, gateway, ports),
                "Heurística", null);
    }

    // ════════════════════ HTTP Banner Grab ═══════════════════════

    /**
     * Hace GET / a la IP y extrae Server header + HTML title.
     * Timeout corto: 1500 ms.
     */
    private String grabHttpBanner(String ip) {
        // Intentar HTTP (puerto 80)
        String result = grabHttpBannerPort(ip, 80);
        if (result != null) return result;
        // Intentar puerto 8080
        return grabHttpBannerPort(ip, 8080);
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

            // Server header
            if (server != null && !server.isEmpty()) {
                String cleanServer = server.replaceAll("\\s+", " ").trim();
                if (cleanServer.length() > 60) {
                    cleanServer = cleanServer.substring(0, 57) + "...";
                }
                result.append("Server: ").append(cleanServer);
            }

            // HTML title — solo si el contenido es html
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
                        result.append("Title: ").append(title);
                    }
                } catch (Exception e) {
                    Log.v(TAG, "Error leyendo HTML de " + ip + ":" + port + ": " + e.getMessage());
                }
            }

            conn.disconnect();

            if (result.length() > 0) return result.toString();

        } catch (Exception e) {
            Log.v(TAG, "HTTP error " + ip + ":" + port + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private String extractTitle(String html) {
        String lower = html.toLowerCase();
        int startIdx = lower.indexOf("<title>");
        if (startIdx < 0) {
            startIdx = lower.indexOf("<title ");
            if (startIdx >= 0) {
                // <title attr="val">
                int gt = lower.indexOf(">", startIdx);
                if (gt < 0) return null;
                startIdx = gt + 1;
            } else {
                return null;
            }
        } else {
            startIdx += 7; // "<title>".length()
        }

        int endIdx = lower.indexOf("</title>", startIdx);
        if (endIdx < 0) return null;

        String title = html.substring(startIdx, endIdx).trim();
        // Limpiar entidades HTML básicas
        title = title.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");

        if (title.length() > 80) {
            title = title.substring(0, 77) + "...";
        }

        return title.isEmpty() ? null : title;
    }

    // ════════════════════ Nombrado heurístico ═════════════════════

    private String guessType(String ip, String gateway, String ports) {
        boolean isGw = ip.equals(gateway);
        String portInfo = (ports != null) ? ports : "";

        Log.v(TAG, "    guessType(ip=" + ip + ", gw=" + gateway + ", ports=" + portInfo + ")");

        if (isGw) {
            Log.v(TAG, "    → Router / Puerta de enlace");
            return "Router / Puerta de enlace";
        }

        if (portInfo.contains("80") || portInfo.contains("443") || portInfo.contains("8080")) {
            if (portInfo.contains("554")) {
                Log.v(TAG, "    → Cámara IP (HTTP + RTSP)");
                return "Cámara IP";
            }
            if (portInfo.contains("23") || portInfo.contains("22")) {
                Log.v(TAG, "    → Servidor / NAS (HTTP + SSH/Telnet)");
                return "Servidor / NAS";
            }
            Log.v(TAG, "    → Servicio web");
            return "Servicio web";
        }
        if (portInfo.contains("445") || portInfo.contains("139")) {
            Log.v(TAG, "    → PC / NAS (SMB)");
            return "PC / NAS (SMB)";
        }
        if (portInfo.contains("22") && !portInfo.contains("80")) {
            Log.v(TAG, "    → Servidor SSH");
            return "Servidor SSH";
        }
        if (portInfo.contains("1883")) {
            Log.v(TAG, "    → Dispositivo IoT (MQTT)");
            return "Dispositivo IoT (MQTT)";
        }
        if (portInfo.contains("554")) {
            Log.v(TAG, "    → Cámara IP (RTSP)");
            return "Cámara IP (RTSP)";
        }
        if (ip.endsWith(".1") || ip.endsWith(".254")) {
            Log.v(TAG, "    → Posible router / Gateway");
            return "Posible router / Gateway";
        }

        String fallback = "Equipo ." + ip.substring(ip.lastIndexOf('.') + 1);
        Log.v(TAG, "    → " + fallback);
        return fallback;
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

    // ════════════════════ IP local como InetAddress ══════════════

    private InetAddress getLocalInetAddress(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                WifiInfo info = wifi.getConnectionInfo();
                int ip = info.getIpAddress();
                if (ip != 0) {
                    byte[] addr = new byte[] {
                            (byte) (ip & 0xFF),
                            (byte) ((ip >> 8) & 0xFF),
                            (byte) ((ip >> 16) & 0xFF),
                            (byte) ((ip >> 24) & 0xFF)
                    };
                    return InetAddress.getByAddress(addr);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error obteniendo IP local: " + e.getMessage());
        }
        return null;
    }

    // ════════════════════ ARP — todos los métodos ═════════════════

    private Map<String, String> readArpEveryWay() {
        Map<String, String> arp;
        int method = 0;

        method++; arp = readArpFile("/proc/net/arp");
        logArpResult(1, "FileReader /proc/net/arp", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("/system/bin/cat /proc/net/arp");
        logArpResult(method, "/system/bin/cat /proc/net/arp", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("/bin/cat /proc/net/arp");
        logArpResult(method, "/bin/cat /proc/net/arp", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("cat /proc/net/arp");
        logArpResult(method, "cat /proc/net/arp", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("/system/bin/ip neigh show");
        logArpResult(method, "/system/bin/ip neigh show", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("/system/bin/ip neigh");
        logArpResult(method, "/system/bin/ip neigh", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("ip neigh show");
        logArpResult(method, "ip neigh show", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("ip neigh");
        logArpResult(method, "ip neigh", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("/system/bin/arp -a");
        logArpResult(method, "/system/bin/arp -a", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("/system/xbin/arp -a");
        logArpResult(method, "/system/xbin/arp -a", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("arp -a");
        logArpResult(method, "arp -a", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("/system/xbin/busybox arp -a");
        logArpResult(method, "/system/xbin/busybox arp -a", arp);
        if (!arp.isEmpty()) return arp;

        method++; arp = runShell("busybox arp -a");
        logArpResult(method, "busybox arp -a", arp);

        Log.w(TAG, "⚠️  Ninguno de los " + method + " métodos ARP dio resultados");
        return arp;
    }

    private void logArpResult(int method, String cmd, Map<String, String> result) {
        String status = result.isEmpty() ? "❌ vacío/error" : "✅ " + result.size() + " entradas";
        Log.d(TAG, "  ARP método #" + method + ": " + cmd + " → " + status);
    }

    private Map<String, String> readArpFile(String path) {
        Map<String, String> arp = new HashMap<>();
        try (BufferedReader br = new BufferedReader(
                new java.io.FileReader(path))) {
            String line;
            boolean header = true;
            int count = 0;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; }
                line = line.trim();
                if (line.isEmpty()) continue;
                count++;
                Log.v(TAG, "    ARP raw line[" + count + "]: \"" + line + "\"");
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    String ip = parts[0];
                    String mac = parts[3];
                    String flags = parts.length >= 3 ? parts[2] : "";
                    if (!"00:00:00:00:00:00".equals(mac)
                            && mac.matches("[0-9a-fA-F:]{17}")
                            && (flags.contains("2") || flags.contains("02"))) {
                        arp.put(ip, mac.toUpperCase());
                        Log.d(TAG, "    ARP ✓: " + ip + " → " + mac.toUpperCase());
                    }
                }
            }
            Log.d(TAG, "    ARP file: " + count + " líneas, " + arp.size() + " entradas");
        } catch (Exception e) {
            Log.v(TAG, "    ARP file FAIL: " + path + " → " + e.getClass().getSimpleName());
        }
        return arp;
    }

    private Map<String, String> runShell(String command) {
        Map<String, String> arp = new HashMap<>();
        try {
            Process p = Runtime.getRuntime().exec(command);
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String line;
            int raw = 0;
            while ((line = br.readLine()) != null) {
                raw++;
                Log.v(TAG, "    shell[" + command + "] line " + raw + ": \"" + line + "\"");
                String parsed = parseArpLine(line);
                if (parsed != null) {
                    String[] kv = parsed.split("\t");
                    arp.put(kv[0], kv[1]);
                }
            }
            BufferedReader err = new BufferedReader(
                    new InputStreamReader(p.getErrorStream()));
            StringBuilder errBuf = new StringBuilder();
            String errLine;
            while ((errLine = err.readLine()) != null) {
                errBuf.append(errLine).append("\n");
            }
            if (errBuf.length() > 0) {
                Log.w(TAG, "    shell stderr [" + command + "]: " + errBuf.toString().trim());
            }
            int exitCode = p.waitFor();
            Log.v(TAG, "    shell exit [" + command + "]: " + exitCode + ", parsed=" + arp.size());
            br.close();
        } catch (Exception e) {
            Log.v(TAG, "    shell FAIL [" + command + "]: " + e.getClass().getSimpleName());
        }
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

    private String detectSubnet(Context context) {
        try {
            WifiManager wifi = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                Log.w(TAG, "WifiManager es null, usando fallback");
                return fallbackSubnet();
            }
            WifiInfo info = wifi.getConnectionInfo();
            int ip = info.getIpAddress();
            Log.d(TAG, "WifiInfo.getIpAddress() = " + ip + " (raw int)");
            if (ip == 0) {
                Log.w(TAG, "IP es 0 (¿WiFi conectado?), usando fallback");
                return fallbackSubnet();
            }
            String subnet = String.format("%d.%d.%d.", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff));
            Log.i(TAG, "📡 IP local: " + subnet.substring(0, subnet.length() - 1));
            return subnet;
        } catch (Exception e) {
            Log.e(TAG, "Error detectando subred: " + e.getMessage());
            return fallbackSubnet();
        }
    }

    private String detectGateway(Context context) {
        try {
            WifiManager wifi = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                android.net.DhcpInfo dhcp = wifi.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    int gw = dhcp.gateway;
                    String gwStr = String.format("%d.%d.%d.%d",
                            (gw & 0xff), (gw >> 8 & 0xff),
                            (gw >> 16 & 0xff), (gw >> 24 & 0xff));
                    Log.i(TAG, "🚪 Gateway DHCP: " + gwStr);
                    return gwStr;
                }
            }
        } catch (Exception ignored) {
            Log.w(TAG, "Error detectando gateway: " + ignored.getMessage());
        }
        String fallbackGw = detectSubnet(context) + "1";
        Log.w(TAG, "Usando gateway por defecto: " + fallbackGw);
        return fallbackGw;
    }

    private String fallbackSubnet() { return "192.168.1."; }
}
