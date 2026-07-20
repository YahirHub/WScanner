package com.thowilabs.wscanner;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class MdnsDiscoveryTest {

    @Test
    public void aRecordIsPublishedAsIpToHostname() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Header: response, 1 answer.
        out.write(new byte[]{0, 0, (byte) 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 0});
        MdnsDiscovery.encodeDnsName(out, "printer.local");
        out.write(new byte[]{0, 1, 0, 1, 0, 0, 0, 120, 0, 4});
        out.write(new byte[]{(byte) 192, (byte) 168, 1, 50});

        Map<String, String> parsed = MdnsDiscovery.parseDnsResponse(out.toByteArray());

        assertEquals("printer", parsed.get("192.168.1.50"));
    }

    @Test
    public void srvNameDoesNotDuplicateServiceType() {
        assertEquals("Living Room._googlecast._tcp.local",
                MdnsDiscovery.normalizeSrvQueryName(
                        "Living Room._googlecast._tcp", "_googlecast._tcp.local"));
        assertEquals("Living Room",
                MdnsDiscovery.cleanServiceInstanceName(
                        "Living Room._googlecast._tcp", "_googlecast._tcp.local"));
    }
    @Test
    public void dynamicServiceTypesAreNormalizedAndInstancesSeparated() {
        assertEquals("_custom._tcp.local",
                MdnsDiscovery.normalizeServiceType("_custom._tcp"));
        org.junit.Assert.assertTrue(MdnsDiscovery.isServiceType("_custom._tcp.local"));
        org.junit.Assert.assertFalse(MdnsDiscovery.isServiceInstanceName("_custom._tcp"));
        org.junit.Assert.assertTrue(MdnsDiscovery.isServiceInstanceName(
                "Office Device._custom._tcp"));
    }

}
