package com.chicagoland.commuter.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.chicagoland.commuter.TestcontainersConfiguration;
import com.chicagoland.commuter.gtfs.GtfsDownloader;
import com.chicagoland.commuter.gtfs.GtfsImporter;
import com.chicagoland.commuter.gtfs.GtfsProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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
import org.springframework.test.context.ActiveProfiles;

/** The Elgin → UIC commute on the sample feeds: Metra to Union Station, then the 157 bus. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripPlannerTest {

    @Autowired
    GtfsImporter importer;
    @Autowired
    TripPlanner planner;

    @TempDir
    Path tmp;

    @BeforeEach
    void importSampleFeeds() throws Exception {
        importer.importAll(List.of(feed("metra", "Metra"), feed("cta", "CTA")));
        planner.onSchedulesImported(new com.chicagoland.commuter.gtfs.SchedulesImportedEvent());
    }

    @Test
    void bartlettToUicOnAWeekday() {
        // Tuesday, November 17, 2026
        var plan = planner.plan("metra:BARTLETT", "cta:40350", LocalDateTime.of(2026, 11, 17, 7, 0), 3);

        assertThat(plan.options()).hasSize(1);
        Journey j = plan.options().getFirst();
        assertThat(j.leaveAt()).isEqualTo(7 * 3600 + 28 * 60);
        List<String> summary = j.legs().stream().map(l -> switch (l) {
            case Journey.Ride r -> r.route().displayName() + ": " + r.fromStop() + " → " + r.toStop();
            case Journey.Walk w -> "Walk: " + w.fromStop() + " → " + w.toStop();
        }).toList();
        assertThat(summary).containsExactly(
                "Metra MD-W: Bartlett → Chicago Union Station",
                "Walk: Chicago Union Station → Canal & Jackson, \"Union Station\"",
                "Bus 157: Canal & Jackson, \"Union Station\" → Halsted & Polk",
                "Walk: Halsted & Polk → Destination");
        assertThat(((Journey.Ride) j.legs().get(2)).start()).isEqualTo(8 * 3600 + 21 * 60);
        assertThat(j.transfers()).isEqualTo(1);
    }

    @Test
    void noTrainsOnThanksgiving() {
        var plan = planner.plan("metra:BARTLETT", "cta:40350", LocalDateTime.of(2026, 11, 26, 7, 0), 3);

        assertThat(plan.options()).isEmpty();
        assertThat(plan.message()).isNotBlank();
    }

    @Test
    void tooLateForTheLastTrain() {
        var plan = planner.plan("metra:BARTLETT", "cta:40350", LocalDateTime.of(2026, 11, 17, 7, 30), 3);

        assertThat(plan.options()).isEmpty();
    }

    private GtfsImporter.FeedFile feed(String id, String name) throws Exception {
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
