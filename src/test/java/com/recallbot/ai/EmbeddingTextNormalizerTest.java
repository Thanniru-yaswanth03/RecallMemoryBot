package com.recallbot.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingTextNormalizerTest {

    private EmbeddingTextNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new EmbeddingTextNormalizer();
    }

    @Test
    @DisplayName("Null or blank input returns empty string")
    void nullOrBlankReturnsEmpty() {
        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize("")).isEmpty();
        assertThat(normalizer.normalize("   \n\t  ")).isEmpty();
    }

    @Test
    @DisplayName("Collapses consecutive whitespace and newlines to single space and trims")
    void collapsesWhitespace() {
        String input = "  Hello   world \n\n this is   a   test \t message.  ";
        String normalized = normalizer.normalize(input);
        assertThat(normalized).isEqualTo("Hello world this is a test message.");
    }

    @Test
    @DisplayName("Applies NFKC unicode normalization to ligatures and special symbols")
    void appliesNfkcNormalization() {
        // "ﬁ" (U+FB01 ligature fi) -> "fi"
        String input = "The \uFB01le was saved.";
        String normalized = normalizer.normalize(input);
        assertThat(normalized).isEqualTo("The file was saved.");
    }

    @Test
    @DisplayName("Bounds input exceeding 1500 characters to prevent context window overflow")
    void boundsOversizedInput() {
        String longText = "a".repeat(2000);
        String normalized = normalizer.normalize(longText);
        assertThat(normalized).hasSize(EmbeddingTextNormalizer.MAX_EMBEDDING_TEXT_LENGTH);
    }
}
