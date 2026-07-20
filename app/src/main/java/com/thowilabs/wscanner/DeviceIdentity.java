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
 * la señal observada (mDNS, SSDP, NetBIOS, DNS, HTTP y puertos) y conserva la
 * información complementaria obtenida por otras capas.
 */
public final class DeviceIdentity {

    private DeviceIdentity() {}

    public static int sourceRank(String source) {
        if (source == null) return 0;
        switch (source) {
            case "Local":     return 100;
            case "mDNS":      return 90;
            case "SSDP":      return 85;
            case "NetBIOS":   return 80;
            case "DNS":       return 70;
            case "HTTP":      return 60;
            case "OUI DB":    return 50;
            case "TCP":       return 30;
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

        if (shouldReplaceIdentity(existing, incoming)) {
            existing.name = incoming.name;
            existing.discoveryMethod = incoming.discoveryMethod;
            existing.discoveryDetail = incoming.discoveryDetail;
        } else if ((existing.discoveryDetail == null || existing.discoveryDetail.isEmpty())
                && incoming.discoveryDetail != null && !incoming.discoveryDetail.isEmpty()) {
            existing.discoveryDetail = incoming.discoveryDetail;
        }
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
        Set<Integer> p = new LinkedHashSet<>();
        if (ports != null) p.addAll(ports);

        String joinedServices = services == null
                ? ""
                : String.join(" ", services).toLowerCase(Locale.ROOT);

        if (ip != null && ip.equals(gateway)) return "Router / Puerta de enlace";
        if (containsAny(joinedServices, "_ipp._tcp", "_ipps._tcp", "_printer._tcp")
                || p.contains(631) || p.contains(515) || p.contains(9100)) {
            return "Impresora";
        }
        if (containsAny(joinedServices, "_googlecast._tcp", "_airplay._tcp", "_raop._tcp")) {
            return "Reproductor multimedia";
        }
        if (containsAny(joinedServices, "_smb._tcp", "_workstation._tcp")
                || p.contains(445) || p.contains(139)) {
            return "PC / NAS";
        }
        if (p.contains(554)) return "Cámara / dispositivo RTSP";
        if (p.contains(1883) || p.contains(8883)) return "Dispositivo IoT / MQTT";
        if (p.contains(3389)) return "PC con Escritorio remoto";
        if (p.contains(5900)) return "Equipo con VNC";
        if (p.contains(22)) return "Servidor / equipo SSH";
        if (p.contains(53)) return "Servidor DNS / infraestructura de red";
        if (hasAny(p, 80, 81, 443, 8000, 8008, 8080, 8081, 8443)) return "Dispositivo con interfaz web";

        if (ip != null && ip.contains(".")) {
            return "Equipo ." + ip.substring(ip.lastIndexOf('.') + 1);
        }
        return "Dispositivo de red";
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

    private static boolean isGenericName(String value) {
        String v = normalize(value).toLowerCase(Locale.ROOT);
        return v.startsWith("equipo .")
                || v.equals("dispositivo de red")
                || v.equals("servicio web")
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
