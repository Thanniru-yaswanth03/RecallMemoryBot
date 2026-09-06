package com.recallbot.ai;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Normalizes message text prior to vector embedding generation.
 * Enforces unicode NFKC canonical decomposition/composition, collapses redundant whitespace,
 * and bounds text length to ensure it fits comfortably within the model's token context window.
 */
@Component
public class EmbeddingTextNormalizer {

    public static final int MAX_EMBEDDING_TEXT_LENGTH = 1500;
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * Normalizes input text for embedding generation.
     *
     * @param rawText raw message content
     * @return normalized text string, or empty string if input was null/blank
     */
    public String normalize(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        // 1. Unicode normalization (NFKC)
        String nfkc = Normalizer.normalize(rawText, Normalizer.Form.NFKC);

        // 2. Collapse excessive whitespace and trim
        String collapsed = WHITESPACE_PATTERN.matcher(nfkc).replaceAll(" ").trim();

        // 3. Enforce context budget length bound (1500 chars ~ 500 tokens for 512 context limit)
        if (collapsed.length() > MAX_EMBEDDING_TEXT_LENGTH) {
            return collapsed.substring(0, MAX_EMBEDDING_TEXT_LENGTH).trim();
        }

        return collapsed;
    }
}
