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
    @Test
    public void gatewayIdentityIsNotReplacedByHttpSoftwareName() {
        Device gateway = new Device("Router / Puerta de enlace", "192.168.1.1",
                "N/A", "Desconocido", "Gateway", "default route");
        Device http = new Device("lighttpd", "192.168.1.1",
                "N/A", "Desconocido", "HTTP", "Server: lighttpd");

        DeviceIdentity.mergeInto(gateway, http);

        assertEquals("Router / Puerta de enlace", gateway.name);
        assertEquals("Gateway", gateway.discoveryMethod);
    }

    @Test
    public void mdnsServiceClassifiesCastWithoutManufacturerTable() {
        assertEquals("Reproductor multimedia", DeviceIdentity.inferDeviceType(
                "192.168.1.20", "192.168.1.1",
                java.util.Collections.emptyList(),
                java.util.Collections.singletonList("_googlecast._tcp"),
                "Sala"));
    }

    @Test
    public void mergePreservesEvidenceFromMultipleDiscoveryLayers() {
        Device existing = new Device("Sala TV", "192.168.1.20", "N/A", "Desconocido",
                "mDNS", "Servicios: _googlecast._tcp");
        Device incoming = new Device("Servicio web", "192.168.1.20", "N/A", "Desconocido",
                "HTTP", "HTTP 8008 · Server: local-web");

        DeviceIdentity.mergeInto(existing, incoming);

        assertTrue(existing.discoveryDetail.contains("_googlecast._tcp"));
        assertTrue(existing.discoveryDetail.contains("HTTP 8008"));
        assertEquals("Sala TV", existing.name);
    }

    @Test
    public void operatingSystemHintUsesOnlySelfDeclaredText() {
        assertEquals("Ubuntu / Linux", DeviceIdentity.inferOsHint("OpenSSH Ubuntu Linux"));
        assertEquals("OpenWrt", DeviceIdentity.inferOsHint("OpenWrt router"));
    }

    @Test
    public void rtspPortAloneDoesNotOverclaimCameraIdentity() {
        assertEquals("Dispositivo RTSP / video", DeviceIdentity.inferDeviceType(
                "192.168.1.60", "192.168.1.1",
                java.util.Collections.singletonList(554),
                java.util.Collections.emptyList(), null));
        assertEquals("Cámara / dispositivo de video", DeviceIdentity.inferDeviceType(
                "192.168.1.61", "192.168.1.1",
                java.util.Collections.singletonList(554),
                java.util.Collections.singletonList("ONVIF"), "NetworkVideoTransmitter"));
    }

    @Test
    public void explicitCameraEvidenceUpgradesGenericRtspType() {
        Device existing = new Device("Video", "192.168.1.60", "N/A", "Desconocido",
                "TCP", "Puerto 554");
        existing.deviceType = "Dispositivo RTSP / video";
        Device incoming = new Device("Cámara patio", "192.168.1.60", "N/A", "Desconocido",
                "WS-Discovery", "ONVIF");
        incoming.deviceType = "Cámara / dispositivo de video";

        DeviceIdentity.mergeInto(existing, incoming);

        assertEquals("Cámara / dispositivo de video", existing.deviceType);
    }

}
