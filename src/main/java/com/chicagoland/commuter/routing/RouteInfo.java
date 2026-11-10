package com.chicagoland.commuter.routing;

/**
 * A transit line as riders know it.
 *
 * @param routeType GTFS route type: 1 = subway/'L', 2 = rail (Metra), 3 = bus
 */
public record RouteInfo(String routeId, String feedId, String shortName, String longName, int routeType,
                        String color) {

    /** e.g. "Metra MD-W", "Blue Line", "Bus 157", "Pace Pulse Dempster Line". */
    public String displayName() {
        return switch (feedId) {
            case "metra" -> "Metra " + firstNonBlank(shortName, longName);
            case "cta" -> routeType == 3 ? "Bus " + firstNonBlank(shortName, longName) : firstNonBlank(longName, shortName);
            case "pace" -> "Pace " + firstNonBlank(shortName, longName);
            default -> firstNonBlank(shortName, longName);
        };
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b != null ? b : "";
    }
}
