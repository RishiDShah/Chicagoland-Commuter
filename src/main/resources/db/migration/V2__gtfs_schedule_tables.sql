-- Transit schedules from the CTA, Metra and Pace GTFS feeds.
-- IDs are prefixed with the feed ("cta:", "metra:", "pace:") because agencies reuse the same IDs.

CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- fast "contains" search on stop names

CREATE TABLE gtfs_feed (
    feed_id       text PRIMARY KEY,         -- cta, metra, pace
    name          text        NOT NULL,
    source_url    text        NOT NULL,
    sha256        text        NOT NULL,     -- skip re-importing an unchanged file
    imported_at   timestamptz NOT NULL,
    stop_count    integer     NOT NULL,
    trip_count    integer     NOT NULL
);

CREATE TABLE route (
    route_id      text PRIMARY KEY,
    feed_id       text     NOT NULL,
    short_name    text,
    long_name     text,
    route_type    smallint NOT NULL,        -- 1 = subway/L, 2 = rail (Metra), 3 = bus
    color         text,
    text_color    text
);

CREATE TABLE stop (
    stop_id         text PRIMARY KEY,
    feed_id         text     NOT NULL,
    code            text,
    name            text     NOT NULL,
    location        geography(Point, 4326) NOT NULL,
    location_type   smallint NOT NULL DEFAULT 0,  -- 0 = stop/platform, 1 = station
    parent_station  text,
    wheelchair      smallint
);
CREATE INDEX stop_location_idx ON stop USING gist (location);
CREATE INDEX stop_name_trgm_idx ON stop USING gin (lower(name) gin_trgm_ops);

CREATE TABLE trip (
    trip_id       text PRIMARY KEY,
    feed_id       text NOT NULL,
    route_id      text NOT NULL,
    service_id    text NOT NULL,
    headsign      text,
    direction_id  smallint
);
CREATE INDEX trip_route_idx ON trip (route_id);

-- Times are seconds after midnight of the service day; GTFS allows values past 24:00 for late trips.
CREATE TABLE stop_time (
    trip_id         text     NOT NULL,
    stop_sequence   integer  NOT NULL,
    stop_id         text     NOT NULL,
    arrival_secs    integer,
    departure_secs  integer,
    pickup_type     smallint NOT NULL DEFAULT 0,
    drop_off_type   smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (trip_id, stop_sequence)
);
CREATE INDEX stop_time_stop_idx ON stop_time (stop_id);

CREATE TABLE service_calendar (
    service_id  text PRIMARY KEY,
    feed_id     text    NOT NULL,
    monday      boolean NOT NULL,
    tuesday     boolean NOT NULL,
    wednesday   boolean NOT NULL,
    thursday    boolean NOT NULL,
    friday      boolean NOT NULL,
    saturday    boolean NOT NULL,
    sunday      boolean NOT NULL,
    start_date  date    NOT NULL,
    end_date    date    NOT NULL
);

-- exception_type 1 = service added on this date, 2 = service removed (holidays)
CREATE TABLE service_calendar_date (
    service_id      text     NOT NULL,
    feed_id         text     NOT NULL,
    service_date    date     NOT NULL,
    exception_type  smallint NOT NULL,
    PRIMARY KEY (service_id, service_date)
);

CREATE TABLE transfer (
    feed_id            text     NOT NULL,
    from_stop_id       text     NOT NULL,
    to_stop_id         text     NOT NULL,
    transfer_type      smallint NOT NULL DEFAULT 0,
    min_transfer_secs  integer
);
