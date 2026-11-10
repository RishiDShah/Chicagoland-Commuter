package com.chicagoland.commuter.gtfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the schedules up to date: downloads the feeds when the app starts and every week,
 * and re-imports only when a file actually changed.
 */
@Service
public class GtfsRefreshService {

    private static final Logger log = LoggerFactory.getLogger(GtfsRefreshService.class);

    /** What the home screen shows about the schedules. */
    public record Status(boolean loading, String error, List<FeedSummary> feeds) {
        public boolean ready() {
            return !feeds.isEmpty();
        }
    }

    public record FeedSummary(String name, int stops, int trips, Instant importedAt) {
    }

    private final GtfsProperties properties;
    private final GtfsDownloader downloader;
    private final GtfsImporter importer;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    private volatile boolean loading;
    private volatile String error;

    public GtfsRefreshService(GtfsProperties properties, GtfsDownloader downloader, GtfsImporter importer,
                              JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.properties = properties;
        this.downloader = downloader;
        this.importer = importer;
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Runs in the background so the app opens right away, even while the first import is running. */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        if (properties.importOnStartup()) {
            Thread.ofVirtual().name("gtfs-refresh").start(this::refresh);
        }
    }

    @Scheduled(cron = "${commuter.gtfs.refresh-cron}", zone = "America/Chicago")
    public void refreshWeekly() {
        refresh();
    }

    public synchronized void refresh() {
        loading = true;
        error = null;
        try {
            List<GtfsImporter.FeedFile> files = downloadAll();
            Map<String, String> imported = jdbc.query("SELECT feed_id, sha256 FROM gtfs_feed", rs -> {
                Map<String, String> m = new java.util.HashMap<>();
                while (rs.next()) {
                    m.put(rs.getString(1), rs.getString(2));
                }
                return m;
            });
            Map<String, String> downloaded = files.stream()
                    .collect(Collectors.toMap(f -> f.feed().id(), GtfsImporter.FeedFile::sha256));
            if (downloaded.equals(imported)) {
                log.info("Schedules unchanged, nothing to import");
                events.publishEvent(new SchedulesImportedEvent());
                return;
            }
            log.info("Importing schedules for {} (the first import takes a few minutes)", downloaded.keySet());
            importer.importAll(files);
            jdbc.execute("ANALYZE");
            log.info("Schedules imported");
            events.publishEvent(new SchedulesImportedEvent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Schedule refresh failed", e);
            error = e.getMessage();
        } finally {
            loading = false;
        }
    }

    /** Downloads every feed. If a download fails, falls back to the last downloaded copy. */
    private List<GtfsImporter.FeedFile> downloadAll() throws Exception {
        List<GtfsImporter.FeedFile> files = new ArrayList<>();
        for (GtfsProperties.Feed feed : properties.feeds()) {
            Path zip;
            try {
                log.info("Downloading {} schedule", feed.name());
                zip = downloader.download(feed, properties.cacheDir());
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                zip = properties.cacheDir().resolve(feed.id() + ".zip");
                if (!Files.exists(zip)) {
                    throw e;
                }
                log.warn("{} download failed ({}), using the copy from {}", feed.name(), e.getMessage(),
                        Files.getLastModifiedTime(zip));
            }
            String source = feed.url() != null ? feed.url() : feed.pageUrl();
            files.add(new GtfsImporter.FeedFile(feed, zip, GtfsDownloader.sha256(zip), source));
        }
        return files;
    }

    public Status status() {
        List<FeedSummary> feeds = jdbc.query(
                "SELECT name, stop_count, trip_count, imported_at FROM gtfs_feed ORDER BY name",
                (rs, i) -> new FeedSummary(rs.getString(1), rs.getInt(2), rs.getInt(3),
                        rs.getTimestamp(4).toInstant()));
        return new Status(loading, error, feeds);
    }
}
