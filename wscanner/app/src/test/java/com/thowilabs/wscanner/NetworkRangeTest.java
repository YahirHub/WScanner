package com.thowilabs.wscanner;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetworkRangeTest {

    @Test
    public void slash24ProducesUsableHosts() {
        NetworkScanner.NetworkRange range = new NetworkScanner.NetworkRange(
                "192.168.10.25", 24, "192.168.10.1");
        List<String> hosts = range.hosts(1024);

        assertEquals(254, hosts.size());
        assertEquals("192.168.10.1", hosts.get(0));
        assertEquals("192.168.10.254", hosts.get(253));
        assertTrue(range.contains("192.168.10.200"));
        assertFalse(range.contains("192.168.11.1"));
    }

    @Test
    public void largeNetworkIsBoundedAroundCurrentSlash24() {
        NetworkScanner.NetworkRange range = new NetworkScanner.NetworkRange(
                "10.20.30.40", 16, "10.20.0.1");
        List<String> hosts = range.hosts(1024);

        assertEquals(254, hosts.size());
        assertEquals("10.20.30.1", hosts.get(0));
        assertEquals("10.20.30.254", hosts.get(253));
    }
    @Test
    public void cidrUsesActualNetworkAddress() {
        NetworkScanner.NetworkRange range = new NetworkScanner.NetworkRange(
                "192.168.10.218", 24, "192.168.10.1");
        assertEquals("192.168.10.0/24", range.cidr());
    }

}
