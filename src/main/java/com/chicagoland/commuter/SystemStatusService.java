package com.chicagoland.commuter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reports whether the database and PostGIS are reachable, shown on the home screen. */
@Service
public class SystemStatusService {

    private final JdbcTemplate jdbc;

    public SystemStatusService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String postgisVersion() {
        return jdbc.queryForObject("SELECT postgis_lib_version()", String.class);
    }
}
