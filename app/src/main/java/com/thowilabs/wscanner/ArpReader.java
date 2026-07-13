package com.thowilabs.wscanner;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Lee la tabla ARP del sistema (/proc/net/arp) para obtener
 * direcciones MAC de los dispositivos en la red local.
 *
 * NOTA: Esta clase es independiente por compatibilidad,
 * pero NetworkScanner tiene su propia implementación más robusta.
 */
public class ArpReader {

    private static final String TAG = "WScanner.ArpReader";

    /**
     * Parsea /proc/net/arp y devuelve un mapa IP → MAC.
     */
    public static Map<String, String> readArpTable() {
        Map<String, String> arp = new HashMap<>();
        Log.d(TAG, "Intentando leer /proc/net/arp...");
        try (BufferedReader br = new BufferedReader(
                new FileReader("/proc/net/arp"))) {

            String line;
            boolean header = true;
            int count = 0;
            while ((line = br.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                line = line.trim();
                if (line.isEmpty()) continue;
                count++;

                // Formato: IP       HWtype  Flags   HWaddress       Mask    Device
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    String ip = parts[0];
                    String mac = parts[3];
                    String flags = parts[2];

                    // 0x2 = reachable/completa, solo dispositivos activos
                    if (!"00:00:00:00:00:00".equals(mac) && mac.length() == 17) {
                        arp.put(ip, mac.toUpperCase());
                        Log.d(TAG, "  ✓ " + ip + " → " + mac.toUpperCase());
                    }
                }
            }
            Log.i(TAG, "ARP table leída: " + count + " líneas, " + arp.size() + " entradas");
        } catch (Exception e) {
            Log.w(TAG, "No se pudo leer /proc/net/arp: " + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
        return arp;
    }
}
