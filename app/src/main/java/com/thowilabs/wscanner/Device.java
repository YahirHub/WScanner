package com.thowilabs.wscanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Device {
    public String name;
    public String ip;
    public String mac;
    public String vendor;
    public String discoveryMethod;   // "mDNS", "SSDP", "NetBIOS", "DNS", "HTTP", "Heurística", "OUI DB"
    public String discoveryDetail;  // Valor crudo encontrado (ej: "iPhone-de-Juan.local")
    public int ttl = -1;            // TTL del ping (-1 = no disponible, requiere raw sockets)
    public List<Integer> openPorts = new ArrayList<>();
    public List<String> serviceNames = new ArrayList<>();

    public Device(String name, String ip, String mac, String vendor) {
        this(name, ip, mac, vendor, "Heurística", null);
    }

    public Device(String name, String ip, String mac, String vendor,
                  String discoveryMethod, String discoveryDetail) {
        this.name = name;
        this.ip = ip;
        this.mac = mac;
        this.vendor = vendor;
        this.discoveryMethod = discoveryMethod;
        this.discoveryDetail = discoveryDetail;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Device device = (Device) o;
        return Objects.equals(ip, device.ip);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip);
    }
}
