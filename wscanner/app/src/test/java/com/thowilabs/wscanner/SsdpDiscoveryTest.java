package com.thowilabs.wscanner;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class SsdpDiscoveryTest {

    @Test
    public void parsesStructuredUpnpDescription() {
        String xml = "<root><device>"
                + "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                + "<friendlyName>Sala TV</friendlyName>"
                + "<manufacturer>Fabricante local</manufacturer>"
                + "<modelName>Renderer X</modelName><modelNumber>42</modelNumber>"
                + "</device></root>";

        SsdpDiscovery.Description result = SsdpDiscovery.parseDescription(xml);

        assertNotNull(result);
        assertEquals("Sala TV", result.friendlyName);
        assertEquals("Fabricante local", result.manufacturer);
        assertEquals("Renderer X 42", result.model);
        assertEquals("Reproductor multimedia",
                SsdpDiscovery.inferUpnpDeviceType(result.deviceType));
    }

    @Test
    public void upnpCameraTypeIsDetectedWithoutVendorDictionary() {
        assertEquals("Cámara / dispositivo de video",
                SsdpDiscovery.inferUpnpDeviceType(
                        "urn:schemas-upnp-org:device:NetworkVideoTransmitter:1"));
    }
}
