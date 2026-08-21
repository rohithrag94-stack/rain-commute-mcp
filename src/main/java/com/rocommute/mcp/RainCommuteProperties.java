package com.rocommute.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * User-editable defaults: named location shortcuts (e.g. {@code home}, {@code work}) and a
 * fallback commute duration, so {@code checkRainOnCommute} doesn't require every caller to
 * restate an address and a duration on every request. Bound from {@code rain-commute.*}
 * properties -- see {@code application.properties}' {@code spring.config.import} for how a file
 * outside the jar (survives upgrades, user-editable) gets layered on top of the bundled defaults.
 */
@Component
@ConfigurationProperties(prefix = "rain-commute")
public class RainCommuteProperties {

    private Map<String, String> locations = Map.of();
    private int defaultCommuteMinutes = 30;

    public Map<String, String> getLocations() {
        return locations;
    }

    public void setLocations(Map<String, String> locations) {
        this.locations = locations;
    }

    public int getDefaultCommuteMinutes() {
        return defaultCommuteMinutes;
    }

    public void setDefaultCommuteMinutes(int defaultCommuteMinutes) {
        this.defaultCommuteMinutes = defaultCommuteMinutes;
    }
}
