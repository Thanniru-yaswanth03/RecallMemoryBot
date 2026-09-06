package com.recallbot;

import com.recallbot.persistence.BasePostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TelegramBotApplicationTests extends BasePostgresIntegrationTest {

    @Test
    @DisplayName("Context loads cleanly with database and vector extensions configured")
    void contextLoads() {
        // Verifies that the Spring Boot context bootstraps deterministically
        // with database, Flyway, and JPA schema validation.
    }
}
