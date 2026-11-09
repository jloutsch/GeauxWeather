package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class UvNotifyHelperTest {

    @Test
    public void belowTheChosenLevelStaysQuiet() {
        assertNull(UvNotifyHelper.title(7.4, 8));
        assertNull(UvNotifyHelper.title(2, 6));
    }

    @Test
    public void atOrAboveTheLevelNamesTheCategory() {
        assertEquals("Very high UV today (8)", UvNotifyHelper.title(7.6, 8)); // rounds like the app
        assertEquals("High UV today (6)", UvNotifyHelper.title(6, 6));
        assertEquals("Extreme UV today (11)", UvNotifyHelper.title(11.2, 8));
    }
}
