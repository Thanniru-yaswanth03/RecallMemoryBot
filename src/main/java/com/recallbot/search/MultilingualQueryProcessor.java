package com.recallbot.search;

import java.util.Set;

/**
 * Service for analyzing and expanding queries across English, Hindi, Hinglish, Telugu,
 * and mixed scripts without altering the original user question text.
 */
public interface MultilingualQueryProcessor {

    /**
     * Analyzes and enriches a raw query for semantic, full-text, and exact keyword retrieval.
     *
     * @param rawQuery the original user query text
     * @return ProcessedQuery containing original query, cleaned vector query, keywords, and cross-lingual terms
     */
    ProcessedQuery process(String rawQuery);

    record ProcessedQuery(
            String originalQuery,
            String semanticQuery,
            Set<String> exactKeywords,
            String ftsQuery,
            Set<String> crossLingualTerms,
            boolean isMultiItemRequest,
            QueryLanguage detectedLanguage
    ) {}

    enum QueryLanguage {
        ENGLISH,
        HINDI_DEVANAGARI,
        TELUGU_SCRIPT,
        HINGLISH,
        TELUGISH,
        MIXED_OR_OTHER
    }
}
