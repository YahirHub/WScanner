package com.thowilabs.wscanner;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

/**
 * Clasificador offline del sistema operativo probable de un host.
 *
 * Combina tres señales, todas obtenidas sin conexión a Internet:
 * <ol>
 *   <li><b>TTL inicial</b> ({@link TtlProbe}). La mayoría de pilas TCP/IP fijan
 *       un valor inicial estándar: 64 (Linux/Android/macOS/iOS/FreeBSD),
 *       128 (Windows) o 255 (routers/impresoras/IoT/Solaris).</li>
 *   <li><b>Puertos abiertos</b>. 445 (SMB) + 3389 (RDP) es un patrón Windows
 *       muy fuerte; 22 solo → *nix; 62078 → iOS; 631/9100 → impresora;
 *       53+80 en el gateway → router.</li>
 *   <li><b>Banners/servicios declarados</b> (mDNS, HTTP Server, SSH greeting,
 *       SNMP sysDescr) que ya recoge el resto del pipeline. Solo se usa como
 *       refuerzo, nunca como única fuente para no repetir el osHint.</li>
 * </ol>
 *
 * Devuelve una etiqueta legible ("Windows", "Linux / Android", "Router / IoT",
 * "iOS / macOS", …) y una confianza 0-100. Si no hay evidencia suficiente,
 * devuelve {@code null} y quien invoca no muestra el campo.
 */
public final class OsFingerprint {

    public static final class Result {
        public final String label;
        public final int confidence; // 0-100
        Result(String label, int confidence) {
            this.label = label;
            this.confidence = Math.max(0, Math.min(100, confidence));
        }
    }

    private OsFingerprint() {}

    /**
     * @param ttl        TTL observado o -1 si no disponible.
     * @param ports      puertos TCP abiertos.
     * @param services   servicios mDNS/SSDP/SNMP anunciados.
     * @param declared   texto declarado (SSH banner, HTTP Server, sysDescr).
     * @param isGateway  true si la IP coincide con la puerta de enlace.
     */
    public static Result classify(int ttl,
                                  List<Integer> ports,
                                  List<String> services,
                                  String declared,
                                  boolean isGateway) {
        Set<Integer> p = new HashSet<>();
        if (ports != null) p.addAll(ports);
        String signals = joinLower(services, declared);

        // --- Reglas fuertes por texto autoanunciado (máxima confianza). ---
        if (contains(signals, "windows"))     return new Result("Windows", 95);
        if (contains(signals, "darwin", "mac os", "macos"))
            return new Result("macOS", 90);
        if (contains(signals, "ios "))         return new Result("iOS", 85);
        if (contains(signals, "android"))      return new Result("Android", 90);
        if (contains(signals, "openwrt"))      return new Result("OpenWrt (router)", 95);
        if (contains(signals, "mikrotik", "routeros"))
            return new Result("MikroTik RouterOS", 95);
        if (contains(signals, "ubuntu"))       return new Result("Ubuntu Linux", 90);
        if (contains(signals, "debian"))       return new Result("Debian Linux", 90);
        if (contains(signals, "raspbian", "raspberry"))
            return new Result("Raspberry Pi OS", 90);
        if (contains(signals, "synology", "dsm"))
            return new Result("Synology DSM (NAS)", 92);
        if (contains(signals, "qnap", "qts"))  return new Result("QNAP QTS (NAS)", 92);
        if (contains(signals, "freebsd"))      return new Result("FreeBSD", 90);
        if (contains(signals, "linux"))        return new Result("Linux", 80);

        // --- Reglas fuertes por combinación de puertos. ---
        boolean rdp = p.contains(3389);
        boolean smb = p.contains(445) || p.contains(139);
        boolean ssh = p.contains(22);
        boolean ios62078 = p.contains(62078);
        boolean printer = p.contains(9100) || p.contains(631) || p.contains(515);
        boolean airplayCast = p.contains(7000) || p.contains(8009);

        if (rdp && smb)        return blend("Windows", 88, ttl, 128);
        if (rdp)               return blend("Windows", 78, ttl, 128);
        if (ios62078)          return blend("iOS / iPadOS", 85, ttl, 64);
        if (printer && !ssh)   return blend("Firmware de impresora", 82, ttl, 255);
        if (airplayCast && !smb) return blend("tvOS / Chromecast", 78, ttl, 64);

        // --- Router / gateway ---
        if (isGateway) {
            // Los routers domésticos suelen exponer 53 + web admin.
            if (p.contains(53) || p.contains(80) || p.contains(443) || p.contains(8080)) {
                return blend("Router / firmware embebido", 75, ttl, 64);
            }
        }

        // --- Solo TTL: última línea de defensa. ---
        if (ttl > 0) {
            int normalized = normalizeTtl(ttl);
            switch (normalized) {
                case 64:
                    // Ambiguo: Linux, Android, macOS, iOS, muchos IoT modernos.
                    if (ssh) return new Result("Linux / macOS (kernel *nix)", 55);
                    return new Result("Linux / Android / *nix", 45);
                case 128:
                    return new Result("Windows", smb ? 80 : 65);
                case 255:
                    return new Result("Router / IoT / impresora", 60);
                default:
                    return new Result("SO desconocido (TTL " + ttl + ")", 20);
            }
        }

        // --- Sin TTL: heurística débil por puertos. ---
        if (smb)  return new Result("Windows o SMB compartido", 40);
        if (ssh)  return new Result("Linux / *nix", 35);
        return null;
    }

    /**
     * Aproxima el TTL original sumando los saltos habituales (0-30) para
     * decidir cuál de los tres valores base fue el emisor.
     */
    private static int normalizeTtl(int ttl) {
        if (ttl <= 0) return 0;
        if (ttl >= 220) return 255;
        if (ttl >= 100) return 128;
        if (ttl >= 30)  return 64;
        return 64; // TTL muy bajo: asumimos linux con muchos saltos.
    }

    /**
     * Ajusta la confianza si el TTL concuerda con el SO ya inferido por otras señales.
     */
    private static Result blend(String label, int base, int ttl, int expected) {
        if (ttl <= 0) return new Result(label, base);
        int normalized = normalizeTtl(ttl);
        int bonus = (normalized == expected) ? 8 : -10;
        return new Result(label, base + bonus);
    }

    private static String joinLower(List<String> services, String declared) {
        StringBuilder sb = new StringBuilder();
        if (services != null) {
            for (String s : services) {
                if (s != null) sb.append(' ').append(s);
            }
        }
        if (declared != null) sb.append(' ').append(declared);
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String value, String... needles) {
        for (String n : needles) if (value.contains(n)) return true;
        return false;
    }
}
