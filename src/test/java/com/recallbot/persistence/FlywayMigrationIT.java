package com.recallbot.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FlywayMigrationIT extends BasePostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("Verify pgvector extension is created")
    void verifyPgVectorExtensionInstalled() {
        String extName = jdbcClient.sql("SELECT extname FROM pg_extension WHERE extname = 'vector'")
                .query(String.class)
                .single();

        assertThat(extName).isEqualTo("vector");
    }

    @Test
    @DisplayName("Verify all required tables exist in public schema")
    void verifyTablesExist() {
        List<String> tableNames = jdbcClient.sql(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name"
        ).query(String.class).list();

        assertThat(tableNames).contains(
                "groups",
                "users",
                "group_memberships",
                "messages",
                "message_embeddings",
                "memories",
                "memory_sources",
                "telegram_updates"
        );
    }

    @Test
    @DisplayName("Verify message_embeddings embedding column has vector(1536) type")
    void verifyEmbeddingColumnType() {
        String udtName = jdbcClient.sql(
                "SELECT udt_name FROM information_schema.columns WHERE table_name = 'message_embeddings' AND column_name = 'embedding'"
        ).query(String.class).single();

        assertThat(udtName).isEqualTo("vector");
    }

    @Test
    @DisplayName("Verify messages tsv_content generated column has tsvector type")
    void verifyTsvectorColumnType() {
        String udtName = jdbcClient.sql(
                "SELECT udt_name FROM information_schema.columns WHERE table_name = 'messages' AND column_name = 'tsv_content'"
        ).query(String.class).single();

        assertThat(udtName).isEqualTo("tsvector");
    }
}
