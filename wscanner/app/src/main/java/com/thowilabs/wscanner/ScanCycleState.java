package com.thowilabs.wscanner;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mantiene el estado transitorio de un ciclo de escaneo.
 *
 * Los dispositivos no se marcan offline al iniciar un ciclo. Permanecen con su
 * último estado conocido mientras el barrido está en curso y solo se consideran
 * ausentes cuando el ciclo completo termina sin volver a observarlos.
 */
public final class ScanCycleState {

    private final Set<String> seenIps = new HashSet<>();

    public void beginCycle() {
        seenIps.clear();
    }

    public void markSeen(String ip) {
        if (ip != null && !ip.trim().isEmpty()) {
            seenIps.add(ip.trim());
        }
    }

    public boolean wasSeen(String ip) {
        return ip != null && seenIps.contains(ip);
    }

    /**
     * Aplica el resultado final del ciclo al inventario conocido.
     *
     * @return cantidad de dispositivos observados en este ciclo.
     */
    public int finishCycle(List<Device> devices) {
        int online = 0;
        if (devices == null) return 0;

        for (Device device : devices) {
            boolean seen = device != null && wasSeen(device.ip);
            if (device != null) {
                device.online = seen;
                if (seen) online++;
            }
        }
        return online;
    }

    public int seenCount() {
        return seenIps.size();
    }
}
