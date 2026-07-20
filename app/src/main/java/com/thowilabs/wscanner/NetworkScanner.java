package com.thowilabs.wscanner;

import android.annotation.SuppressLint;
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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
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
    private static final int[] DISCOVERY_PORTS = {
            80, 443, 22, 445, 554, 9100, 631, 5357, 62078, 8009, 3389, 32400
    };
    private static final int[] PROBE_PORTS = {
            21, 22, 23, 53, 80, 81, 111, 139, 443, 445, 515, 548, 554, 631,
            1883, 2049, 2869, 3389, 3689, 5000, 5357, 5358, 5900, 62078, 7000,
            8000, 8008, 8009, 8080, 8081, 8443, 8883, 9000, 9100, 10000, 32400
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
        SERVICE_NAMES.put(111, "RPC");
        SERVICE_NAMES.put(2049, "NFS");
        SERVICE_NAMES.put(2869, "UPnP-Event");
        SERVICE_NAMES.put(3689, "DAAP");
        SERVICE_NAMES.put(5357, "WSDAPI");
        SERVICE_NAMES.put(5358, "WSDAPI-TLS");
        SERVICE_NAMES.put(62078, "Apple-Lockdown");
        SERVICE_NAMES.put(7000, "AirPlay");
    }

    /** Retorna el nombre del servicio para un puerto, o null si es desconocido. */
    public static String serviceName(int port) {
        return SERVICE_NAMES.get(port);
    }

    public NetworkInfo getNetworkInfo(Context context) {
        NetworkRange range = detectNetworkRange(context);
        if (range == null) return null;
        String gateway = range.gateway != null ? range.gateway : detectGateway(context);
        return new NetworkInfo(range.localIp, range.prefixLength, gateway, range.cidr());
    }

    public static final class NetworkInfo {
        public final String localIp;
        public final int prefixLength;
        public final String gateway;
        public final String cidr;

        NetworkInfo(String localIp, int prefixLength, String gateway, String cidr) {
            this.localIp = localIp;
            this.prefixLength = prefixLength;
            this.gateway = gateway;
            this.cidr = cidr;
        }
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
                Device localDevice = new Device("Este dispositivo", range.localIp,
                        "N/A", "Desconocido", "Local", range.localIp);
                localDevice.deviceType = "Dispositivo local";
                if (!isCancelled(generation)) callback.onDeviceFound(localDevice);
            }
            if (gateway != null && range.contains(gateway)) {
                foundIps.add(gateway);
                Device gatewayDevice = new Device("Router / Puerta de enlace", gateway,
                        "N/A", "Desconocido", "Gateway", "Puerta de enlace predeterminada");
                gatewayDevice.deviceType = "Router / Puerta de enlace";
                if (!isCancelled(generation)) callback.onDeviceFound(gatewayDevice);
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
                            // Fuerza resolución de vecino L2 de mejor esfuerzo. Incluso si el
                            // equipo bloquea ICMP/TCP, una entrada ARP accesible puede revelarlo.
                            stimulateNeighbor(host, range.networkHandle);
                            if (isHostResponsive(addr, host, range.networkHandle)) {
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
                                Device basic = new Device(name, host,
                                        "N/A", "Desconocido", discoverySource, discoveryDetail);
                                basic.deviceType = DeviceIdentity.inferDeviceType(host, gateway,
                                        java.util.Collections.emptyList(), java.util.Collections.emptyList(), dnsName);
                                if (!isCancelled(generation)) callback.onDeviceFound(basic);
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
            final Map<String, MdnsDiscovery.Result> mdnsDevices = new ConcurrentHashMap<>();
            Thread mdnsThread = new Thread(() -> {
                try {
                    InetAddress localAddr = InetAddress.getByName(range.localIp);
                    if (localAddr != null) {
                        // Service discovery inmediato, no necesita IPs
                        Map<String, MdnsDiscovery.Result> mdns = MdnsDiscovery.discoverServiceDiscoveryDetailed(localAddr);
                        mdnsDevices.putAll(mdns);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error en mDNS: " + e.getMessage());
                }
            });
            mdnsThread.start();

            // ── FASE 1.6: SSDP (paralelo) ──
            final Map<String, SsdpDiscovery.Result> ssdpDevices = new ConcurrentHashMap<>();
            Thread ssdpThread = new Thread(() -> {
                try {
                    Thread.sleep(500);
                    InetAddress localAddr = InetAddress.getByName(range.localIp);
                    Map<String, SsdpDiscovery.Result> ssdp = SsdpDiscovery.discoverDetailed(localAddr, range.networkHandle);
                    ssdpDevices.putAll(ssdp);
                } catch (Exception e) {
                    Log.w(TAG, "Error en SSDP: " + e.getMessage());
                }
            });
            ssdpThread.start();

            // ── FASE 1.7: WS-Discovery (ONVIF, WSD, impresoras, Windows) ──
            final Map<String, WsDiscovery.Result> wsdDevices = new ConcurrentHashMap<>();
            Thread wsdThread = new Thread(() -> {
                try {
                    Thread.sleep(250);
                    InetAddress localAddr = InetAddress.getByName(range.localIp);
                    wsdDevices.putAll(WsDiscovery.discover(localAddr));
                } catch (Exception e) {
                    Log.w(TAG, "Error en WS-Discovery: " + e.getMessage());
                }
            });
            wsdThread.start();

            // ── FASE 1.8: SNMP v2c de mejor esfuerzo ──
            // Puede revelar impresoras, switches, APs y NAS que no publican mDNS/SSDP.
            final Map<String, SnmpDiscovery.Result> snmpDevices = new ConcurrentHashMap<>();
            Thread snmpThread = new Thread(() -> {
                try {
                    snmpDevices.putAll(SnmpDiscovery.discover(hosts, range.networkHandle));
                } catch (Exception e) {
                    Log.w(TAG, "Error en SNMP discovery: " + e.getMessage());
                }
            });
            snmpThread.start();

            // Esperar que termine el ping
            for (Thread w : workers) {
                try { w.join(); } catch (InterruptedException ignored) {}
            }
            Log.i(TAG, "✅ FASE 1 completada: " + foundIps.size() + " hosts vivos");

            if (isCancelled(generation)) {
                releaseMulticastLock(multicastLock);
                return;
            }
            if (!isCancelled(generation)) callback.onProgress(70, total, total);

            // ── FASE 1.5: Esperar mDNS service discovery → emitir/actualizar ──
            try { mdnsThread.join(8000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (isCancelled(generation)) {
                releaseMulticastLock(multicastLock);
                return;
            }
            Log.i(TAG, "🔵 mDNS service discovery: " + mdnsDevices.size() + " dispositivos");
            for (Map.Entry<String, MdnsDiscovery.Result> e : mdnsDevices.entrySet()) {
                String ip = e.getKey();
                MdnsDiscovery.Result result = e.getValue();
                if (range.contains(ip)) {
                    foundIps.add(ip);
                    Device mdnsDevice = new Device(result.displayName(), ip,
                            result.mac != null ? result.mac : "N/A", "Desconocido", "mDNS", result.detail());
                    mdnsDevice.serviceNames.addAll(result.services);
                    mdnsDevice.deviceType = DeviceIdentity.inferDeviceType(ip, gateway,
                            java.util.Collections.emptyList(), result.services,
                            result.displayName() + " " + nullToEmpty(result.model));
                    mdnsDevice.manufacturer = result.manufacturer;
                    mdnsDevice.model = result.model;
                    mdnsDevice.osHint = DeviceIdentity.inferOsHint(
                            nullToEmpty(result.osHint) + " " + nullToEmpty(result.model));
                    if (!isCancelled(generation)) callback.onDeviceFound(mdnsDevice);
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
                        if (range.contains(ip) && !mdnsDevices.containsKey(ip)) {
                            foundIps.add(ip);
                            Device reverseDevice = new Device(e.getValue(), ip,
                                    "N/A", "Desconocido", "mDNS", e.getValue());
                            reverseDevice.deviceType = DeviceIdentity.inferDeviceType(ip, gateway,
                                    java.util.Collections.emptyList(), java.util.Collections.emptyList(), e.getValue());
                            if (!isCancelled(generation)) callback.onDeviceFound(reverseDevice);
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
            Log.i(TAG, "🟢 SSDP: " + ssdpDevices.size() + " dispositivos");
            for (Map.Entry<String, SsdpDiscovery.Result> e : ssdpDevices.entrySet()) {
                String ip = e.getKey();
                SsdpDiscovery.Result result = e.getValue();
                if (range.contains(ip)) {
                    foundIps.add(ip);
                    Device ssdpDevice = new Device(result.displayName(), ip,
                            "N/A", "Desconocido", "SSDP", result.detail());
                    ssdpDevice.deviceType = result.deviceType != null ? result.deviceType
                            : DeviceIdentity.inferDeviceType(ip, gateway,
                            java.util.Collections.emptyList(), result.services, result.displayName());
                    ssdpDevice.manufacturer = result.manufacturer;
                    ssdpDevice.model = result.model;
                    ssdpDevice.osHint = DeviceIdentity.inferOsHint(result.server);
                    ssdpDevice.serviceNames.addAll(result.services);
                    if (!isCancelled(generation)) callback.onDeviceFound(ssdpDevice);
                }
            }

            // ── FASE 1.7: Esperar WS-Discovery ──
            try { wsdThread.join(5000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Log.i(TAG, "🟣 WS-Discovery: " + wsdDevices.size() + " dispositivos");
            for (Map.Entry<String, WsDiscovery.Result> entry : wsdDevices.entrySet()) {
                String ip = entry.getKey();
                WsDiscovery.Result result = entry.getValue();
                if (!range.contains(ip)) continue;
                foundIps.add(ip);
                Device device = new Device(result.name, ip, "N/A", "Desconocido",
                        "WS-Discovery", result.detail());
                device.deviceType = result.deviceType;
                device.model = result.model;
                device.serviceNames.addAll(result.services);
                if (!isCancelled(generation)) callback.onDeviceFound(device);
            }

            // ── FASE 1.8: Esperar SNMP ──
            try { snmpThread.join(5000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Log.i(TAG, "🟠 SNMP: " + snmpDevices.size() + " dispositivos");
            for (Map.Entry<String, SnmpDiscovery.Result> entry : snmpDevices.entrySet()) {
                String ip = entry.getKey();
                SnmpDiscovery.Result result = entry.getValue();
                if (!range.contains(ip)) continue;
                foundIps.add(ip);
                Device device = new Device(result.displayName(), ip, "N/A", "Desconocido",
                        "SNMP", result.detail());
                device.deviceType = result.deviceType;
                device.osHint = DeviceIdentity.inferOsHint(result.sysDescr);
                if (!isCancelled(generation)) callback.onDeviceFound(device);
            }

            if (isCancelled(generation)) {
                releaseMulticastLock(multicastLock);
                return;
            }
            if (!isCancelled(generation)) callback.onProgress(75, total, total);

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
                // La caché ARP puede conservar entradas de equipos ya desconectados.
                // Usarla como prueba de presencia produciría falsos online en el monitor;
                // por eso solo enriquece candidatos confirmados por otra señal del ciclo.
                if (!range.contains(ip) || !foundIps.contains(ip)) continue;
                String mac = e.getValue();
                String vendor = vendorResolver != null ? vendorResolver.resolve(mac) : "Desconocido";
                if (!isCancelled(generation)) {
                    callback.onDeviceFound(new Device(
                            vendor.equals("Desconocido") ? ("Equipo ." + ip.substring(ip.lastIndexOf('.') + 1)) : vendor,
                            ip, mac, vendor, "OUI DB", vendor));
                }
            }

            if (!isCancelled(generation)) callback.onProgress(80, total, total);

            // ── FASE 3: Port scanning + HTTP banner → emitir/actualizar ──
            Log.i(TAG, "🔍 FASE 3: Port scanning en " + foundIps.size() + " hosts");

            List<String> portTargets = new ArrayList<>(foundIps);
            AtomicInteger portScanned = new AtomicInteger(0);
            int portWorkers = Math.min(24, Math.max(1, portTargets.size()));
            ExecutorService portExecutor = Executors.newFixedThreadPool(portWorkers);
            for (String ip : portTargets) {
                portExecutor.execute(() -> {
                    if (!isCancelled(generation)) {
                        scanPortsForHost(ip, gateway, mdnsDevices, ssdpDevices,
                                range.networkHandle, generation, callback);
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
                if (!mdnsDevices.containsKey(ip) && !ssdpDevices.containsKey(ip)
                        && !ip.equals(gateway) && !ip.equals(range.localIp)) {
                    smbIps.add(ip);
                }
            }
            Map<String, NetBiosDiscovery.Result> netbiosResults =
                    NetBiosDiscovery.discoverDetailed(smbIps, range.networkHandle);
            boolean netbiosHasMac = false;
            for (NetBiosDiscovery.Result result : netbiosResults.values()) {
                if (result.mac != null) {
                    netbiosHasMac = true;
                    break;
                }
            }
            if (netbiosHasMac && vendorResolver == null) {
                vendorResolver = new VendorResolver();
                vendorResolver.load(context);
            }
            for (Map.Entry<String, NetBiosDiscovery.Result> e : netbiosResults.entrySet()) {
                String ip = e.getKey();
                NetBiosDiscovery.Result result = e.getValue();
                if (foundIps.contains(ip)) {
                    String vendor = result.mac != null && vendorResolver != null
                            ? vendorResolver.resolve(result.mac) : "Desconocido";
                    Device netbiosDevice = new Device(result.name, ip,
                            result.mac != null ? result.mac : "N/A", vendor,
                            "NetBIOS", result.detail());
                    netbiosDevice.deviceType = "PC / NAS";
                    if (!isCancelled(generation)) callback.onDeviceFound(netbiosDevice);
                }
            }
            Log.i(TAG, "   NetBIOS: " + netbiosResults.size() + " respuestas");

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

    // ════════════════════ HTTP fingerprint local ════════════════════

    private HttpFingerprint grabHttpFingerprint(String ip, List<Integer> openPorts, Network network) {
        int[] httpPorts = {80, 81, 8000, 8008, 8080, 8081};
        HttpFingerprint best = null;
        for (int port : httpPorts) {
            if (!openPorts.contains(port)) continue;
            HttpFingerprint candidate = grabHttpFingerprintPort(ip, port, network);
            if (candidate == null) continue;
            if (best == null || (best.title == null && candidate.title != null)) best = candidate;
            if (candidate.title != null) break;
        }
        return best;
    }

    private boolean hasHttpPort(List<Integer> openPorts) {
        int[] httpPorts = {80, 81, 8000, 8008, 8080, 8081};
        for (int port : httpPorts) if (openPorts.contains(port)) return true;
        return false;
    }

    private HttpFingerprint grabHttpFingerprintPort(String ip, int port, Network network) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":" + port + "/");
            conn = (HttpURLConnection) (network != null
                    ? network.openConnection(url) : url.openConnection());
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "WScanner/1.0");
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            String server = cleanHeader(conn.getHeaderField("Server"));
            String realm = extractAuthRealm(conn.getHeaderField("WWW-Authenticate"));
            String contentType = conn.getHeaderField("Content-Type");
            String title = null;

            if (contentType != null && contentType.toLowerCase().contains("html")
                    && code >= 200 && code < 400) {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder html = new StringBuilder();
                    String line;
                    int lines = 0;
                    while ((line = reader.readLine()) != null && lines < 60 && html.length() < 32768) {
                        html.append(line);
                        lines++;
                    }
                    reader.close();
                    title = extractTitle(html.toString());
                } catch (Exception ignored) {}
            }
            if (title == null && server == null && realm == null) return null;
            return new HttpFingerprint(port, title, server, realm);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
        }
    }

    private String cleanHeader(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }


    private String extractAuthRealm(String authHeader) {
        if (authHeader == null) return null;
        Matcher matcher = Pattern.compile("(?i)realm\\s*=\\s*[\"']?([^\"',]+)").matcher(authHeader);
        if (!matcher.find()) return null;
        String value = cleanHeader(matcher.group(1));
        if (value == null) return null;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("authentication required") || lower.equals("restricted")
                || lower.equals("login") || lower.equals("web server") || lower.equals("default")) {
            return null;
        }
        return value;
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
                                  Map<String, ?> mdnsDevices,
                                  Map<String, ?> ssdpDevices,
                                  Network network,
                                  long generation,
                                  Callback callback) {
        if (isCancelled(generation)) return;
        StringBuilder ports = new StringBuilder();
        List<Integer> openPortsList = new ArrayList<>();
        List<String> serviceNamesList = new ArrayList<>();

        for (int port : PROBE_PORTS) {
            if (isPortOpen(ip, port, 120, network)) {
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
        portDevice.deviceType = DeviceIdentity.inferDeviceType(ip, gateway,
                openPortsList, serviceNamesList, null);

        if (hasHttpPort(openPortsList)) {
            HttpFingerprint http = grabHttpFingerprint(ip, openPortsList, network);
            if (http != null) {
                String detail = http.detail();
                if (detail != null) portDevice.discoveryDetail += " · " + detail;

                // Un header Server como "lighttpd" identifica software, no al equipo.
                // Solo un <title> humano puede competir como nombre de dispositivo.
                String httpIdentity = http.title != null ? http.title : http.realm;
                if (httpIdentity != null && !mdnsDevices.containsKey(ip) && !ssdpDevices.containsKey(ip)) {
                    Device httpDevice = new Device(httpIdentity, ip, "N/A", "Desconocido",
                            "HTTP", detail);
                    httpDevice.openPorts = new ArrayList<>(openPortsList);
                    httpDevice.serviceNames = new ArrayList<>(serviceNamesList);
                    httpDevice.deviceType = portDevice.deviceType;
                    httpDevice.osHint = DeviceIdentity.inferOsHint(http.server);
                    if (!isCancelled(generation)) callback.onDeviceFound(httpDevice);
                }
            }
        }

        if (openPortsList.contains(443) || openPortsList.contains(8443)) {
            int tlsPort = openPortsList.contains(443) ? 443 : 8443;
            TlsFingerprint tls = probeTlsFingerprint(ip, tlsPort, network);
            if (tls != null) {
                portDevice.discoveryDetail += " · " + tls.detail();
                if (tls.bestName != null && !mdnsDevices.containsKey(ip) && !ssdpDevices.containsKey(ip)) {
                    Device tlsDevice = new Device(tls.bestName, ip, "N/A", "Desconocido",
                            "TLS", tls.detail());
                    tlsDevice.openPorts = new ArrayList<>(openPortsList);
                    tlsDevice.serviceNames = new ArrayList<>(serviceNamesList);
                    tlsDevice.deviceType = portDevice.deviceType;
                    if (!isCancelled(generation)) callback.onDeviceFound(tlsDevice);
                }
            }
        }

        String protocolDetail = probeProtocolDetails(ip, openPortsList, network);
        if (protocolDetail != null) {
            portDevice.discoveryDetail += " · " + protocolDetail;
            portDevice.osHint = DeviceIdentity.inferOsHint(protocolDetail);
        }
        if (!isCancelled(generation)) callback.onDeviceFound(portDevice);
    }

    private String probeProtocolDetails(String ip, List<Integer> ports, Network network) {
        List<String> details = new ArrayList<>();
        if (ports.contains(22)) {
            String banner = readGreeting(ip, 22, network);
            if (banner != null) details.add("SSH: " + banner);
        }
        if (ports.contains(21)) {
            String banner = readGreeting(ip, 21, network);
            if (banner != null) details.add("FTP: " + banner);
        }
        if (ports.contains(554)) {
            String server = probeRtspServer(ip, network);
            if (server != null) details.add("RTSP: " + server);
        }
        return details.isEmpty() ? null : String.join(" · ", details);
    }

    private String readGreeting(String ip, int port, Network network) {
        try (Socket socket = new Socket()) {
            if (network != null) network.bindSocket(socket);
            socket.connect(new InetSocketAddress(ip, port), 500);
            socket.setSoTimeout(500);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String line = reader.readLine();
            if (line == null) return null;
            line = line.trim().replaceAll("\\s+", " ");
            return line.length() > 120 ? line.substring(0, 120) : line;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String probeRtspServer(String ip, Network network) {
        try (Socket socket = new Socket()) {
            if (network != null) network.bindSocket(socket);
            socket.connect(new InetSocketAddress(ip, 554), 600);
            socket.setSoTimeout(700);
            String request = "OPTIONS rtsp://" + ip + "/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: WScanner/1.0\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String line;
            for (int i = 0; i < 20 && (line = reader.readLine()) != null; i++) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Server")) {
                    return cleanHeader(line.substring(colon + 1));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private TlsFingerprint probeTlsFingerprint(String ip, int port, Network network) {
        Socket raw = null;
        try {
            raw = new Socket();
            if (network != null) network.bindSocket(raw);
            raw.connect(new InetSocketAddress(ip, port), 800);
            raw.setSoTimeout(1000);

            CapturingTrustManager capture = new CapturingTrustManager(defaultTrustManager());
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{capture}, new SecureRandom());
            SSLSocketFactory factory = sslContext.getSocketFactory();
            try (SSLSocket ssl = (SSLSocket) factory.createSocket(raw, ip, port, true)) {
                raw = null; // ownership transferred to SSLSocket
                ssl.setSoTimeout(1000);

                X509Certificate cert = null;
                try {
                    ssl.startHandshake();
                    java.security.cert.Certificate[] peer = ssl.getSession().getPeerCertificates();
                    if (peer.length > 0 && peer[0] instanceof X509Certificate) {
                        cert = (X509Certificate) peer[0];
                    }
                } catch (Exception handshakeError) {
                    // Un certificado local autofirmado puede no ser confiable para el
                    // sistema. El trust manager captura la cadena antes de delegar la
                    // validación normal; no acepta ni establece confianza global.
                    cert = capture.firstServerCertificate();
                }
                if (cert == null) return null;

                String subject = cleanHeader(cert.getSubjectX500Principal().getName());
                String bestName = firstDnsSan(cert);
                if (bestName == null) bestName = extractCommonName(subject);
                if (!isUsefulTlsName(bestName, ip)) bestName = null;
                return new TlsFingerprint(port, bestName, subject);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (raw != null) try { raw.close(); } catch (Exception ignored) {}
        }
    }

    private X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        }
        throw new IllegalStateException("No X509TrustManager disponible");
    }


    private String firstDnsSan(X509Certificate cert) {
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) return null;
            for (List<?> san : sans) {
                if (san == null || san.size() < 2 || !(san.get(0) instanceof Integer)) continue;
                if (((Integer) san.get(0)) == 2 && san.get(1) != null) {
                    String value = String.valueOf(san.get(1)).trim();
                    if (isUsefulTlsName(value, null)) return value;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractCommonName(String subject) {
        if (subject == null) return null;
        Matcher matcher = Pattern.compile("(?i)(?:^|,)\\s*CN=([^,]+)").matcher(subject);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private boolean isUsefulTlsName(String value, String ip) {
        if (value == null) return false;
        String clean = value.trim();
        if (clean.isEmpty() || clean.equalsIgnoreCase("localhost") || clean.equals(ip)
                || clean.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) return false;
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        return !lower.equals("default") && !lower.equals("server") && !lower.equals("device")
                && !lower.startsWith("selfsigned") && clean.length() <= 120;
    }

    @SuppressLint("CustomX509TrustManager")
    private static final class CapturingTrustManager implements X509TrustManager {
        private final X509TrustManager delegate;
        private volatile X509Certificate[] lastServerChain;

        CapturingTrustManager(X509TrustManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            lastServerChain = chain == null ? null : chain.clone();
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }

        X509Certificate firstServerCertificate() {
            X509Certificate[] chain = lastServerChain;
            return chain != null && chain.length > 0 ? chain[0] : null;
        }
    }

    private static final class HttpFingerprint {
        final int port;
        final String title;
        final String server;
        final String realm;

        HttpFingerprint(int port, String title, String server, String realm) {
            this.port = port;
            this.title = title;
            this.server = server;
            this.realm = realm;
        }

        String detail() {
            StringBuilder value = new StringBuilder("HTTP ").append(port);
            if (title != null) value.append(" título=\"").append(title).append("\"");
            if (realm != null) value.append(" realm=\"").append(realm).append("\"");
            if (server != null) value.append(" server=\"").append(server).append("\"");
            return value.toString();
        }
    }

    private static final class TlsFingerprint {
        final int port;
        final String bestName;
        final String subject;

        TlsFingerprint(int port, String bestName, String subject) {
            this.port = port;
            this.bestName = bestName;
            this.subject = subject;
        }

        String detail() {
            StringBuilder value = new StringBuilder("TLS ").append(port);
            if (bestName != null) value.append(" nombre=\"").append(bestName).append("\"");
            if (subject != null) value.append(" sujeto=\"").append(subject).append("\"");
            return value.toString();
        }
    }

    private boolean isHostResponsive(InetAddress address, String ip, Network network) {
        try {
            if (address.isReachable(180)) return true;
        } catch (Exception ignored) {}

        // Muchos equipos bloquean ICMP. Un servicio TCP abierto sigue siendo una señal
        // válida de presencia y evita perder cámaras, impresoras, NAS y dispositivos IoT.
        for (int port : DISCOVERY_PORTS) {
            if (isPortOpen(ip, port, 70, network)) return true;
        }
        return false;
    }

    /**
     * Envía un datagrama UDP vacío para provocar resolución ARP/neighbor en la LAN.
     * No se considera una respuesta por sí misma; solo ayuda a poblar la caché local
     * que Android permita leer posteriormente.
     */
    private void stimulateNeighbor(String ip, Network network) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.bind(new InetSocketAddress(0));
            if (network != null) network.bindSocket(socket);
            byte[] empty = new byte[0];
            DatagramPacket packet = new DatagramPacket(empty, 0,
                    InetAddress.getByName(ip), 65534);
            socket.send(packet);
        } catch (Exception ignored) {}
    }

    // ════════════════════ Port scanning ══════════════════════════

    private boolean isPortOpen(String ip, int port, int timeoutMs, Network network) {
        try (Socket socket = new Socket()) {
            if (network != null) network.bindSocket(socket);
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
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
                        link.getPrefixLength(), gateway, network);
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
        final Network networkHandle;
        final long network;
        final long broadcast;

        NetworkRange(String localIp, int prefixLength, String gateway) {
            this(localIp, prefixLength, gateway, null);
        }

        NetworkRange(String localIp, int prefixLength, String gateway, Network networkHandle) {
            this.localIp = localIp;
            this.prefixLength = Math.max(0, Math.min(32, prefixLength));
            this.gateway = gateway;
            this.networkHandle = networkHandle;
            long ip = ipv4ToLong(localIp);
            long mask = this.prefixLength == 0 ? 0 : (0xFFFFFFFFL << (32 - this.prefixLength)) & 0xFFFFFFFFL;
            this.network = ip & mask;
            this.broadcast = this.network | (~mask & 0xFFFFFFFFL);
        }

        String cidr() {
            return longToIpv4(network) + "/" + prefixLength;
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
