package com.chicagoland.commuter.ui;

import com.chicagoland.commuter.routing.Journey;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** One trip option: leave and arrive times, then each ride (with its line color) and walk. */
class JourneyCard extends Div {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    JourneyCard(Journey journey, boolean best) {
        addClassName("journey");

        var times = new Div(clock(journey.leaveAt()) + " → " + clock(journey.arriveAt()));
        times.addClassName("journey-times");
        int transfers = journey.transfers();
        var meta = new Div(duration(journey.durationSecs()) + " · "
                + (transfers == 0 ? "no transfers" : transfers == 1 ? "1 transfer" : transfers + " transfers"));
        meta.addClassName("muted");
        var head = new Div(new Div(times, meta));
        head.addClassName("journey-head");
        if (best) {
            var tag = new Span("Best");
            tag.addClassNames("tag", "tag-best");
            head.add(tag);
        }

        var legs = new Div();
        legs.addClassName("legs");
        for (Journey.Leg leg : journey.legs()) {
            legs.add(switch (leg) {
                case Journey.Ride ride -> rideRow(ride);
                case Journey.Walk walk -> walkRow(walk);
            });
        }
        add(head, legs);
    }

    private static Div rideRow(Journey.Ride ride) {
        String color = ride.route().color() == null || ride.route().color().isBlank()
                ? "565A5C" : ride.route().color();

        var line = new Span(ride.route().displayName());
        line.addClassName("line-tag");
        line.getStyle().set("background", "#" + color).set("color", textColorFor(color));
        var toward = new Span("toward " + ride.toward());
        toward.addClassName("toward");
        var head = new Div(line, toward);
        head.addClassName("ride-head");

        var count = new Span(" · " + ride.stopsTraveled() + (ride.stopsTraveled() == 1 ? " stop" : " stops"));
        count.addClassName("count");
        var body = new Div(stopRow(ride.start(), new Span(ride.fromStop())),
                stopRow(ride.end(), new Span(new Span(ride.toStop()), count)));
        body.addClassName("ride-body");
        body.getStyle().set("--line-color", "#" + color);

        var row = new Div(head, body);
        row.addClassName("ride");
        return row;
    }

    private static Div stopRow(int time, Span stop) {
        var t = new Span(clock(time));
        t.addClassName("time");
        var row = new Div(t, stop);
        row.addClassName("stop-row");
        return row;
    }

    private static Div walkRow(Journey.Walk walk) {
        String where = "Destination".equals(walk.toStop()) ? "to your destination" : "to " + walk.toStop();
        var row = new Div("Walk " + walk.minutes() + " min " + where);
        row.addClassName("walk");
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
