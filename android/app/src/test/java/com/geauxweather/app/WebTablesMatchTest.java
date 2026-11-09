package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** Java copies of small tables in www/js/weather.js; fails if either side changes alone (#37, #39). */
public class WebTablesMatchTest {

    private static String weatherJs() throws Exception {
        // Gradle runs unit tests from android/app
        return new String(Files.readAllBytes(Paths.get("../../www/js/weather.js")), StandardCharsets.UTF_8);
    }

    @Test
    public void forecastModelsMatchWeatherJs() throws Exception {
        Matcher m = Pattern.compile("const MODELS = \\[([^\\]]*)\\]").matcher(weatherJs());
        assertTrue("weather.js no longer declares MODELS", m.find());
        List<String> js = new ArrayList<>();
        Matcher q = Pattern.compile("'([a-z0-9_]+)'").matcher(m.group(1));
        while (q.find()) js.add(q.group(1));
        assertEquals("first JS model must be the default", "best_match", js.remove(0));
        assertEquals(js, Arrays.asList(WidgetRefreshWorker.MODELS));
        for (String model : js) assertEquals(model, WidgetRefreshWorker.forecastModel(model));
    }

    @Test
    public void uvCategoriesMatchWeatherJs() throws Exception {
        String src = weatherJs();
        int start = src.indexOf("function uvLabel");
        assertTrue("weather.js no longer has uvLabel", start >= 0);
        String body = src.substring(start, src.indexOf("\n  }", start));
        Matcher m = Pattern.compile("if \\(n >= (\\d+)\\) return \\{ text: '([^']+)'").matcher(body);
        List<int[]> bands = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            bands.add(new int[] {Integer.parseInt(m.group(1))});
            names.add(m.group(2));
        }
        Matcher low = Pattern.compile("return \\{ text: '([^']+)'[^}]*\\};\\s*$").matcher(body);
        assertTrue("no default UV band", low.find());
        assertTrue("parsed only " + bands.size() + " UV bands", bands.size() >= 4);
        for (int n = 0; n <= 16; n++) {
            String expected = low.group(1);
            for (int i = 0; i < bands.size(); i++) {
                if (n >= bands.get(i)[0]) {
                    expected = names.get(i);
                    break;
                }
            }
            assertEquals("UV " + n, expected, UvNotifyHelper.category(n));
        }
    }
}
