package com.chicagoland.commuter.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One day's schedule, laid out in plain arrays so RAPTOR can scan it quickly.
 *
 * <p>Trips that stop at exactly the same stops in the same order are grouped into a <em>pattern</em>
 * (RAPTOR calls these "routes"). Inside a pattern, trips are sorted so an earlier trip never arrives
 * anywhere later than the trip after it, which lets us find "the next departure" with a binary search.
 *
 * <p>All times are seconds after midnight of the service day.
 */
public final class Timetable {

    /** Walking speed and a factor for streets not being straight lines. */
    static final double WALK_SPEED_METERS_PER_SEC = 1.3;
    static final double WALK_DETOUR_FACTOR = 1.3;

    final String[] stopIds;
    final String[] stopNames;
    final double[] lat;
    final double[] lon;
    final Map<String, Integer> stopIndex;

    final Pattern[] patterns;
    /** For each stop: which patterns serve it, and at which position in the pattern. */
    final int[][] stopPatterns;
    final int[][] stopPatternPositions;

    /** Walking connections between nearby stops, in compressed form: stop p's are walkStart[p]..walkStart[p+1]. */
    final int[] walkStart;
    final int[] walkTo;
    final int[] walkSecs;

    /** All trips of a pattern share the stops; times are stored trip by trip: index = trip * stopCount + position. */
    static final class Pattern {
        final RouteInfo route;
        final int[] stops;
        final String[] tripIds;
        final int[] arrival;
        final int[] departure;
        final boolean[] canBoard;
        final boolean[] canAlight;

        Pattern(RouteInfo route, int[] stops, List<TripData> trips) {
            this.route = route;
            this.stops = stops;
            int n = stops.length;
            this.tripIds = new String[trips.size()];
            this.arrival = new int[trips.size() * n];
            this.departure = new int[trips.size() * n];
            this.canBoard = new boolean[trips.size() * n];
            this.canAlight = new boolean[trips.size() * n];
            for (int t = 0; t < trips.size(); t++) {
                TripData trip = trips.get(t);
                tripIds[t] = trip.tripId;
                System.arraycopy(trip.arrival, 0, arrival, t * n, n);
                System.arraycopy(trip.departure, 0, departure, t * n, n);
                System.arraycopy(trip.canBoard, 0, canBoard, t * n, n);
                System.arraycopy(trip.canAlight, 0, canAlight, t * n, n);
            }
        }

        int tripCount() {
            return tripIds.length;
        }

        int arrival(int trip, int pos) {
            return arrival[trip * stops.length + pos];
        }

        int departure(int trip, int pos) {
            return departure[trip * stops.length + pos];
        }

        boolean canBoard(int trip, int pos) {
            return canBoard[trip * stops.length + pos];
        }

        boolean canAlight(int trip, int pos) {
            return canAlight[trip * stops.length + pos];
        }

        /** The first trip you can board at {@code pos} leaving at or after {@code time}, or -1. */
        int earliestTrip(int pos, int time) {
            int lo = 0;
            int hi = tripCount();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (departure(mid, pos) < time) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            for (int t = lo; t < tripCount(); t++) {
                if (canBoard(t, pos)) {
                    return t;
                }
            }
            return -1;
        }
    }

    record TripData(String tripId, RouteInfo route, int[] stops, int[] arrival, int[] departure,
                    boolean[] canBoard, boolean[] canAlight) {
    }

