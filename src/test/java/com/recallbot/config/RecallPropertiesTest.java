package com.recallbot.config;

import com.recallbot.config.properties.RecallProperties;
import com.recallbot.persistence.BasePostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RecallPropertiesTest extends BasePostgresIntegrationTest {

    @Autowired
    private RecallProperties recallProperties;

    @Test
    @DisplayName("Configuration properties bind successfully to immutable records")
    void recallPropertiesBindSuccessfully() {
        assertThat(recallProperties).isNotNull();

        // Telegram section
        assertThat(recallProperties.telegram()).isNotNull();
        assertThat(recallProperties.telegram().mode()).isEqualTo("polling");
        assertThat(recallProperties.telegram().botUsername()).isEqualTo("recall_memory_bot");

        // AI section
        assertThat(recallProperties.ai()).isNotNull();
        assertThat(recallProperties.ai().chatModel()).isEqualTo("anthropic/claude-3-haiku");
        assertThat(recallProperties.ai().embeddingModel()).isEqualTo("openai/text-embedding-3-small");
        assertThat(recallProperties.ai().embeddingDimension()).isEqualTo(1536);
        assertThat(recallProperties.ai().timeoutSeconds()).isEqualTo(30);
        assertThat(recallProperties.ai().maxOutputTokens()).isEqualTo(800);
        assertThat(recallProperties.ai().temperature()).isEqualTo(0.2);

        // Search section
        assertThat(recallProperties.search()).isNotNull();
        assertThat(recallProperties.search().maxCandidates()).isEqualTo(15);
        assertThat(recallProperties.search().rrfK()).isEqualTo(60);
        assertThat(recallProperties.search().maxContextTokens()).isEqualTo(2500);

        // Rate limit section
        assertThat(recallProperties.rateLimit()).isNotNull();
        assertThat(recallProperties.rateLimit().userPerMinute()).isEqualTo(3);
        assertThat(recallProperties.rateLimit().groupPerFiveMinutes()).isEqualTo(10);

        // Admin section
        assertThat(recallProperties.admin()).isNotNull();
        assertThat(recallProperties.admin().enabled()).isTrue();
        assertThat(recallProperties.admin().username()).isEqualTo("admin");
        assertThat(recallProperties.admin().password()).isEqualTo("admin_recall_secret");
        assertThat(recallProperties.admin().sessionTtlHours()).isEqualTo(12);
        assertThat(recallProperties.admin().maxLoginAttempts()).isEqualTo(5);
        assertThat(recallProperties.admin().lockoutMinutes()).isEqualTo(15);
    }
}
