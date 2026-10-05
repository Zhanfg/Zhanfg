package io.github.andrealtb.coloroslyrics.provider.apple;

import static org.junit.Assert.*;

import org.junit.Test;

public class AppleLyricLeasePolicyTest {
    @Test
    public void selectsOnlyNearFutureLines() {
        long[] begins = {0, 2000, 5000, 9000, 15000};
        long[] ends = {1500, 3500, 7000, 11000, 17000};
        AppleLyricLeasePolicy.Window window =
                AppleLyricLeasePolicy.select(begins, ends, 5200, 1000, 4500);
        assertNotNull(window);
        assertEquals(2, window.first);
        assertEquals(3, window.last);
    }

    @Test
    public void doesNotShipWholeSongAcrossLongGap() {
        long[] begins = {0, 30000};
        long[] ends = {2000, 32000};
        assertNull(AppleLyricLeasePolicy.select(begins, ends, 10000, 1000, 4500));
    }

    @Test
    public void prearmsNearestLineInsideLease() {
        long[] begins = {0, 12000};
        long[] ends = {1000, 14000};
        AppleLyricLeasePolicy.Window window =
                AppleLyricLeasePolicy.select(begins, ends, 8000, 1000, 4500);
        assertNotNull(window);
        assertEquals(1, window.first);
        assertEquals(1, window.last);
    }
}
