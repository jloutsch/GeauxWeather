package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AqiNotifyHelperTest {

    @Test
    public void usLevelsStartAtSensitiveUnhealthyAndVeryUnhealthy() {
        assertNull(AqiNotifyHelper.title(100, true, 1));
        assertEquals("Air quality today: Unhealthy (sensitive) (AQI 101)", AqiNotifyHelper.title(101, true, 1));
        assertNull(AqiNotifyHelper.title(150, true, 2));
        assertEquals("Air quality today: Unhealthy (AQI 162)", AqiNotifyHelper.title(161.6, true, 2));
        assertNull(AqiNotifyHelper.title(200, true, 3));
        assertEquals("Air quality today: Hazardous (AQI 320)", AqiNotifyHelper.title(320, true, 3));
    }

    @Test
    public void euLevelsStartAtPoorVeryPoorAndExtremelyPoor() {
        assertNull(AqiNotifyHelper.title(60, false, 1));
        assertEquals("Air quality today: Poor (EU AQI 61)", AqiNotifyHelper.title(61, false, 1));
        assertNull(AqiNotifyHelper.title(80, false, 2));
        assertEquals("Air quality today: Extremely poor (EU AQI 120)", AqiNotifyHelper.title(120, false, 3));
    }

    @Test
    public void badLevelSettingIsClamped() {
        assertEquals(AqiNotifyHelper.title(101, true, 1), AqiNotifyHelper.title(101, true, 0));
        assertEquals(AqiNotifyHelper.title(250, true, 3), AqiNotifyHelper.title(250, true, 9));
    }
}
