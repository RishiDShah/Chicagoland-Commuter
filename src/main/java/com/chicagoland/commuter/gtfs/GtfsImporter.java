package com.chicagoland.commuter.gtfs;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.sql.DataSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads GTFS zip files into the schedule tables. All feeds are replaced in one transaction,
 * so the app keeps using the old schedules until the new ones are completely loaded.
 */
@Component
public class GtfsImporter {

    private static final Logger log = LoggerFactory.getLogger(GtfsImporter.class);
    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final DataSource dataSource;

    public GtfsImporter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** A downloaded feed ready to import. */
    public record FeedFile(GtfsProperties.Feed feed, Path zip, String sha256, String sourceUrl) {
    }

    public void importAll(List<FeedFile> feeds) throws IOException, SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("TRUNCATE gtfs_feed, route, stop, trip, stop_time, "
                            + "service_calendar, service_calendar_date, transfer");
                }
                for (FeedFile feed : feeds) {
                    importFeed(connection, feed);
                }
                connection.commit();
            } catch (IOException | SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private void importFeed(Connection connection, FeedFile file) throws IOException, SQLException {
        long started = System.nanoTime();
        String f = file.feed().id();
        try (ZipFile zip = new ZipFile(file.zip().toFile())) {
            copy(connection, zip, "routes.txt", true,
                    "route (route_id, feed_id, short_name, long_name, route_type, color, text_color)",
                    r -> new Object[] {id(f, r.get("route_id")), f, r.get("route_short_name"),
                            r.get("route_long_name"), r.require("route_type"), r.get("route_color"),
                            r.get("route_text_color")});

            long stops = copy(connection, zip, "stops.txt", true,
                    "stop (stop_id, feed_id, code, name, location, location_type, parent_station, wheelchair)",
                    r -> new Object[] {id(f, r.get("stop_id")), f, r.get("stop_code"), r.require("stop_name"),
                            point(r.require("stop_lat"), r.require("stop_lon")), orDefault(r.get("location_type"), "0"),
                            id(f, r.get("parent_station")), r.get("wheelchair_boarding")});

            long trips = copy(connection, zip, "trips.txt", true,
                    "trip (trip_id, feed_id, route_id, service_id, headsign, direction_id)",
                    r -> new Object[] {id(f, r.get("trip_id")), f, id(f, r.get("route_id")),
                            id(f, r.get("service_id")), r.get("trip_headsign"), r.get("direction_id")});

            copy(connection, zip, "stop_times.txt", true,
                    "stop_time (trip_id, stop_sequence, stop_id, arrival_secs, departure_secs, pickup_type, drop_off_type)",
                    r -> new Object[] {id(f, r.get("trip_id")), r.require("stop_sequence"), id(f, r.get("stop_id")),
                            seconds(r.get("arrival_time")), seconds(r.get("departure_time")),
                            orDefault(r.get("pickup_type"), "0"), orDefault(r.get("drop_off_type"), "0")});

            copy(connection, zip, "calendar.txt", false,
                    "service_calendar (service_id, feed_id, monday, tuesday, wednesday, thursday, friday, "
                            + "saturday, sunday, start_date, end_date)",
                    r -> new Object[] {id(f, r.get("service_id")), f, bool(r.get("monday")), bool(r.get("tuesday")),
                            bool(r.get("wednesday")), bool(r.get("thursday")), bool(r.get("friday")),
                            bool(r.get("saturday")), bool(r.get("sunday")), date(r.require("start_date")),
                            date(r.require("end_date"))});

            copy(connection, zip, "calendar_dates.txt", false,
                    "service_calendar_date (service_id, feed_id, service_date, exception_type)",
                    r -> new Object[] {id(f, r.get("service_id")), f, date(r.require("date")),
                            r.require("exception_type")});

            copy(connection, zip, "transfers.txt", false,
                    "transfer (feed_id, from_stop_id, to_stop_id, transfer_type, min_transfer_secs)",
                    r -> new Object[] {f, id(f, r.get("from_stop_id")), id(f, r.get("to_stop_id")),
                            orDefault(r.get("transfer_type"), "0"), r.get("min_transfer_time")});

            try (var insert = connection.prepareStatement("INSERT INTO gtfs_feed "
                    + "(feed_id, name, source_url, sha256, imported_at, stop_count, trip_count) VALUES (?,?,?,?,?,?,?)")) {
                insert.setString(1, f);
                insert.setString(2, file.feed().name());
                insert.setString(3, file.sourceUrl());
                insert.setString(4, file.sha256());
                insert.setTimestamp(5, Timestamp.from(Instant.now()));
                insert.setInt(6, (int) stops);
                insert.setInt(7, (int) trips);
                insert.executeUpdate();
            }
            log.info("Imported {}: {} stops, {} trips in {} s", file.feed().name(), stops, trips,
                    (System.nanoTime() - started) / 1_000_000_000);
        }
    }

    /** Streams one CSV file from the zip into a table using PostgreSQL COPY. Returns the row count. */
    private long copy(Connection connection, ZipFile zip, String fileName, boolean required, String target,
                      Function<Row, Object[]> mapper) throws IOException, SQLException {
        ZipEntry entry = zip.getEntry(fileName);
        if (entry == null) {
            if (required) {
                throw new IOException(zip.getName() + " has no " + fileName);
            }
            return 0;
        }
        long count = 0;
        var copyApi = connection.unwrap(PGConnection.class);
        try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setTrim(true).setIgnoreEmptyLines(true).get().parse(reader);
             var out = new PGCopyOutputStream(copyApi, "COPY " + target + " FROM STDIN WITH (FORMAT csv)", 1 << 16);
             var printer = new CSVPrinter(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16),
                     CSVFormat.DEFAULT.builder().setRecordSeparator('\n').get())) {
            Map<String, Integer> header = null;
            for (CSVRecord record : parser) {
                if (header == null) {
                    header = header(record);
                    continue;
                }
                try {
                    printer.printRecord(mapper.apply(new Row(header, record)));
                } catch (IllegalArgumentException e) {
                    throw new IOException(fileName + " line " + record.getRecordNumber() + ": " + e.getMessage(), e);
                }
                count++;
            }
            printer.flush();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return count;
    }

    private static Map<String, Integer> header(CSVRecord record) {
        Map<String, Integer> header = new HashMap<>();
        for (int i = 0; i < record.size(); i++) {
            // Strip the invisible byte-order mark some agencies put at the start of the file
            header.put(record.get(i).replace("﻿", "").trim(), i);
        }
        return header;
    }

    /** A CSV row looked up by column name. Blank values come back as null (NULL in the database). */
    record Row(Map<String, Integer> header, CSVRecord record) {
        String get(String column) {
            Integer index = header.get(column);
            if (index == null || index >= record.size()) {
                return null;
            }
            String value = record.get(index);
            return value.isBlank() ? null : value;
        }

        String require(String column) {
            String value = get(column);
            if (value == null) {
                throw new IllegalArgumentException("missing " + column);
            }
            return value;
        }
    }

    /** "cta" + "1234" → "cta:1234", so IDs from different agencies never collide. */
    static String id(String feed, String id) {
        return id == null ? null : feed + ":" + id;
    }

    /** "25:10:30" → 90630 seconds. GTFS uses hours past 24 for trips that run after midnight. */
    static Integer seconds(String time) {
        if (time == null) {
            return null;
        }
        String[] parts = time.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("bad time " + time);
        }
        return Integer.parseInt(parts[0].trim()) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
    }

    /** "20261109" → "2026-11-09". */
    static String date(String gtfsDate) {
        return LocalDate.parse(gtfsDate, GTFS_DATE).toString();
    }

    static String bool(String value) {
        return "1".equals(value) ? "t" : "f";
    }

    /** Longitude/latitude as a PostGIS point in the standard GPS coordinate system (SRID 4326). */
    static String point(String lat, String lon) {
        return "SRID=4326;POINT(" + Double.parseDouble(lon) + " " + Double.parseDouble(lat) + ")";
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
