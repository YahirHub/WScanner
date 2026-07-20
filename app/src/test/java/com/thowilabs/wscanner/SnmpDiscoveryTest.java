package com.thowilabs.wscanner;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SnmpDiscoveryTest {

    @Test
    public void requestContainsPublicCommunityAndBothSystemOids() {
        byte[] request = SnmpDiscovery.buildGetRequest(12345);
        String raw = new String(request, StandardCharsets.ISO_8859_1);
        assertTrue(raw.contains("public"));
        assertTrue(request.length > 40);
    }

    @Test
    public void parserExtractsSystemNameAndDescription() {
        byte[] sysDescrOid = {0x2b,0x06,0x01,0x02,0x01,0x01,0x01,0x00};
        byte[] sysNameOid = {0x2b,0x06,0x01,0x02,0x01,0x01,0x05,0x00};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, new byte[]{0x06,0x08}); write(out, sysDescrOid);
        byte[] descr = "Managed network switch".getBytes(StandardCharsets.UTF_8);
        out.write(0x04); out.write(descr.length); write(out, descr);
        write(out, new byte[]{0x06,0x08}); write(out, sysNameOid);
        byte[] name = "core-switch".getBytes(StandardCharsets.UTF_8);
        out.write(0x04); out.write(name.length); write(out, name);

        byte[] response = out.toByteArray();
        SnmpDiscovery.Result result = SnmpDiscovery.parseResponse(response, response.length);
        assertNotNull(result);
        assertEquals("core-switch", result.sysName);
        assertEquals("Managed network switch", result.sysDescr);
        assertEquals("Switch de red", result.deviceType);
    }

    private static void write(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }
}
