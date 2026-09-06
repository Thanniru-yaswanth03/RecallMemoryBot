package com.recallbot.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.Socket;

public abstract class BasePostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BasePostgresIntegrationTest.class);

    private static PostgreSQLContainer<?> postgres;
    private static boolean useLocalPostgres = false;

    static {
        if (isPortOpen("localhost", 5432)) {
            log.info("Local PostgreSQL instance detected on localhost:5432. Using existing instance for tests.");
            useLocalPostgres = true;
        } else {
            try {
                log.info("No local PostgreSQL on port 5432; attempting to start Testcontainers pgvector container...");
                DockerImageName image = DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");
                postgres = new PostgreSQLContainer<>(image)
                        .withDatabaseName("recall_db")
                        .withUsername("recall_user")
                        .withPassword("recall_pass");
                postgres.start();
            } catch (Throwable t) {
                log.warn("Testcontainers could not be started: {}. Falling back to default localhost:5432 configuration.", t.getMessage());
                useLocalPostgres = true;
            }
        }
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (!useLocalPostgres && postgres != null && postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/recall_db");
            registry.add("spring.datasource.username", () -> "recall_user");
            registry.add("spring.datasource.password", () -> "recall_pass");
        }
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