    private Timetable(Builder b, Pattern[] patterns, double maxWalkMeters) {
        int stopCount = b.stopIds.size();
        this.stopIds = b.stopIds.toArray(String[]::new);
        this.stopNames = b.stopNames.toArray(String[]::new);
        this.lat = b.lat.stream().mapToDouble(Double::doubleValue).toArray();
        this.lon = b.lon.stream().mapToDouble(Double::doubleValue).toArray();
        this.stopIndex = Map.copyOf(b.stopIndex);
        this.patterns = patterns;

        int[] counts = new int[stopCount];
        for (Pattern p : patterns) {
            for (int s : p.stops) {
                counts[s]++;
            }
        }
        stopPatterns = new int[stopCount][];
        stopPatternPositions = new int[stopCount][];
        for (int s = 0; s < stopCount; s++) {
            stopPatterns[s] = new int[counts[s]];
            stopPatternPositions[s] = new int[counts[s]];
        }
        int[] filled = new int[stopCount];
        for (int pi = 0; pi < patterns.length; pi++) {
            int[] stops = patterns[pi].stops;
            for (int pos = 0; pos < stops.length; pos++) {
                int s = stops[pos];
                stopPatterns[s][filled[s]] = pi;
                stopPatternPositions[s][filled[s]] = pos;
                filled[s]++;
            }
        }

        // Walking connections between stops that some trip actually serves
        boolean[] served = new boolean[stopCount];
        for (int s = 0; s < stopCount; s++) {
            served[s] = counts[s] > 0;
        }
        List<int[]> edges = nearbyPairs(served, maxWalkMeters);
        walkStart = new int[stopCount + 1];
        for (int[] e : edges) {
            walkStart[e[0] + 1]++;
        }
        for (int s = 0; s < stopCount; s++) {
            walkStart[s + 1] += walkStart[s];
        }
        walkTo = new int[edges.size()];
        walkSecs = new int[edges.size()];
        int[] next = Arrays.copyOf(walkStart, stopCount);
        for (int[] e : edges) {
            int i = next[e[0]]++;
            walkTo[i] = e[1];
            walkSecs[i] = e[2];
        }
    }

    public int stopCount() {
        return stopIds.length;
    }

    public Integer stopIndex(String stopId) {
        return stopIndex.get(stopId);
    }

    /** Walking time in seconds for a straight-line distance. */
    static int walkSeconds(double meters) {
        return (int) Math.round(meters * WALK_DETOUR_FACTOR / WALK_SPEED_METERS_PER_SEC);
    }

