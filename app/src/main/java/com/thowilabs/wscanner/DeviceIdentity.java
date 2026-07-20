package com.thowilabs.wscanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Política central para fusionar observaciones de un mismo dispositivo.
 *
 * No depende de fabricantes ni de diccionarios externos: prioriza la calidad de
 * la señal observada (mDNS, SSDP, WS-Discovery, SNMP, NetBIOS, DNS, TLS, HTTP y puertos) y conserva la
 * información complementaria obtenida por otras capas.
 */
public final class DeviceIdentity {

    private DeviceIdentity() {}

    public static int sourceRank(String source) {
        if (source == null) return 0;
        switch (source) {
            case "Local":         return 100;
            case "Gateway":       return 98;
            case "mDNS":          return 90;
            case "WS-Discovery":  return 88;
            case "SSDP":          return 85;
            case "SNMP":          return 82;
            case "NetBIOS":       return 80;
            case "DNS":           return 70;
            case "TLS":           return 65;
            case "HTTP":          return 60;
            case "OUI DB":        return 50;
            case "TCP":           return 30;
            case "Heurística":
            default:           return 10;
        }
    }

    /**
     * Fusiona una observación nueva sin perder datos útiles ya descubiertos.
     */
    public static void mergeInto(Device existing, Device incoming) {
        if (existing == null || incoming == null) return;

        existing.online = existing.online || incoming.online;
        existing.lastSeen = Math.max(existing.lastSeen, incoming.lastSeen);

        mergePorts(existing.openPorts, incoming.openPorts);
        mergeStrings(existing.serviceNames, incoming.serviceNames);

        if (isUsefulMac(incoming.mac) && !isUsefulMac(existing.mac)) {
            existing.mac = incoming.mac;
        }
        if (isUsefulVendor(incoming.vendor) && !isUsefulVendor(existing.vendor)) {
            existing.vendor = incoming.vendor;
        }
        if (isUsefulMetadata(incoming.manufacturer) && !isUsefulMetadata(existing.manufacturer)) {
            existing.manufacturer = incoming.manufacturer;
        }
        if (isUsefulMetadata(incoming.model) && !isUsefulMetadata(existing.model)) {
            existing.model = incoming.model;
        }
        if (isUsefulMetadata(incoming.osHint) && !isUsefulMetadata(existing.osHint)) {
            existing.osHint = incoming.osHint;
        }
        if (shouldReplaceDeviceType(existing.deviceType, incoming.deviceType)) {
            existing.deviceType = incoming.deviceType;
        }

        String mergedDetail = mergeDetail(existing.discoveryDetail, incoming.discoveryDetail);
        if (shouldReplaceIdentity(existing, incoming)) {
            existing.name = incoming.name;
            existing.discoveryMethod = incoming.discoveryMethod;
        }
        existing.discoveryDetail = mergedDetail;
    }

    private static String mergeDetail(String existing, String incoming) {
        String first = normalizeDetail(existing);
        String second = normalizeDetail(incoming);
        if (first == null) return second;
        if (second == null || first.contains(second)) return first;
        if (second.contains(first)) return second;
        String merged = first + "\n" + second;
        // Evita crecimiento sin límite durante el monitor continuo si las señales
        // cambian ligeramente entre ciclos.
        return merged.length() > 1600 ? merged.substring(0, 1600) : merged;
    }

