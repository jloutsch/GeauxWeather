package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void mapLayerMatchesWebSide() {
        assertEquals("radar", HurricaneAlertHelper.mapLayerForEvent("Winter Storm Warning"));
        assertEquals("wind", HurricaneAlertHelper.mapLayerForEvent("High Wind Warning"));
        assertEquals("fog", HurricaneAlertHelper.mapLayerForEvent("Dense Fog Advisory"));
        assertEquals("hurricane", HurricaneAlertHelper.mapLayerForEvent("Storm Surge Warning"));
        assertEquals("tornado", HurricaneAlertHelper.mapLayerForEvent("Tornado Warning"));
    }
}