    /** Distance in meters between two stops (accurate enough at city scale). */
    double meters(int a, int b) {
        return meters(lat[a], lon[a], lat[b], lon[b]);
    }

    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * 6_371_000;
    }

    /** Stops within {@code maxMeters} of a point, with the walking time to each. */
    Map<Integer, Integer> stopsNear(double pointLat, double pointLon, double maxMeters) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int s = 0; s < stopCount(); s++) {
            if (stopPatterns[s].length == 0) {
                continue;
            }
            double d = meters(pointLat, pointLon, lat[s], lon[s]);
            if (d <= maxMeters) {
                result.put(s, walkSeconds(d));
            }
        }
        return result;
    }

    /** Finds pairs of stops within walking distance using a grid, so we don't compare every pair. */
    private List<int[]> nearbyPairs(boolean[] include, double maxMeters) {
        double cellLat = maxMeters / 111_320.0;
        double cellLon = maxMeters / (111_320.0 * Math.cos(Math.toRadians(41.9)));
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int s = 0; s < stopCount(); s++) {
            if (include[s]) {
                grid.computeIfAbsent(cell(lat[s], cellLat, lon[s], cellLon), k -> new ArrayList<>()).add(s);
            }
        }
        List<int[]> edges = new ArrayList<>();
        for (int s = 0; s < stopCount(); s++) {
            if (!include[s]) {
                continue;
            }
            long cy = (long) Math.floor(lat[s] / cellLat);
            long cx = (long) Math.floor(lon[s] / cellLon);
            for (long dy = -1; dy <= 1; dy++) {
                for (long dx = -1; dx <= 1; dx++) {
                    for (int other : grid.getOrDefault(key(cy + dy, cx + dx), List.of())) {
                        if (other != s) {
                            double d = meters(s, other);
                            if (d <= maxMeters) {
                                edges.add(new int[] {s, other, walkSeconds(d)});
                            }
                        }
                    }
                }
            }
        }
        return edges;
    }

    private static long cell(double lat, double cellLat, double lon, double cellLon) {
        return key((long) Math.floor(lat / cellLat), (long) Math.floor(lon / cellLon));
    }

    private static long key(long y, long x) {
        return (y << 32) ^ (x & 0xffffffffL);
    }

    /** Collects stops and trips, then builds the timetable. Used by the database loader and by tests. */
    public static final class Builder {
        private final List<String> stopIds = new ArrayList<>();
        private final List<String> stopNames = new ArrayList<>();
        private final List<Double> lat = new ArrayList<>();
        private final List<Double> lon = new ArrayList<>();
        private final Map<String, Integer> stopIndex = new HashMap<>();
        private final List<TripData> trips = new ArrayList<>();

        public int addStop(String id, String name, double latitude, double longitude) {
            Integer existing = stopIndex.get(id);
            if (existing != null) {
                return existing;
            }
            stopIds.add(id);
            stopNames.add(name);
            lat.add(latitude);
            lon.add(longitude);
            stopIndex.put(id, stopIds.size() - 1);
            return stopIds.size() - 1;
        }

        public Integer stop(String id) {
            return stopIndex.get(id);
        }

        /**
         * Adds one trip. Use -1 for a missing time (GTFS lets stops between timepoints leave it blank);
         * those are filled in by spreading the time evenly between the known stops around them.
         */
        public void addTrip(String tripId, RouteInfo route, int[] stops, int[] arrival, int[] departure,
                            boolean[] canBoard, boolean[] canAlight) {
            int n = stops.length;
            if (n < 2) {
                return;
            }
            int[] arr = arrival.clone();
            int[] dep = departure.clone();
            for (int i = 0; i < n; i++) {
                if (arr[i] < 0) {
                    arr[i] = dep[i];
                }
                if (dep[i] < 0) {
                    dep[i] = arr[i];
                }
            }
            if (!interpolate(arr, dep)) {
                return; // no usable times at all
            }
            trips.add(new TripData(tripId, route, stops, arr, dep, canBoard, canAlight));
        }

        private static boolean interpolate(int[] arr, int[] dep) {
            int n = arr.length;
            int prev = -1;
            for (int i = 0; i < n; i++) {
                if (dep[i] >= 0) {
                    if (prev >= 0 && i - prev > 1) {
                        for (int j = prev + 1; j < i; j++) {
                            int t = dep[prev] + (arr[i] - dep[prev]) * (j - prev) / (i - prev);
                            arr[j] = t;
                            dep[j] = t;
                        }
                    }
                    prev = i;
                }
            }
            // Times must be known at the first and last stop to be usable
            return dep[0] >= 0 && arr[n - 1] >= 0;
        }

        public Timetable build(double maxWalkMeters) {
            // Group trips with the same line and the same stops
            Map<String, List<TripData>> groups = new HashMap<>();
            for (TripData trip : trips) {
                String key = trip.route.routeId() + "|" + Arrays.toString(trip.stops);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(trip);
            }
            List<Pattern> patterns = new ArrayList<>();
            for (List<TripData> group : groups.values()) {
                group.sort(Comparator.comparingInt((TripData t) -> t.departure[0]).thenComparingInt(t -> t.arrival[1]));
                // A trip that overtakes another (leaves later but arrives earlier somewhere) goes into
                // its own pattern, so trips in each pattern stay in order at every stop.
                List<List<TripData>> ordered = new ArrayList<>();
                for (TripData trip : group) {
                    List<TripData> target = null;
                    for (List<TripData> candidate : ordered) {
                        if (neverOvertakes(candidate.getLast(), trip)) {
                            target = candidate;
                            break;
                        }
                    }
                    if (target == null) {
                        target = new ArrayList<>();
                        ordered.add(target);
                    }
                    target.add(trip);
                }
                for (List<TripData> list : ordered) {
                    patterns.add(new Pattern(list.getFirst().route, list.getFirst().stops, list));
                }
            }
            return new Timetable(this, patterns.toArray(Pattern[]::new), maxWalkMeters);
        }

        private static boolean neverOvertakes(TripData earlier, TripData later) {
            for (int i = 0; i < earlier.stops.length; i++) {
                if (later.departure[i] < earlier.departure[i] || later.arrival[i] < earlier.arrival[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
