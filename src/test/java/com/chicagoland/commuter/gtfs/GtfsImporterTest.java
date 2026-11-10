package com.chicagoland.commuter.gtfs;

import static org.assertj.core.api.Assertions.assertThat;

import com.chicagoland.commuter.TestcontainersConfiguration;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Imports the small sample feeds in src/test/resources/gtfs into a real PostGIS database. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class GtfsImporterTest {

    @Autowired
    GtfsImporter importer;
    @Autowired
    GtfsRefreshService refresh;
    @Autowired
    StopSearchService search;
    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path tmp;

    @BeforeEach
    void importSampleFeeds() throws Exception {
        importer.importAll(List.of(feed("metra", "Metra"), feed("cta", "CTA")));
    }

    @Test
    void importsStopsWithFeedPrefixAndLocation() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stop", Integer.class)).isEqualTo(9);
        var bartlett = jdbc.queryForMap(
                "SELECT name, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon FROM stop "
                        + "WHERE stop_id = 'metra:BARTLETT'");
        assertThat(bartlett.get("name")).isEqualTo("Bartlett");
        assertThat((Double) bartlett.get("lat")).isEqualTo(41.9922222);
        assertThat((Double) bartlett.get("lon")).isEqualTo(-88.1838889);
        // Platforms point at their station, also prefixed
        assertThat(jdbc.queryForObject("SELECT parent_station FROM stop WHERE stop_id = 'cta:30069'", String.class))
                .isEqualTo("cta:40350");
        // Quotes and commas inside a name survive
        assertThat(jdbc.queryForObject("SELECT name FROM stop WHERE stop_id = 'cta:15993'", String.class))
                .isEqualTo("Canal & Jackson, \"Union Station\"");
    }

    @Test
    void storesTimesAsSecondsIncludingAfterMidnight() {
        assertThat(jdbc.queryForObject("SELECT arrival_secs FROM stop_time "
                + "WHERE trip_id = 'metra:MD-W_MW2208_V1_A' AND stop_id = 'metra:BARTLETT'", Integer.class))
                .isEqualTo(7 * 3600 + 28 * 60);
        assertThat(jdbc.queryForObject("SELECT arrival_secs FROM stop_time "
                + "WHERE trip_id = 'metra:MD-W_MW2299_V1_A' AND stop_sequence = 3", Integer.class))
                .isEqualTo(25 * 3600 + 10 * 60 + 30);
        // A stop with no scheduled time (between timepoints) is stored as empty
        assertThat(jdbc.queryForObject("SELECT arrival_secs FROM stop_time WHERE stop_id = 'cta:1340'",
                Integer.class)).isNull();
    }

    @Test
    void importsCalendarsTransfersAndFeedSummary() {
        assertThat(jdbc.queryForObject("SELECT end_date::text FROM service_calendar WHERE service_id = 'metra:A1B'",
                String.class)).isEqualTo("2026-12-31");
        assertThat(jdbc.queryForObject("SELECT exception_type FROM service_calendar_date "
                + "WHERE service_id = 'metra:A1B' AND service_date = '2026-11-26'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer", Integer.class)).isEqualTo(1);
        assertThat(refresh.status().feeds())
                .extracting(GtfsRefreshService.FeedSummary::name, GtfsRefreshService.FeedSummary::stops)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("CTA", 6),
                        org.assertj.core.groups.Tuple.tuple("Metra", 3));
    }

    @Test
    void reimportReplacesInsteadOfDuplicating() throws Exception {
        importer.importAll(List.of(feed("metra", "Metra")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stop", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stop_time", Integer.class)).isEqualTo(6);
    }

    @Test
    void searchFindsStationsNotPlatformsAndListsEachBusStopOnce() {
        assertThat(search.search("bart", 0, 10))
                .extracting(StopSearchService.StopSuggestion::label)
                .containsExactly("Bartlett · Metra station");
        assertThat(search.search("UIC", 0, 10))
                .extracting(StopSearchService.StopSuggestion::label)
                .containsExactly("UIC-Halsted · CTA 'L' station");
        assertThat(search.search("halsted & polk", 0, 10)).hasSize(1);
        // Train stations are listed before bus stops
        assertThat(search.search("union station", 0, 10))
                .extracting(StopSearchService.StopSuggestion::kind)
                .containsExactly("Metra station", "CTA bus stop");
        assertThat(search.search("  ", 0, 10)).isEmpty();
        assertThat(search.search("100%", 0, 10)).isEmpty();
    }

    /** Zips one sample folder the way the agencies publish it. */
    private GtfsImporter.FeedFile feed(String id, String name) throws IOException {
        Path folder = Path.of("src/test/resources/gtfs", id);
        Path zip = tmp.resolve(id + ".zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip)); Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                out.putNextEntry(new ZipEntry(file.getFileName().toString()));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        var feed = new GtfsProperties.Feed(id, name, "https://example.test/" + id + ".zip", null, null);
        return new GtfsImporter.FeedFile(feed, zip, GtfsDownloader.sha256(zip), feed.url());
    }
}
