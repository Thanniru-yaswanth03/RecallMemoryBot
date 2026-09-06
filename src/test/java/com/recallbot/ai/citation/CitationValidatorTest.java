package com.recallbot.ai.citation;

import com.recallbot.search.dto.SearchHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator();

    @Test
    @DisplayName("Preserves valid citations matching retrieved message IDs")
    void preservesValidCitations() {
        SearchHit hit1 = new SearchHit(1L, 101L, 1L, 10L, "alice", "Alice", "Hello", Instant.now(), 0.1);
        SearchHit hit2 = new SearchHit(2L, 102L, 1L, 20L, "bob", "Bob", "World", Instant.now(), 0.2);

        String answer = "Alice proposed the idea [Msg #101] and Bob agreed [Msg #102].";
        String sanitized = validator.validateAndSanitize(answer, List.of(hit1, hit2));

        assertThat(sanitized).isEqualTo("Alice proposed the idea [Msg #101] and Bob agreed [Msg #102].");
    }

    @Test
    @DisplayName("Strips hallucinated citations referencing non-retrieved message IDs")
    void stripsHallucinatedCitations() {
        SearchHit hit1 = new SearchHit(1L, 101L, 1L, 10L, "alice", "Alice", "Hello", Instant.now(), 0.1);

        String answer = "Alice proposed the idea [Msg #101] and Charlie confirmed [Msg #9999].";
        String sanitized = validator.validateAndSanitize(answer, List.of(hit1));

        assertThat(sanitized).isEqualTo("Alice proposed the idea [Msg #101] and Charlie confirmed.");
    }

    @Test
    @DisplayName("Normalizes Message prefix to Msg tag format")
    void normalizesMessagePrefix() {
        SearchHit hit1 = new SearchHit(1L, 412L, 1L, 10L, "alice", "Alice", "Hello", Instant.now(), 0.1);

        String answer = "Confirmed on Thursday [Message #412].";
        String sanitized = validator.validateAndSanitize(answer, List.of(hit1));

        assertThat(sanitized).isEqualTo("Confirmed on Thursday [Msg #412].");
    }

    @Test
    @DisplayName("Handles null, empty, or un-cited answers gracefully")
    void handlesNullOrEmpty() {
        assertThat(validator.validateAndSanitize(null, Collections.emptyList())).isNull();
        assertThat(validator.validateAndSanitize("   ", Collections.emptyList())).isEqualTo("   ");
        assertThat(validator.validateAndSanitize("Plain answer without citations", Collections.emptyList()))
                .isEqualTo("Plain answer without citations");
    }
}
