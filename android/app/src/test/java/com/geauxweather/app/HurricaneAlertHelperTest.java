package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HurricaneAlertHelperTest {

    @Test
    public void originalStormEventsNotifyRegardlessOfSeverity() {
        assertTrue(HurricaneAlertHelper.shouldNotify("Tornado Watch", "Moderate", false));
        assertTrue(HurricaneAlertHelper.shouldNotify("Hurricane Watch", "Unknown", false));
    }

    @Test
    public void warningsLevelAddsExtremeAndSevere() {
        assertTrue(HurricaneAlertHelper.shouldNotify("Winter Storm Warning", "Severe", false));
        assertTrue(HurricaneAlertHelper.shouldNotify("Excessive Heat Warning", "Extreme", false));
    }

    @Test
    public void advisoriesOnlyWhenOptedIn() {
        assertFalse(HurricaneAlertHelper.shouldNotify("Wind Advisory", "Moderate", false));
        assertTrue(HurricaneAlertHelper.shouldNotify("Wind Advisory", "Moderate", true));
    }

    @Test
    public void minorNeverNotifies() {
        assertFalse(HurricaneAlertHelper.shouldNotify("Frost Advisory", "Minor", true));
    }

    @Test
    public void activeStormSpeedsUpEvenIfAFeedFailed() {
        assertEquals(Boolean.TRUE, HurricaneAlertHelper.nextFastMode(true, false));
    }

    @Test
    public void quietAndBothFeedsAnsweredSlowsDown() {
        assertEquals(Boolean.FALSE, HurricaneAlertHelper.nextFastMode(false, true));
    }

    @Test
    public void failedFeedNeverSlowsDown() {
        assertNull(HurricaneAlertHelper.nextFastMode(false, false));
    }

    @Test
    public void mapLayerMatchesWebSide() {
        assertEquals("radar", HurricaneAlertHelper.mapLayerForEvent("Winter Storm Warning"));
        assertEquals("wind", HurricaneAlertHelper.mapLayerForEvent("High Wind Warning"));
        assertEquals("fog", HurricaneAlertHelper.mapLayerForEvent("Dense Fog Advisory"));
        assertEquals("hurricane", HurricaneAlertHelper.mapLayerForEvent("Storm Surge Warning"));
        assertEquals("tornado", HurricaneAlertHelper.mapLayerForEvent("Tornado Warning"));
    }

    @Test
    public void onlyANeverCheckedPhoneIsAQuietFirstRun() {
        assertTrue(HurricaneAlertHelper.isFirstRun(0, 0));
        // After a quiet check the seen record is empty; the next warning must still notify
        assertFalse(HurricaneAlertHelper.isFirstRun(1_794_000_000_000L, 0));
        assertFalse(HurricaneAlertHelper.isFirstRun(0, 2)); // older install with alerts on record
    }
}
