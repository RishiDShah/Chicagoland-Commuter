package com.chicagoland.commuter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Starts the whole app against a throwaway PostGIS container (needs Docker running). */
@SpringBootTest(properties = {"spring.docker.compose.enabled=false", "vaadin.launch-browser=false"})
class CommuterApplicationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgis() {
            return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5")
                    .asCompatibleSubstituteFor("postgres"));
        }
    }

    @Autowired
    SystemStatusService status;

    @Test
    void appStartsAndPostgisIsEnabled() {
        assertThat(status.postgisVersion()).startsWith("3.");
    }
}
