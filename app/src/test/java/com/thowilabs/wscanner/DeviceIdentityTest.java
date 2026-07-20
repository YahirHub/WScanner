package com.thowilabs.wscanner;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DeviceIdentityTest {

    @Test
    public void mergeKeepsBestIdentityAndComplementsPorts() {
        Device existing = new Device("Equipo .20", "192.168.1.20", "N/A", "Desconocido",
                "Heurística", null);
        existing.openPorts.add(80);

        Device incoming = new Device("Sala TV", "192.168.1.20", "N/A", "Desconocido",
                "mDNS", "Sala TV._googlecast._tcp");
        incoming.openPorts.add(8008);
        incoming.serviceNames.add("_googlecast._tcp");

        DeviceIdentity.mergeInto(existing, incoming);

        assertEquals("Sala TV", existing.name);
        assertEquals("mDNS", existing.discoveryMethod);
        assertEquals(Arrays.asList(80, 8008), existing.openPorts);
        assertTrue(existing.serviceNames.contains("_googlecast._tcp"));
    }

    @Test
    public void genericHigherSourceDoesNotBeatSpecificUsefulName() {
        Device existing = new Device("NAS Oficina", "192.168.1.30", "N/A", "Desconocido",
                "NetBIOS", "NAS Oficina");
        Device incoming = new Device("Dispositivo de red", "192.168.1.30", "N/A", "Desconocido",
                "mDNS", "Dispositivo de red");

        DeviceIdentity.mergeInto(existing, incoming);

        assertEquals("NAS Oficina", existing.name);
        assertEquals("NetBIOS", existing.discoveryMethod);
    }

    @Test
    public void classifiesPrinterFromObservedPorts() {
        assertEquals("Impresora", DeviceIdentity.classifyBySignals(
                "192.168.1.50", "192.168.1.1", Arrays.asList(80, 9100), Arrays.asList("HTTP", "RAW-Print")));
    }
}
