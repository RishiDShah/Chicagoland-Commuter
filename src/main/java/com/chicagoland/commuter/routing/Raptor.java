package com.chicagoland.commuter.routing;

import com.chicagoland.commuter.routing.Journey.Leg;
import com.chicagoland.commuter.routing.Timetable.Pattern;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * RAPTOR: Round-bAsed Public Transit Optimized Router (Delling, Pajor and Werneck, 2012).
 *
 * <p>Round k finds the earliest time you can reach every stop using exactly k vehicles:
 * <ol>
 *   <li>Look at every stop that got faster in the previous round.</li>
 *   <li>For every line passing through those stops, ride the earliest trip you can catch and
 *       record the arrival time at each later stop on that line.</li>
 *   <li>From every stop just reached, walk to nearby stops (e.g. the Metra platform to the bus stop).</li>
 * </ol>
 * Each round adds one more vehicle, so the results naturally include "fastest with 0 transfers",
 * "fastest with 1 transfer", and so on.
 */
public final class Raptor {

    /** At most this many vehicles (so 4 transfers). */
    static final int MAX_ROUNDS = 5;
    /** Time added after you arrive at a stop before you can board the next vehicle there. */
    static final int TRANSFER_BUFFER_SECS = 120;

    private static final int INF = Integer.MAX_VALUE / 2;
    private static final byte NONE = 0;
    private static final byte ACCESS = 1;
    private static final byte RIDE = 2;
    private static final byte WALK = 3;

    private final Timetable tt;

    public Raptor(Timetable tt) {
        this.tt = tt;
    }

    /**
     * Finds the fastest journey for each number of transfers (only those that beat every journey
     * with fewer transfers).
     *
     * @param access stops you can start from, with the walking time to each
     * @param depart earliest time you can leave
     * @param egress stops you can finish at, with the walking time from each to the destination
     */
    public List<Journey> search(Map<Integer, Integer> access, int depart, Map<Integer, Integer> egress) {
        int n = tt.stopCount();
        int rounds = MAX_ROUNDS + 1;
        int[][] arrival = new int[rounds][n];
        int[][] ready = new int[rounds][n];
        // How each stop was reached in each round. Rides and walks are kept apart so a walk
        // never overwrites the ride it started from.
        Path path = new Path(rounds, n);
        for (int k = 0; k < rounds; k++) {
            Arrays.fill(arrival[k], INF);
            Arrays.fill(ready[k], INF);
        }
        int[] best = new int[n];
        Arrays.fill(best, INF);

        int[] egressSecs = new int[n];
        Arrays.fill(egressSecs, -1);
        egress.forEach((stop, secs) -> egressSecs[stop] = secs);

        // Round 0: walk from the start to the nearby stops
        boolean[] marked = new boolean[n];
        List<Integer> markedStops = new ArrayList<>();
        for (var e : access.entrySet()) {
            int s = e.getKey();
            int t = depart + e.getValue();
            if (t < arrival[0][s]) {
                arrival[0][s] = t;
                ready[0][s] = t;
                best[s] = t;
                path.kind[0][s] = ACCESS;
                if (!marked[s]) {
                    marked[s] = true;
                    markedStops.add(s);
                }
            }
        }

        List<Journey> journeys = new ArrayList<>();
        int bestAtDestination = INF;
        // A walk-only trip when the start is close enough to the destination
        for (int s : markedStops) {
            if (egressSecs[s] >= 0 && arrival[0][s] + egressSecs[s] < bestAtDestination) {
                bestAtDestination = arrival[0][s] + egressSecs[s];
            }
        }
        if (bestAtDestination < INF) {
            journeys.add(walkOnly(depart, bestAtDestination));
        }

        int[] firstPosition = new int[tt.patterns.length];
        Arrays.fill(firstPosition, -1);

        for (int k = 1; k < rounds && !markedStops.isEmpty(); k++) {
            // 1. Which lines pass through stops that improved last round, and the earliest such stop on each
            List<Integer> patternsToScan = new ArrayList<>();
            for (int s : markedStops) {
                marked[s] = false;
                int[] pats = tt.stopPatterns[s];
                int[] positions = tt.stopPatternPositions[s];
                for (int j = 0; j < pats.length; j++) {
                    int p = pats[j];
                    if (firstPosition[p] < 0) {
                        patternsToScan.add(p);
                        firstPosition[p] = positions[j];
                    } else if (positions[j] < firstPosition[p]) {
                        firstPosition[p] = positions[j];
                    }
                }
            }
            markedStops = new ArrayList<>();

            // 2. Ride each line from that stop onward
            List<Integer> reachedByRide = new ArrayList<>();
            for (int p : patternsToScan) {
                Pattern pattern = tt.patterns[p];
                int trip = -1;
                int boardPos = -1;
                for (int pos = firstPosition[p]; pos < pattern.stops.length; pos++) {
                    int s = pattern.stops[pos];
                    if (trip >= 0 && pattern.canAlight(trip, pos)) {
                        int t = pattern.arrival(trip, pos);
                        if (t < best[s] && t < bestAtDestination) {
                            arrival[k][s] = t;
                            best[s] = t;
                            path.kind[k][s] = RIDE;
                            path.rideArrival[k][s] = t;
                            path.ridePattern[k][s] = p;
                            path.rideTrip[k][s] = trip;
                            path.rideBoard[k][s] = boardPos;
                            path.rideAlight[k][s] = pos;
                            if (!marked[s]) {
                                marked[s] = true;
                                markedStops.add(s);
                            }
                            reachedByRide.add(s);
                        }
                    }
                    // Could we have caught this trip, or an earlier one, here?
                    int readyAt = ready[k - 1][s];
                    if (readyAt < INF && (trip < 0 || readyAt <= pattern.departure(trip, pos))) {
                        int earlier = pattern.earliestTrip(pos, readyAt);
                        if (earlier >= 0 && (trip < 0 || earlier < trip)) {
                            trip = earlier;
                            boardPos = pos;
                        }
                    }
                }
                firstPosition[p] = -1;
            }

            // 3. Walk from stops reached by a vehicle this round to nearby stops
            for (int s : new LinkedHashSet<>(reachedByRide)) {
                for (int i = tt.walkStart[s]; i < tt.walkStart[s + 1]; i++) {
                    int to = tt.walkTo[i];
                    int t = path.rideArrival[k][s] + tt.walkSecs[i];
                    if (t < best[to] && t < bestAtDestination) {
                        arrival[k][to] = t;
                        best[to] = t;
                        path.kind[k][to] = WALK;
                        path.walkFrom[k][to] = s;
                        if (!marked[to]) {
                            marked[to] = true;
                            markedStops.add(to);
                        }
                    }
                }
            }

            for (int s : markedStops) {
                ready[k][s] = arrival[k][s] + TRANSFER_BUFFER_SECS;
            }

            // Did this round get us to the destination sooner?
            int bestStop = -1;
            for (int s : markedStops) {
                if (egressSecs[s] >= 0 && arrival[k][s] + egressSecs[s] < bestAtDestination) {
                    bestAtDestination = arrival[k][s] + egressSecs[s];
                    bestStop = s;
                }
            }
            if (bestStop >= 0) {
                journeys.add(rebuild(k, bestStop, path, arrival, access, egressSecs[bestStop]));
            }
        }
        return journeys;
    }

