package com.thowilabs.wscanner;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WsDiscoveryTest {

    @Test
    public void parsesOnvifNameModelAndType() {
        String xml = "<s:Envelope xmlns:s=\"x\" xmlns:d=\"y\">"
                + "<d:ProbeMatches><d:ProbeMatch>"
                + "<d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types>"
                + "<d:Scopes>onvif://www.onvif.org/name/Patio%20Camera "
                + "onvif://www.onvif.org/hardware/IPC-123</d:Scopes>"
                + "<d:XAddrs>http://192.168.1.40/onvif/device_service</d:XAddrs>"
                + "</d:ProbeMatch></d:ProbeMatches></s:Envelope>";

        WsDiscovery.Result result = WsDiscovery.parseResponse(xml, "192.168.1.40");
        assertNotNull(result);
        assertEquals("Patio Camera", result.name);
        assertEquals("IPC-123", result.model);
        assertEquals("Cámara / dispositivo de video", result.deviceType);
        assertTrue(result.services.contains("ONVIF"));
    }

    @Test
    public void probeContainsDiscoveryActionAndUniqueId() {
        String probe = WsDiscovery.buildProbe();
        assertTrue(probe.contains("/discovery/Probe"));
        assertTrue(probe.contains("uuid:"));
    }
}
