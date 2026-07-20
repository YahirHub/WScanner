package com.thowilabs.wscanner;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class NetBiosDiscoveryTest {

    @Test
    public void nbstatQueryContainsDnsEncodedWildcardName() {
        byte[] query = NetBiosDiscovery.buildNbstatQuery();

        assertEquals(50, query.length);
        assertEquals(0x20, query[12] & 0xFF);
        assertEquals(0x00, query[45] & 0xFF);
        assertEquals(0x00, query[46] & 0xFF);
        assertEquals(0x21, query[47] & 0xFF);
        assertEquals(0x00, query[48] & 0xFF);
        assertEquals(0x01, query[49] & 0xFF);
    }
    @Test
    public void nbstatResponseExtractsActiveUniqueWorkstationName() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        // Header: response, 0 questions, 1 answer.
        out.write(new byte[]{0x12, 0x34, (byte) 0x85, 0, 0, 0, 0, 1, 0, 0, 0, 0});

        // Answer NAME: 32-byte NetBIOS label + terminator.
        out.write(0x20);
        for (int i = 0; i < 32; i++) out.write('A');
        out.write(0x00);
        // TYPE NBSTAT, CLASS IN, TTL 0, RDLENGTH 19.
        out.write(new byte[]{0, 0x21, 0, 1, 0, 0, 0, 0, 0, 19});
        out.write(1); // one name

        byte[] name = String.format("%-15s", "OFFICE-PC")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(name);
        out.write(0x00); // workstation suffix
        out.write(0x04); // ACTIVE flag, network byte order
        out.write(0x00);

        byte[] packet = out.toByteArray();
        assertEquals("OFFICE-PC", NetBiosDiscovery.parseNbstatResponse(packet, packet.length));
    }

    @Test
    public void nbstatDetailedResponseExtractsUnitIdMac() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(new byte[]{0x12, 0x34, (byte) 0x85, 0, 0, 0, 0, 1, 0, 0, 0, 0});
        out.write(0x20);
        for (int i = 0; i < 32; i++) out.write('A');
        out.write(0x00);
        // 1 + 18 bytes nombre + 6 bytes Unit ID.
        out.write(new byte[]{0, 0x21, 0, 1, 0, 0, 0, 0, 0, 25});
        out.write(1);
        out.write(String.format("%-15s", "OFFICE-PC")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(0x00);
        out.write(0x04);
        out.write(0x00);
        out.write(new byte[]{0x00, 0x11, 0x22, 0x33, 0x44, 0x55});

        byte[] packet = out.toByteArray();
        NetBiosDiscovery.Result result = NetBiosDiscovery.parseNbstatResponseDetailed(packet, packet.length);

        assertNotNull(result);
        assertEquals("OFFICE-PC", result.name);
        assertEquals("00:11:22:33:44:55", result.mac);
    }

}
