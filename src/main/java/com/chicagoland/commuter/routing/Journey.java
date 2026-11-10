package com.chicagoland.commuter.routing;

import java.util.List;

/**
 * One way to make the trip. Times are seconds after midnight of the travel day.
 *
 * @param leaveAt when to start walking from the starting point
 * @param arriveAt when you reach the destination
 */
public record Journey(int leaveAt, int arriveAt, List<Leg> legs) {

    public int transfers() {
        return Math.max(0, (int) legs.stream().filter(l -> l instanceof Ride).count() - 1);
    }

    public int durationSecs() {
        return arriveAt - leaveAt;
    }

    public sealed interface Leg permits Ride, Walk {
        int start();

        int end();
    }

    /** Riding one vehicle from where you board to where you get off. */
    public record Ride(RouteInfo route, String toward, String fromStop, String toStop, int start, int end,
                       int stopsTraveled, String tripId) implements Leg {
    }

    /** Walking between stops, or to/from the start and destination. */
    public record Walk(String fromStop, String toStop, int start, int end) implements Leg {
        public int minutes() {
            return Math.max(1, (int) Math.round((end - start) / 60.0));
        }
    }
}
