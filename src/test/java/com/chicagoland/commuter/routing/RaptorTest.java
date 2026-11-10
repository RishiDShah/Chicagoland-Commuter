package com.chicagoland.commuter.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * RAPTOR on a tiny made-up network (no database). Stops sit on a line about 1 km apart,
 * except B2, which is 100 m from B (a short walk).
 */
class RaptorTest {

    static final RouteInfo TRAIN = new RouteInfo("t", "metra", "MD-W", "Milwaukee District West", 2, null);
    static final RouteInfo BUS = new RouteInfo("b", "cta", "157", "Streeterville/Taylor", 3, null);
    static final RouteInfo SLOW_BUS = new RouteInfo("s", "pace", "999", "Slow Local", 3, null);

    final Timetable.Builder builder = new Timetable.Builder();
    final int a = builder.addStop("A", "A", 41.90, -87.70);
    final int b = builder.addStop("B", "B", 41.90, -87.688);
    final int b2 = builder.addStop("B2", "B2", 41.90, -87.6868);   // ~100 m east of B
    final int c = builder.addStop("C", "C", 41.90, -87.664);
    final int x = builder.addStop("X", "X", 41.95, -87.70);        // far away, unrelated

    static int t(int h, int m) {
        return h * 3600 + m * 60;
    }

    void trip(String id, RouteInfo route, int[] stops, int... times) {
        boolean[] board = new boolean[stops.length];
        boolean[] alight = new boolean[stops.length];
        Arrays.fill(board, true);
        Arrays.fill(alight, true);
        builder.addTrip(id, route, stops, times, times, board, alight);
    }

    List<Journey> search(int from, int depart, int to) {
        Timetable tt = builder.build(500);
        return new Raptor(tt).search(Map.of(from, 0), depart, Map.of(to, 0));
    }

    @Test
    void ridesThenWalksThenTransfers() {
        trip("train1", TRAIN, new int[] {a, b}, t(7, 0), t(7, 20));
        trip("bus1", BUS, new int[] {b2, c}, t(7, 21), t(7, 30));   // too soon: walk + 2 min buffer
        trip("bus2", BUS, new int[] {b2, c}, t(7, 25), t(7, 35));

        List<Journey> found = search(a, t(6, 50), c);

        assertThat(found).hasSize(1);
        Journey j = found.getFirst();
        assertThat(j.leaveAt()).isEqualTo(t(7, 0));
        assertThat(j.arriveAt()).isEqualTo(t(7, 35));
        assertThat(j.transfers()).isEqualTo(1);
        assertThat(j.legs()).hasSize(3);
        assertThat(j.legs().get(0)).isInstanceOf(Journey.Ride.class);
        assertThat(((Journey.Ride) j.legs().get(0)).toStop()).isEqualTo("B");
        assertThat(j.legs().get(1)).isInstanceOf(Journey.Walk.class);
        assertThat(((Journey.Ride) j.legs().get(2)).tripId()).isEqualTo("bus2");
    }

    @Test
    void offersFasterOptionWithMoreTransfersAndSlowerDirectOption() {
        trip("slow", SLOW_BUS, new int[] {a, b, c}, t(7, 0), t(7, 30), t(8, 0));
        trip("train1", TRAIN, new int[] {a, b}, t(7, 5), t(7, 15));
        trip("bus1", BUS, new int[] {b, c}, t(7, 20), t(7, 30));

        List<Journey> found = search(a, t(6, 55), c);

        // Round 1: the slow direct bus. Round 2: train + bus, half an hour sooner.
        assertThat(found).extracting(Journey::transfers).containsExactly(0, 1);
        assertThat(found).extracting(Journey::arriveAt).containsExactly(t(8, 0), t(7, 30));
    }

    @Test
    void catchesTheNextTripNotOneThatAlreadyLeft() {
        trip("early", TRAIN, new int[] {a, b}, t(6, 0), t(6, 20));
        trip("later", TRAIN, new int[] {a, b}, t(8, 0), t(8, 20));

        Journey j = search(a, t(7, 0), b).getFirst();

        assertThat(((Journey.Ride) j.legs().getFirst()).tripId()).isEqualTo("later");
    }

    @Test
    void respectsNoBoardingAndNoGettingOff() {
        boolean[] board = {true, false, true};
        boolean[] alight = {true, false, true};
        int[] times = {t(7, 0), t(7, 10), t(7, 20)};
        builder.addTrip("express", TRAIN, new int[] {a, b, c}, times, times, board, alight);

        assertThat(search(a, t(6, 0), b)).isEmpty();          // can't get off at B
        assertThat(search(a, t(6, 0), c)).hasSize(1);
    }

    @Test
    void fillsInMissingTimesBetweenTimepoints() {
        int[] times = {t(7, 0), -1, t(7, 20)};
        trip("bus", BUS, new int[] {a, b, c}, times);

        Journey j = search(a, t(6, 0), b).getFirst();

        assertThat(j.arriveAt()).isEqualTo(t(7, 10));
    }

    @Test
    void handlesTripsThatOvertakeEachOther() {
        trip("local", BUS, new int[] {a, b, c}, t(7, 0), t(7, 30), t(8, 0));
        trip("express", BUS, new int[] {a, b, c}, t(7, 5), t(7, 15), t(7, 25));   // leaves later, arrives sooner

        Journey j = search(a, t(6, 59), c).getFirst();

        assertThat(j.arriveAt()).isEqualTo(t(7, 25));
    }

    @Test
    void findsNothingWhenNoServiceConnects() {
        trip("train1", TRAIN, new int[] {a, b}, t(7, 0), t(7, 20));

        assertThat(search(a, t(6, 0), x)).isEmpty();
    }
}
