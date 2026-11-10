package com.chicagoland.commuter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Starts the whole app against a throwaway PostGIS database. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CommuterApplicationTests {

    @Autowired
    SystemStatusService status;

    @Test
    void appStartsAndPostgisIsEnabled() {
        assertThat(status.postgisVersion()).startsWith("3.");
    }
}