    /** Remembers how each stop was reached, per round, so the itinerary can be rebuilt afterwards. */
    private static final class Path {
        final byte[][] kind;
        final int[][] rideArrival;
        final int[][] ridePattern;
        final int[][] rideTrip;
        final int[][] rideBoard;
        final int[][] rideAlight;
        final int[][] walkFrom;

        Path(int rounds, int stops) {
            kind = new byte[rounds][stops];
            rideArrival = new int[rounds][stops];
            ridePattern = new int[rounds][stops];
            rideTrip = new int[rounds][stops];
            rideBoard = new int[rounds][stops];
            rideAlight = new int[rounds][stops];
            walkFrom = new int[rounds][stops];
        }
    }

    /** Follows the "how did I get here" records back from the final stop to the start. */
    private Journey rebuild(int round, int stop, Path path, int[][] arrival, Map<Integer, Integer> access,
                            int egressSecs) {
        List<Leg> legs = new ArrayList<>();
        int k = round;
        int s = stop;
        boolean mustBeRide = false;
        while (true) {
            byte how = mustBeRide ? RIDE : path.kind[k][s];
            mustBeRide = false;
            if (how == WALK) {
                int from = path.walkFrom[k][s];
                legs.add(new Journey.Walk(tt.stopNames[from], tt.stopNames[s], path.rideArrival[k][from],
                        arrival[k][s]));
                s = from;
                mustBeRide = true; // a walk always starts where a vehicle dropped you off
            } else if (how == RIDE) {
                Pattern pattern = tt.patterns[path.ridePattern[k][s]];
                int trip = path.rideTrip[k][s];
                int boardPos = path.rideBoard[k][s];
                int alightPos = path.rideAlight[k][s];
                int from = pattern.stops[boardPos];
                legs.add(new Journey.Ride(pattern.route, tt.stopNames[pattern.stops[pattern.stops.length - 1]],
                        tt.stopNames[from], tt.stopNames[s], pattern.departure(trip, boardPos),
                        pattern.arrival(trip, alightPos), alightPos - boardPos, pattern.tripIds[trip]));
                s = from;
                k--;
            } else if (how == ACCESS) {
                break;
            } else {
                throw new IllegalStateException("broken path at stop " + tt.stopIds[s]);
            }
        }
        Collections.reverse(legs);

        // Leave just in time for the first vehicle, not at the time you asked
        int accessSecs = access.get(s);
        int leaveAt = legs.getFirst().start() - accessSecs;
        if (accessSecs > 0) {
            legs.addFirst(new Journey.Walk("Start", tt.stopNames[s], leaveAt, leaveAt + accessSecs));
        }
        int lastEnd = legs.getLast().end();
        int arriveAt = lastEnd + egressSecs;
        if (egressSecs > 0) {
            legs.add(new Journey.Walk(tt.stopNames[stop], "Destination", lastEnd, arriveAt));
        }
        return new Journey(leaveAt, arriveAt, List.copyOf(legs));
    }

    private static Journey walkOnly(int depart, int arriveAt) {
        return new Journey(depart, arriveAt, List.of(new Journey.Walk("Start", "Destination", depart, arriveAt)));
    }
}
