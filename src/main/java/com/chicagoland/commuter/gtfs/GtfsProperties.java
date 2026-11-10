package com.chicagoland.commuter.gtfs;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code commuter.gtfs.*} in application.properties.
 *
 * @param importOnStartup download and import schedules when the app starts (skipped if nothing changed)
 * @param cacheDir        where downloaded zip files are kept, so the app still starts offline
 * @param feeds           the agencies to load
 */
@ConfigurationProperties("commuter.gtfs")
public record GtfsProperties(boolean importOnStartup, Path cacheDir, List<Feed> feeds) {

    /**
     * One agency's schedule feed. Give either {@code url} (a direct link to the zip) or
     * {@code pageUrl} + {@code linkPattern} when the link changes and must be found on a web page.
     *
     * @param id          short prefix for IDs, e.g. "cta"
     * @param name        display name, e.g. "CTA"
     * @param url         direct link to the GTFS zip
     * @param pageUrl     web page that links to the zip
     * @param linkPattern regex with one group capturing the zip link on that page
     */
    public record Feed(String id, String name, String url, String pageUrl, String linkPattern) {
    }
}
