package com.thowilabs.wscanner;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScanCycleStateTest {

    @Test
    public void devicesStayOnlineUntilCycleFinishes() {
        Device first = new Device("Uno", "192.168.1.10", "N/A", "Desconocido");
        Device second = new Device("Dos", "192.168.1.11", "N/A", "Desconocido");
        List<Device> devices = new ArrayList<>();
        devices.add(first);
        devices.add(second);

        ScanCycleState state = new ScanCycleState();
        state.beginCycle();
        state.markSeen(first.ip);

        // Durante el ciclo se conserva el último estado conocido.
        assertTrue(first.online);
        assertTrue(second.online);

        state.finishCycle(devices);
        assertTrue(first.online);
        assertFalse(second.online);
    }

    @Test
    public void previouslyOfflineDeviceReturnsOnlineWhenSeenAgain() {
        Device device = new Device("Equipo", "192.168.1.20", "N/A", "Desconocido");
        device.online = false;
        List<Device> devices = java.util.Collections.singletonList(device);

        ScanCycleState state = new ScanCycleState();
        state.beginCycle();
        state.markSeen(device.ip);
        state.finishCycle(devices);

        assertTrue(device.online);
    }
}
