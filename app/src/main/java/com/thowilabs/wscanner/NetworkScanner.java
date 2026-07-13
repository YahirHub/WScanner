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
        /**
         * Llamado cada vez que se encuentra o actualiza un dispositivo.
         * El caller debe reemplazar/actualizar usando IP como clave.
         */
        void onDeviceFound(Device device);

        void onProgress(int percent, int scanned, int total);

        void onFinished(int totalFound);
    }

    private VendorResolver vendorResolver;

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

            int total = 254;
            AtomicInteger scanned = new AtomicInteger(0);
            Set<String> foundIps = ConcurrentHashMap.newKeySet();

            // ── Adquirir MulticastLock ──
            WifiManager.MulticastLock multicastLock = acquireMulticastLock(context);

            // ── FASE 1: Ping sweep → emitir cada dispositivo inmediatamente ──
            Log.i(TAG, "────────────────────────────────────────");
            Log.i(TAG, "🔍 FASE 1: Ping sweep + DNS inverso (10 hilos)");

            int threads = 10;
            Thread[] workers = new Thread[threads];

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                workers[t] = new Thread(() -> {
                    for (int i = 1 + threadId; i <= total; i += threads) {
                        String host = subnet + i;
                        try {
                            InetAddress addr = InetAddress.getByName(host);
                            if (addr.isReachable(300)) {
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
                                String name = guessType(host, gateway, null);
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
                        int pct = (done * 70) / total;
                        callback.onProgress(pct, done, total);
                    }
                });
                workers[t].start();
            }

            // ── FASE 1.5: mDNS (paralelo al ping, service discovery) ──
            final Map<String, String> mdnsNames = new ConcurrentHashMap<>();
            Thread mdnsThread = new Thread(() -> {
                try {
                    InetAddress localAddr = getLocalInetAddress(context);
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
                    Map<String, String> ssdp = SsdpDiscovery.discover();
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

            callback.onProgress(70, total, total);

            // ── FASE 1.5: Esperar mDNS service discovery → emitir/actualizar ──
            try { mdnsThread.join(8000); } catch (InterruptedException ignored) {}
            Log.i(TAG, "🔵 mDNS service discovery: " + mdnsNames.size() + " nombres");
            for (Map.Entry<String, String> e : mdnsNames.entrySet()) {
                String ip = e.getKey();
                String mdnsName = e.getValue();
                if (foundIps.contains(ip)) {
                    callback.onDeviceFound(new Device(mdnsName, ip,
                            "N/A", "Desconocido", "mDNS", mdnsName));
                }
            }

            // ── FASE 1.55: mDNS reverse lookup (con lista completa de IPs) ──
            Log.i(TAG, "🔵 mDNS reverse lookup para " + foundIps.size() + " IPs");
            try {
                InetAddress localAddr = getLocalInetAddress(context);
                if (localAddr != null) {
                    Map<String, String> reverseMdns = MdnsDiscovery.discoverReverseLookups(
                            new HashSet<>(foundIps), localAddr);
                    Log.i(TAG, "🔵 mDNS reverse: " + reverseMdns.size() + " nombres adicionales");
                    for (Map.Entry<String, String> e : reverseMdns.entrySet()) {
                        String ip = e.getKey();
                        if (foundIps.contains(ip) && !mdnsNames.containsKey(ip)) {
                            callback.onDeviceFound(new Device(e.getValue(), ip,
                                    "N/A", "Desconocido", "mDNS", e.getValue()));
                        }
                    }
                }
            } catch (Exception ex) {
                Log.w(TAG, "Error en mDNS reverse: " + ex.getMessage());
            }

            // ── FASE 1.6: Esperar SSDP → emitir/actualizar ──
            try { ssdpThread.join(6000); } catch (InterruptedException ignored) {}
            Log.i(TAG, "🟢 SSDP: " + ssdpNames.size() + " dispositivos");
            for (Map.Entry<String, String> e : ssdpNames.entrySet()) {
                String ip = e.getKey();
                String ssdpName = e.getValue();
                if (foundIps.contains(ip)) {
                    callback.onDeviceFound(new Device(ssdpName, ip,
                            "N/A", "Desconocido", "SSDP", ssdpName));
                }
            }

            callback.onProgress(75, total, total);

            // ── FASE 2: ARP (mantenemos consistencia, siempre vacío en Android 10+) ──
            Log.i(TAG, "🔍 FASE 2: ARP (14 métodos)");
            Map<String, String> arpTable = readArpEveryWay();
            Log.i(TAG, "   ARP: " + arpTable.size() + " entradas");

            // Si algún ARP dio resultado, procesarlo
            for (Map.Entry<String, String> e : arpTable.entrySet()) {
                String ip = e.getKey();
                String mac = e.getValue();
                String vendor = vendorResolver.resolve(mac);
                if (foundIps.contains(ip)) {
                    callback.onDeviceFound(new Device(
                            vendor.equals("Desconocido") ? ("Equipo ." + ip.substring(ip.lastIndexOf('.') + 1)) : vendor,
                            ip, mac, vendor, "OUI DB", vendor));
                }
            }

            callback.onProgress(80, total, total);

            // ── FASE 3: Port scanning + HTTP banner → emitir/actualizar ──
            Log.i(TAG, "🔍 FASE 3: Port scanning en " + foundIps.size() + " hosts");

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
                    Log.i(TAG, "  🔌 " + ip + " → [" + portStr + "]");

                    // HTTP banner
                    if (portStr.contains("80") || portStr.contains("8080") || portStr.contains("443")) {
                        String banner = grabHttpBanner(ip);
                        if (banner != null) {
                            String name = guessType(ip, gateway, portStr);
                            // Si ya tiene mDNS/SSDP, no sobreescribir con banner
                            if (!mdnsNames.containsKey(ip) && !ssdpNames.containsKey(ip)) {
                                callback.onDeviceFound(new Device(banner, ip,
                                        "N/A", "Desconocido", "HTTP", banner));
                            } else {
                                callback.onDeviceFound(new Device(name, ip,
                                        "N/A", "Desconocido", "Heurística",
                                        "Puertos: " + portStr + "  " + banner));
                            }
                        }
                    }
                }

                // Actualizar con info de puertos si no hay mejor nombre
                // (ya se emitió en Fase 1, los demás casos se cubren arriba)

                portScanned++;
                int pct2 = 80 + (portScanned * 10 / Math.max(foundIps.size(), 1));
                callback.onProgress(Math.min(pct2, 90), total + portScanned, total);
            }
            Log.i(TAG, "✅ FASE 3 completada");

            // ── FASE 3.5: NetBIOS → emitir/actualizar ──
            Log.i(TAG, "🔍 FASE 3.5: NetBIOS");
            Set<String> smbIps = new HashSet<>();
            for (String ip : foundIps) {
                // Intentar en IPs sin identificación aún
                if (!mdnsNames.containsKey(ip) && !ssdpNames.containsKey(ip)
                        && !arpTable.containsKey(ip) && !ip.equals(gateway)) {
                    smbIps.add(ip);
                    if (smbIps.size() >= 10) break;
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

            callback.onProgress(100, total, total);

            // ── Liberar MulticastLock ──
            if (multicastLock != null) {
                try { multicastLock.release(); } catch (Exception ignored) {}
            }

            Log.i(TAG, "═══════════════════════════════════════");
            Log.i(TAG, "🏁 ESCANEO COMPLETO: " + foundIps.size() + " hosts");
            Log.i(TAG, "═══════════════════════════════════════");

            callback.onFinished(foundIps.size());
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

    // ════════════════════ HTTP Banner Grab ═══════════════════════

    private String grabHttpBanner(String ip) {
        String result = grabHttpBannerPort(ip, 80);
        if (result != null) return result;
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

    // ════════════════════ Nombrado heurístico ═════════════════════

    private String guessType(String ip, String gateway, String ports) {
        String portInfo = (ports != null) ? ports : "";

        if (ip.equals(gateway)) return "Router / Puerta de enlace";

        if (portInfo.contains("80") || portInfo.contains("443") || portInfo.contains("8080")) {
            if (portInfo.contains("554")) return "Cámara IP";
            if (portInfo.contains("23") || portInfo.contains("22")) return "Servidor / NAS";
            return "Servicio web";
        }
        if (portInfo.contains("445") || portInfo.contains("139")) return "PC / NAS (SMB)";
        if (portInfo.contains("22")) return "Servidor SSH";
        if (portInfo.contains("1883")) return "IoT (MQTT)";
        if (portInfo.contains("554")) return "Cámara IP (RTSP)";
        if (ip.endsWith(".1") || ip.endsWith(".254")) return "Posible router";

        return "Equipo ." + ip.substring(ip.lastIndexOf('.') + 1);
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

    // ════════════════════ IP local ═══════════════════════════════

    private InetAddress getLocalInetAddress(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                WifiInfo info = wifi.getConnectionInfo();
                int ip = info.getIpAddress();
                if (ip != 0) {
                    byte[] addr = new byte[]{
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

    // ════════════════════ ARP — 14 métodos ═══════════════════════

    private Map<String, String> readArpEveryWay() {
        Map<String, String> arp;
        int method = 0;

        method++;
        arp = readArpFile("/proc/net/arp");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("/system/bin/cat /proc/net/arp");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("/bin/cat /proc/net/arp");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("cat /proc/net/arp");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("/system/bin/ip neigh show");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("/system/bin/ip neigh");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("ip neigh show");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("ip neigh");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("/system/bin/arp -a");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("/system/xbin/arp -a");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("arp -a");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("/system/xbin/busybox arp -a");
        if (!arp.isEmpty()) return arp;
        method++;
        arp = runShell("busybox arp -a");

        Log.w(TAG, "⚠️  " + method + " métodos ARP intentados, 0 resultados");
        return arp;
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

    private Map<String, String> runShell(String command) {
        Map<String, String> arp = new HashMap<>();
        try {
            Process p = Runtime.getRuntime().exec(command);
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

    private String detectSubnet(Context context) {
        try {
            WifiManager wifi = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return fallbackSubnet();
            WifiInfo info = wifi.getConnectionInfo();
            int ip = info.getIpAddress();
            if (ip == 0) return fallbackSubnet();
            return String.format("%d.%d.%d.", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff));
        } catch (Exception e) {
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
                    return String.format("%d.%d.%d.%d",
                            (gw & 0xff), (gw >> 8 & 0xff),
                            (gw >> 16 & 0xff), (gw >> 24 & 0xff));
                }
            }
        } catch (Exception ignored) {}
        return detectSubnet(context) + "1";
    }

    private String fallbackSubnet() { return "192.168.1."; }
}
