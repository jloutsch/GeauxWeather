package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The widget's condition table is a Java copy of codeToCondition() in www/js/weather.js.
 * This fails if either side changes alone, e.g. after an upstream merge (#19).
 */
public class WidgetIconsMatchWebTest {

    private static String weatherJs() throws Exception {
        // Gradle runs unit tests from android/app
        return new String(Files.readAllBytes(Paths.get("../../www/js/weather.js")), StandardCharsets.UTF_8);
    }

    private static String block(String src, String start) {
        int i = src.indexOf(start);
        assertTrue("weather.js no longer contains " + start, i >= 0);
        int end = src.indexOf("};", i); // both tables are object literals ending in "};"
        assertTrue("no end of table after " + start, end > i);
        return src.substring(i, end);
    }

    @Test
    public void everyWebDayConditionMatches() throws Exception {
        Matcher m = Pattern.compile("(\\d+): \\{ text: '([^']+)', icon: '([^']+)' \\}")
                .matcher(block(weatherJs(), "function dayCondition"));
        int n = 0;
        while (m.find()) {
            int code = Integer.parseInt(m.group(1));
            assertEquals("code " + code, m.group(3) + " " + m.group(2), WidgetRefreshWorker.condition(code, true));
            n++;
        }
        assertTrue("parsed only " + n + " codes from weather.js", n >= 20);
    }

    @Test
    public void everyWebNightIconMatches() throws Exception {
        String src = weatherJs();
        Map<Integer, String> night = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(\\d+): '([^']+)'").matcher(block(src, "const NIGHT_ICONS"));
        while (m.find()) night.put(Integer.parseInt(m.group(1)), m.group(2));
        assertTrue("parsed no night icons from weather.js", night.size() >= 3);

        Matcher day = Pattern.compile("(\\d+): \\{ text: '([^']+)', icon: '([^']+)' \\}")
                .matcher(block(src, "function dayCondition"));
        while (day.find()) {
            int code = Integer.parseInt(day.group(1));
            String icon = night.containsKey(code) ? night.get(code) : day.group(3);
            assertEquals("night code " + code, icon + " " + day.group(2), WidgetRefreshWorker.condition(code, false));
        }
    }
}
