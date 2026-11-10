package com.chicagoland.commuter.routing;

import java.sql.Array;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Builds the timetable for one day from the schedule tables. */
@Component
public class TimetableLoader {

    private static final Logger log = LoggerFactory.getLogger(TimetableLoader.class);

    /** Farthest you're asked to walk between stops for a transfer. */
    static final double MAX_TRANSFER_WALK_METERS = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;

    public TimetableLoader(DataSource dataSource, PlatformTransactionManager transactions) {
        this.jdbc = new JdbcTemplate(dataSource);
        // Stream rows in batches instead of holding millions of them in memory at once
        this.jdbc.setFetchSize(20_000);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    public Timetable load(LocalDate date) {
        long started = System.nanoTime();
        return readOnly.execute(status -> {
            var builder = new Timetable.Builder();
            jdbc.query("SELECT stop_id, name, ST_Y(location::geometry), ST_X(location::geometry) FROM stop",
                    rs -> {
                        builder.addStop(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4));
                    });

            List<String> services = activeServices(date);
            Array serviceArray = jdbc.execute((java.sql.Connection c) -> c.createArrayOf("text", services.toArray()));

            Map<String, RouteInfo> routes = new HashMap<>();
            jdbc.query("SELECT route_id, feed_id, short_name, long_name, route_type, color FROM route", rs -> {
                routes.put(rs.getString(1), new RouteInfo(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6)));
            });

            // Stop times of every trip running that day, trip by trip in stop order
            var trip = new TripCollector(builder, routes);
            jdbc.query("""
                    SELECT st.trip_id, t.route_id, st.stop_id, st.arrival_secs, st.departure_secs,
                           st.pickup_type, st.drop_off_type
                    FROM trip t JOIN stop_time st USING (trip_id)
                    WHERE t.service_id = ANY (?)
                    ORDER BY st.trip_id, st.stop_sequence
                    """, rs -> {
                trip.add(rs.getString(1), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4),
                        (Integer) rs.getObject(5), rs.getInt(6), rs.getInt(7));
            }, serviceArray);
            trip.flush();

            Timetable tt = builder.build(MAX_TRANSFER_WALK_METERS);
            log.info("Timetable for {}: {} trips on {} services, {} patterns, loaded in {} ms", date, trip.trips,
                    services.size(), tt.patterns.length, (System.nanoTime() - started) / 1_000_000);
            return tt;
        });
    }

    /** Services running on a date: the weekly calendar, plus added dates, minus removed dates (holidays). */
    private List<String> activeServices(LocalDate date) {
        String weekday = date.getDayOfWeek().name().toLowerCase(); // column name, e.g. "tuesday"
        return jdbc.queryForList("""
                SELECT service_id FROM service_calendar
                WHERE ? BETWEEN start_date AND end_date AND %s
                UNION
                SELECT service_id FROM service_calendar_date WHERE service_date = ? AND exception_type = 1
                EXCEPT
                SELECT service_id FROM service_calendar_date WHERE service_date = ? AND exception_type = 2
                """.formatted(weekday), String.class, Date.valueOf(date), Date.valueOf(date), Date.valueOf(date));
    }

    /** Gathers consecutive rows of the same trip and hands each finished trip to the builder. */
    private static final class TripCollector {
        private final Timetable.Builder builder;
        private final Map<String, RouteInfo> routes;
        private String tripId;
        private String routeId;
        private final List<int[]> rows = new ArrayList<>();
        int trips;

        TripCollector(Timetable.Builder builder, Map<String, RouteInfo> routes) {
            this.builder = builder;
            this.routes = routes;
        }

        void add(String trip, String route, String stopId, Integer arrival, Integer departure, int pickup, int dropOff) {
            if (!trip.equals(tripId)) {
                flush();
                tripId = trip;
                routeId = route;
            }
            Integer stop = builder.stop(stopId);
            if (stop != null) {
                rows.add(new int[] {stop, arrival == null ? -1 : arrival, departure == null ? -1 : departure,
                        pickup, dropOff});
            }
        }

        void flush() {
            if (tripId != null && rows.size() >= 2) {
                int n = rows.size();
                int[] stops = new int[n];
                int[] arr = new int[n];
                int[] dep = new int[n];
                boolean[] board = new boolean[n];
                boolean[] alight = new boolean[n];
                for (int i = 0; i < n; i++) {
                    int[] r = rows.get(i);
                    stops[i] = r[0];
                    arr[i] = r[1];
                    dep[i] = r[2];
                    board[i] = r[3] != 1 && i < n - 1;   // pickup_type 1 = no boarding here
                    alight[i] = r[4] != 1 && i > 0;      // drop_off_type 1 = no getting off here
                }
                builder.addTrip(tripId, routes.get(routeId), stops, arr, dep, board, alight);
                trips++;
            }
            rows.clear();
            tripId = null;
        }
    }
}