    private static String normalizeDetail(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    public static boolean shouldReplaceIdentity(Device existing, Device incoming) {
        return identityScore(incoming) > identityScore(existing);
    }

    static int identityScore(Device device) {
        if (device == null) return 0;
        int score = sourceRank(device.discoveryMethod);
        String name = normalize(device.name);

        if (name.isEmpty()) return score - 80;
        if (looksLikeIpv4(name)) score -= 80;
        if (isGenericName(name)) score -= 35;
        if (name.length() >= 4 && name.length() <= 64) score += 8;
        if (name.indexOf(' ') >= 0 || name.indexOf('-') >= 0 || name.indexOf('_') >= 0) score += 3;

        return score;
    }

    /**
     * Clasificación genérica basada exclusivamente en señales observables.
     * No intenta adivinar fabricantes.
     */
    public static String classifyBySignals(String ip, String gateway,
                                           List<Integer> ports, List<String> services) {
        String type = inferDeviceType(ip, gateway, ports, services, null);
        if (type != null) return type;
        if (ip != null && ip.contains(".")) {
            return "Equipo ." + ip.substring(ip.lastIndexOf('.') + 1);
        }
        return "Dispositivo de red";
    }

    /**
     * Infiere una categoría únicamente con protocolos, puertos y texto declarado
     * por el propio equipo. No usa una tabla de fabricantes/modelos.
     */
    public static String inferDeviceType(String ip, String gateway,
                                         List<Integer> ports, List<String> services,
                                         String declaredText) {
        Set<Integer> p = new LinkedHashSet<>();
        if (ports != null) p.addAll(ports);
        String joinedServices = services == null ? "" : String.join(" ", services);
        String signals = (joinedServices + " " + (declaredText == null ? "" : declaredText))
                .toLowerCase(Locale.ROOT);

        if (ip != null && ip.equals(gateway)) return "Router / Puerta de enlace";
        if (containsAny(signals, "networkvideotransmitter", "onvif", "digital_security_camera",
                "ip camera", "network camera")) return "Cámara / dispositivo de video";
        // RTSP por sí solo no demuestra que sea una cámara: también lo usan NVR,
        // servidores multimedia y otros equipos. Mantener una categoría neutral
        // reduce falsos positivos sin perder la señal observada.
        if (containsAny(signals, "_rtsp._tcp") || p.contains(554)) return "Dispositivo RTSP / video";
        if (containsAny(signals, "_ipp._tcp", "_ipps._tcp", "_printer._tcp", "printer", "printbasic")
                || p.contains(631) || p.contains(515) || p.contains(9100)) return "Impresora";
        if (containsAny(signals, "_scanner._tcp", "scanner")) return "Escáner";
        if (containsAny(signals, "_googlecast._tcp", "_airplay._tcp", "_raop._tcp",
                "mediarenderer", "dial-multiscreen", "_spotify-connect._tcp")
                || p.contains(7000) || p.contains(8009)) return "Reproductor multimedia";
        if (containsAny(signals, "mediaserver") || p.contains(32400)) return "Servidor multimedia";
        if (containsAny(signals, "internetgatewaydevice", "wlanaccesspoint")) return "Infraestructura de red";
        if (containsAny(signals, "_smb._tcp", "_workstation._tcp")
                || p.contains(445) || p.contains(139)) return "PC / NAS";
        if (containsAny(signals, "_hap._tcp", "_homekit._tcp") || p.contains(1883) || p.contains(8883))
            return "Dispositivo IoT / domótica";
        if (containsAny(signals, "_adb-tls-connect._tcp", "_adb-tls-pairing._tcp")) return "Dispositivo Android";
        if (containsAny(signals, "_androidtvremote2._tcp")) return "Android TV";
        if (p.contains(62078)) return "Dispositivo Apple / sincronización";
        if (p.contains(3389)) return "PC con Escritorio remoto";
        if (p.contains(5900)) return "Equipo con VNC";
        if (p.contains(2049) || p.contains(111)) return "Servidor / NAS";
        if (p.contains(22)) return "Servidor / equipo SSH";
        if (p.contains(53)) return "Servidor DNS / infraestructura de red";
        if (p.contains(5357) || p.contains(5358)) return "Dispositivo WS-Discovery";
        if (hasAny(p, 80, 81, 443, 8000, 8008, 8080, 8081, 8443)) return "Dispositivo con interfaz web";
        return null;
    }

    /**
     * Extrae una pista de sistema operativo únicamente de texto autoanunciado
     * por protocolos/banners locales. No intenta inferir una versión por fabricante.
     */
    public static String inferOsHint(String declaredText) {
        if (declaredText == null) return null;
        String value = declaredText.toLowerCase(Locale.ROOT);
        if (value.contains("android")) return "Android";
        if (value.contains("windows")) return "Windows";
        if (value.contains("darwin") || value.contains("mac os") || value.contains("macos")) return "macOS / Darwin";
        if (value.contains("ios ") || value.startsWith("ios")) return "iOS";
        if (value.contains("openwrt")) return "OpenWrt";
        if (value.contains("freebsd")) return "FreeBSD";
        if (value.contains("ubuntu")) return "Ubuntu / Linux";
        if (value.contains("debian")) return "Debian / Linux";
        if (value.contains("linux")) return "Linux";
        return null;
    }

    private static void mergePorts(List<Integer> target, List<Integer> source) {
        if (source == null || source.isEmpty()) return;
        Set<Integer> values = new LinkedHashSet<>(target);
        values.addAll(source);
        target.clear();
        target.addAll(values);
        Collections.sort(target);
    }

    private static void mergeStrings(List<String> target, List<String> source) {
        if (source == null || source.isEmpty()) return;
        Set<String> values = new LinkedHashSet<>(target);
        for (String item : source) {
            if (item != null && !item.trim().isEmpty()) values.add(item.trim());
        }
        target.clear();
        target.addAll(new ArrayList<>(values));
    }

    private static boolean isUsefulMac(String mac) {
        return mac != null && !mac.isEmpty() && !"N/A".equalsIgnoreCase(mac)
                && !"00:00:00:00:00:00".equals(mac);
    }

    private static boolean isUsefulVendor(String vendor) {
        return vendor != null && !vendor.isEmpty() && !"Desconocido".equalsIgnoreCase(vendor);
    }

    private static boolean isUsefulMetadata(String value) {
        return value != null && !value.trim().isEmpty() && !"Desconocido".equalsIgnoreCase(value.trim());
    }

    private static boolean shouldReplaceDeviceType(String existing, String incoming) {
        if (!isUsefulMetadata(incoming)) return false;
        if (!isUsefulMetadata(existing)) return true;
        return typeScore(incoming) > typeScore(existing);
    }

    private static int typeScore(String type) {
        if (type == null) return 0;
        String value = type.toLowerCase(Locale.ROOT);
        if (value.contains("dispositivo con interfaz web") || value.contains("ws-discovery")) return 10;
        if (value.contains("servidor / equipo ssh")) return 20;
        if (value.contains("rtsp / video")) return 30;
        if (value.contains("pc / nas") || value.contains("servidor / nas")
                || value.contains("vnc") || value.contains("escritorio remoto")) return 55;
        if (value.contains("reproductor multimedia") || value.contains("servidor multimedia")
                || value.contains("android") || value.contains("apple")
                || value.contains("iot") || value.contains("domótica")) return 65;
        if (value.contains("infraestructura") || value.contains("punto de acceso")
                || value.contains("router") || value.contains("puerta de enlace")) return 80;
        if (value.contains("cámara") || value.contains("impresora") || value.contains("escáner")) return 90;
        return 45;
    }

    private static boolean isGenericName(String value) {
        String v = normalize(value).toLowerCase(Locale.ROOT);
        return v.startsWith("equipo .")
                || v.equals("dispositivo de red")
                || v.equals("servicio web")
                || v.equals("dispositivo upnp")
                || v.equals("dispositivo mdns")
                || v.equals("dispositivo snmp")
                || v.equals("dispositivo ws-discovery")
                || v.equals("localhost")
                || v.startsWith("posible router")
                || v.equals("desconocido")
                || v.equals("unknown")
                || v.equals("device");
    }

    private static boolean looksLikeIpv4(String value) {
        return value.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static boolean hasAny(Set<Integer> values, int... needles) {
        for (int needle : needles) {
            if (values.contains(needle)) return true;
        }
        return false;
    }
}
