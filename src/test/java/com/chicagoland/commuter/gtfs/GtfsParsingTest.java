package com.chicagoland.commuter.gtfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** Fast checks of the small conversion helpers; no database needed. */
class GtfsParsingTest {

    @Test
    void convertsTimesToSeconds() {
        assertThat(GtfsImporter.seconds("00:00:00")).isZero();
        assertThat(GtfsImporter.seconds("7:28:00")).isEqualTo(26_880);
        assertThat(GtfsImporter.seconds("25:10:30")).isEqualTo(90_630);
        assertThat(GtfsImporter.seconds(null)).isNull();
        assertThatThrownBy(() -> GtfsImporter.seconds("7:28")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertsDatesIdsAndPoints() {
        assertThat(GtfsImporter.date("20261109")).isEqualTo("2026-11-09");
        assertThat(GtfsImporter.id("cta", "1234")).isEqualTo("cta:1234");
        assertThat(GtfsImporter.id("cta", null)).isNull();
        assertThat(GtfsImporter.point("41.99", "-88.18")).isEqualTo("SRID=4326;POINT(-88.18 41.99)");
    }

    @Test
    void findsPaceZipLinkOnTheirPage() {
        String html = "<a href=\"/sites/default/files/2026-10/routes.pdf\">PDF</a>"
                + "<a href=\"/sites/default/files/2026-10/GTFS.zip\">GTFS Data</a>";
        var base = URI.create("https://www.pacebus.com/route-timetable-data-services");
        assertThat(GtfsDownloader.findLink(html, "href=\"([^\"]*GTFS[^\"]*\\.zip)\"", base))
                .contains(URI.create("https://www.pacebus.com/sites/default/files/2026-10/GTFS.zip"));
        assertThat(GtfsDownloader.findLink("<p>nothing</p>", "href=\"([^\"]*GTFS[^\"]*\\.zip)\"", base)).isEmpty();
    }
}
