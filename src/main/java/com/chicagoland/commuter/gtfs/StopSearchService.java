package com.chicagoland.commuter.gtfs;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Finds stations and stops by name for the From and To boxes. */
@Service
public class StopSearchService {

    public record StopSuggestion(String stopId, String name, String feedId, int locationType) {

        /** e.g. "Bartlett · Metra station" or "UIC-Halsted · CTA 'L' station". */
        public String label() {
            return name + " · " + kind();
        }

        public String kind() {
            return switch (feedId) {
                case "metra" -> "Metra station";
                case "cta" -> locationType == 1 ? "CTA 'L' station" : "CTA bus stop";
                case "pace" -> "Pace bus stop";
                default -> feedId;
            };
        }
    }

    private final JdbcTemplate jdbc;

    public StopSearchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stops whose name contains the text. Train stations come first, then names that start with the text.
     * Platforms inside a station are left out (the station itself is listed), and a bus stop with the same
     * name on both sides of the street is listed once.
     */
    public List<StopSuggestion> search(String text, int offset, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String q = text.trim().toLowerCase();
        return jdbc.query("""
                SELECT stop_id, name, feed_id, location_type FROM (
                    SELECT DISTINCT ON (feed_id, lower(name)) stop_id, name, feed_id, location_type
                    FROM stop
                    WHERE parent_station IS NULL AND lower(name) LIKE '%' || ? || '%'
                    ORDER BY feed_id, lower(name), stop_id
                ) s
                ORDER BY (feed_id = 'metra' OR location_type = 1) DESC,
                         lower(name) LIKE ? || '%' DESC,
                         length(name), name
                OFFSET ? LIMIT ?
                """,
                (rs, i) -> new StopSuggestion(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                escapeLike(q), escapeLike(q), offset, limit);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
