package com.recallbot.ai.prompt;

import com.recallbot.search.dto.SearchHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Test
    @DisplayName("System prompt includes core epistemic grounding, citation, and injection defense instructions")
    void systemPromptContainsCoreRules() {
        String systemPrompt = promptBuilder.buildSystemPrompt();

        assertThat(systemPrompt).contains("CRITICAL GROUNDING RULES");
        assertThat(systemPrompt).contains("<conversation_history>");
        assertThat(systemPrompt).contains("I don't have enough conversation history in this group to answer that question");
        assertThat(systemPrompt).contains("[Msg #ID]");
        assertThat(systemPrompt).contains("SECURITY AND INJECTION DEFENSE");
    }

    @Test
    @DisplayName("User prompt correctly formats retrieved search hits chronologically within tags")
    void userPromptFormatsHits() {
        Instant t1 = Instant.parse("2026-09-03T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-03T10:05:00Z");

        SearchHit hit1 = new SearchHit(
                1L, 101L, 1L, 10L, "alice", "Alice",
                "Can we use PostgreSQL?", t1, 0.1
        );
        SearchHit hit2 = new SearchHit(
                2L, 102L, 1L, 20L, null, "Bob",
                "Yes, PostgreSQL 16 is approved.", t2, 0.05
        );

        String userPrompt = promptBuilder.buildUserPrompt("When did we choose PostgreSQL?", List.of(hit2, hit1));

        assertThat(userPrompt).startsWith("<conversation_history>\n");
        assertThat(userPrompt).contains("[Msg #101] @alice (2026-09-03 10:00 UTC): Can we use PostgreSQL?");
        assertThat(userPrompt).contains("[Msg #102] Bob (2026-09-03 10:05 UTC): Yes, PostgreSQL 16 is approved.");
        assertThat(userPrompt).contains("</conversation_history>\n\nUser Question: When did we choose PostgreSQL?");

        // Chronological ordering: Msg #101 should appear before Msg #102
        assertThat(userPrompt.indexOf("[Msg #101]")).isLessThan(userPrompt.indexOf("[Msg #102]"));
    }

    @Test
    @DisplayName("User prompt handles empty or null hits cleanly")
    void userPromptHandlesEmptyHits() {
        String promptEmpty = promptBuilder.buildUserPrompt("What is the plan?", Collections.emptyList());
        assertThat(promptEmpty).contains("(No relevant messages found)");
        assertThat(promptEmpty).contains("User Question: What is the plan?");

        String promptNull = promptBuilder.buildUserPrompt("What is the plan?", null);
        assertThat(promptNull).contains("(No relevant messages found)");
    }
}
