package com.recallbot;

import com.recallbot.config.properties.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * RecallMemoryBot Spring Boot Application Entry Point.
 * Initializes core application context and configuration property bindings.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RecallProperties.class)
public class TelegramBotApplication {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotApplication.class);

    public static void main(String[] args) {
        loadDotenvIfPresent();
        SpringApplication.run(TelegramBotApplication.class, args);
    }

    /**
     * Automatically loads .env file properties into system properties if present
     * and not already overridden by environment variables or system properties.
     */
    static void loadDotenvIfPresent() {
        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(envPath);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    String val = line.substring(eqIdx + 1).trim();
                    if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                        val = val.substring(1, val.length() - 1);
                    }
                    if (System.getProperty(key) == null && System.getenv(key) == null) {
                        System.setProperty(key, val);
                    }
                }
            }
            log.info("Loaded configuration properties from local .env file");
        } catch (IOException e) {
            log.warn("Could not read local .env file: {}", e.getMessage());
        }
    }
}
