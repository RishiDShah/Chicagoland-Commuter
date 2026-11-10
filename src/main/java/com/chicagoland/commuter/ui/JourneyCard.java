package com.chicagoland.commuter.ui;

import com.chicagoland.commuter.routing.Journey;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** One trip option: a summary line, then each step (ride or walk). */
class JourneyCard extends Div {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    JourneyCard(Journey journey) {
        getStyle()
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("padding", "var(--lumo-space-m)")
                .set("width", "100%")
                .set("box-sizing", "border-box");

        var times = new Div(clock(journey.leaveAt()) + " → " + clock(journey.arriveAt()));
        times.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-l)");
        int transfers = journey.transfers();
        var meta = new Div(duration(journey.durationSecs()) + " · "
                + (transfers == 0 ? "no transfers" : transfers == 1 ? "1 transfer" : transfers + " transfers"));
        meta.getStyle().set("color", "var(--lumo-secondary-text-color)").set("margin-bottom", "var(--lumo-space-s)");
        add(times, meta);

        for (Journey.Leg leg : journey.legs()) {
            add(switch (leg) {
                case Journey.Ride ride -> rideRow(ride);
                case Journey.Walk walk -> walkRow(walk);
            });
        }
    }

    private static Div rideRow(Journey.Ride ride) {
        var badge = new Span(ride.route().displayName());
        String color = ride.route().color() == null || ride.route().color().isBlank() ? "565a5c" : ride.route().color();
        badge.getStyle()
                .set("background", "#" + color)
                .set("color", textColorFor(color))
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("padding", "0 var(--lumo-space-xs)")
                .set("font-weight", "600")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("margin-right", "var(--lumo-space-xs)");
        var toward = new Span("toward " + ride.toward());
        toward.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        var detail = new Div(clock(ride.start()) + "  " + ride.fromStop() + "\n" + clock(ride.end()) + "  " + ride.toStop()
                + "  (" + ride.stopsTraveled() + (ride.stopsTraveled() == 1 ? " stop)" : " stops)"));
        detail.getStyle().set("white-space", "pre-line").set("font-size", "var(--lumo-font-size-s)")
                .set("margin", "2px 0 0 var(--lumo-space-s)");
        var row = new Div(new Div(badge, toward), detail);
        row.getStyle().set("margin", "var(--lumo-space-s) 0");
        return row;
    }

    private static Div walkRow(Journey.Walk walk) {
        String where = "Destination".equals(walk.toStop()) ? "to your destination"
                : "Start".equals(walk.fromStop()) ? "to " + walk.toStop()
                : "to " + walk.toStop();
        var row = new Div("Walk " + walk.minutes() + " min " + where);
        row.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)")
                .set("margin", "var(--lumo-space-xs) 0 var(--lumo-space-xs) var(--lumo-space-s)");
        return row;
    }

    /** Seconds after midnight → "7:28 AM" (trips after midnight wrap around). */
    static String clock(int secs) {
        return LocalTime.ofSecondOfDay(Math.floorMod(secs, 86_400)).format(CLOCK);
    }

    static String duration(int secs) {
        int minutes = (int) Math.round(secs / 60.0);
        return minutes < 60 ? minutes + " min" : minutes / 60 + " h " + minutes % 60 + " min";
    }

    /** Black or white text, whichever reads better on the line's color. */
    private static String textColorFor(String hex) {
        int rgb = Integer.parseInt(hex, 16);
        double luminance = (0.299 * (rgb >> 16 & 255) + 0.587 * (rgb >> 8 & 255) + 0.114 * (rgb & 255)) / 255;
        return luminance > 0.6 ? "#000" : "#fff";
    }
}
