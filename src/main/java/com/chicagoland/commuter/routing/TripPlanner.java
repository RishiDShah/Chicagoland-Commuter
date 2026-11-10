package com.chicagoland.commuter.routing;

import com.chicagoland.commuter.gtfs.SchedulesImportedEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Plans trips between two stations: picks the stops to start and end at, then runs RAPTOR. */
@Service
public class TripPlanner {

    private static final Logger log = LoggerFactory.getLogger(TripPlanner.class);

    /** How far you're willing to walk from the chosen start, or to the chosen destination. */
    static final double MAX_ACCESS_WALK_METERS = 500;
    /** Each transfer counts as this many extra seconds when choosing the best option. */
    static final int TRANSFER_PENALTY_SECS = 10 * 60;

    public record Plan(List<Journey> options, String message) {
    }

    private final TimetableLoader loader;
    private final Map<LocalDate, Timetable> cache = new LinkedHashMap<>();

    public TripPlanner(TimetableLoader loader) {
        this.loader = loader;
    }

    /**
     * Up to {@code count} options leaving at or after {@code when}, each one leaving later than the last.
     * Times in the journeys are seconds after midnight of {@code when}'s date.
     */
    public Plan plan(String fromStopId, String toStopId, LocalDateTime when, int count) {
        Timetable tt = timetable(when.toLocalDate());
        Integer from = tt.stopIndex(fromStopId);
        Integer to = tt.stopIndex(toStopId);
        if (from == null || to == null) {
            return new Plan(List.of(), "That station isn't in today's schedules.");
        }
        if (from.equals(to)) {
            return new Plan(List.of(), "Start and destination are the same.");
        }
        Map<Integer, Integer> access = tt.stopsNear(tt.lat[from], tt.lon[from], MAX_ACCESS_WALK_METERS);
        Map<Integer, Integer> egress = tt.stopsNear(tt.lat[to], tt.lon[to], MAX_ACCESS_WALK_METERS);
        if (access.isEmpty() || egress.isEmpty()) {
            return new Plan(List.of(), "No service near that station on " + when.toLocalDate() + ".");
        }

        var raptor = new Raptor(tt);
        List<Journey> options = new ArrayList<>();
        int depart = when.toLocalTime().toSecondOfDay();
        for (int i = 0; i < count; i++) {
            List<Journey> found = raptor.search(access, depart, egress);
            if (found.isEmpty()) {
                break;
            }
            Journey best = found.stream()
                    .min(Comparator.comparingInt((Journey j) -> j.arriveAt() + TRANSFER_PENALTY_SECS * j.transfers()))
                    .orElseThrow();
            options.add(best);
            if (best.legs().stream().noneMatch(l -> l instanceof Journey.Ride)) {
                break; // walking is the answer; no point searching later departures
            }
            depart = best.leaveAt() + 60;
        }
        String message = options.isEmpty() ? "No trips found for the rest of that day." : null;
        return new Plan(options, message);
    }

    /** One day's timetable, built once and reused. Keeps the two most recent days. */
    private synchronized Timetable timetable(LocalDate date) {
        Timetable tt = cache.get(date);
        if (tt == null) {
            tt = loader.load(date);
            cache.put(date, tt);
            while (cache.size() > 2) {
                cache.remove(cache.keySet().iterator().next());
            }
        }
        return tt;
    }

    /** New schedules were imported: forget old timetables and get today's ready in the background. */
    @EventListener
    public void onSchedulesImported(SchedulesImportedEvent event) {
        synchronized (this) {
            cache.clear();
        }
        Thread.ofVirtual().name("timetable-preload").start(() -> {
            try {
                timetable(LocalDate.now(java.time.ZoneId.of("America/Chicago")));
            } catch (RuntimeException e) {
                log.warn("Could not prepare today's timetable", e);
            }
        });
    }
}
