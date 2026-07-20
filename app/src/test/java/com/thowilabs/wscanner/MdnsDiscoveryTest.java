package com.thowilabs.wscanner;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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

    @Test
    public void extractsServiceTypeFromInstanceName() {
        assertEquals("_googlecast._tcp",
                MdnsDiscovery.extractServiceTypeFromInstance(
                        "Living Room._googlecast._tcp.local"));
        assertEquals("_ipp._tcp",
                MdnsDiscovery.extractServiceTypeFromInstance(
                        "Office Printer._ipp._tcp"));
    }

    @Test
    public void txtRecordPublishesSelfDeclaredModelAndIdentityMetadata() throws Exception {
        ByteArrayOutputStream rdata = new ByteArrayOutputStream();
        writeTxt(rdata, "fn=Sala");
        writeTxt(rdata, "md=Cast Device X");
        writeTxt(rdata, "deviceid=AA:BB:CC:DD:EE:FF");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0, 0, (byte) 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 0});
        MdnsDiscovery.encodeDnsName(out, "Sala._googlecast._tcp.local");
        int len = rdata.size();
        out.write(new byte[]{0, 16, 0, 1, 0, 0, 0, 120,
                (byte) ((len >> 8) & 0xFF), (byte) (len & 0xFF)});
        out.write(rdata.toByteArray());

        Map<String, Map<String, String>> parsed = MdnsDiscovery.parseTxtResponse(out.toByteArray());
        Map<String, String> txt = parsed.get("Sala._googlecast._tcp");
        assertEquals("Sala", txt.get("fn"));
        assertEquals("Cast Device X", txt.get("md"));

        MdnsDiscovery.Result result = new MdnsDiscovery.Result();
        MdnsDiscovery.applyTxtMetadata(result, txt);
        assertEquals("Sala", result.displayName());
        assertEquals("Cast Device X", result.model);
        assertEquals("AA:BB:CC:DD:EE:FF", result.mac);
    }

    private static void writeTxt(ByteArrayOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length);
        out.write(bytes);
    }

}
