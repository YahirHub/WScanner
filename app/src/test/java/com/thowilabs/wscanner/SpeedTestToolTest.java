package com.thowilabs.wscanner;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpeedTestToolTest {

    @Test
    public void calculateMbps_usesMetricMegabits() {
        assertEquals(8.0, SpeedTestTool.calculateMbps(1_000_000L, 1_000_000_000L), 0.001);
    }

    @Test
    public void calculateJitter_averagesAdjacentDifferences() {
        assertEquals(7.5,
                SpeedTestTool.calculateJitter(Arrays.asList(10.0, 20.0, 15.0)),
                0.001);
    }

    @Test
    public void percentile_returnsMedianSample() {
        assertEquals(20.0,
                SpeedTestTool.percentile(Arrays.asList(30.0, 10.0, 20.0), 0.5),
                0.001);
    }

    @Test
    public void shouldRepeatMeasurement_onlyRampsWhenSampleGrowsEnough() {
        assertTrue(SpeedTestTool.shouldRepeatMeasurement(1_000_000L, 2_000_000L, 50_000_000L));
        assertFalse(SpeedTestTool.shouldRepeatMeasurement(1_000_000L, 1_200_000L, 50_000_000L));
        assertFalse(SpeedTestTool.shouldRepeatMeasurement(50_000_000L, 50_000_000L, 50_000_000L));
    }

    @Test
    public void choosePerStreamBytes_respectsLimits() {
        long oneMiB = 1024L * 1024L;
        long result = SpeedTestTool.choosePerStreamBytes(
                100.0,
                4_000_000_000L,
                4,
                oneMiB,
                50L * oneMiB,
                200L * oneMiB);

        // 100 Mbps * 4 s = 50 MB totales; dividido entre 4 streams ≈ 12.5 MB.
        assertEquals(12_500_000L, result);
    }
}
