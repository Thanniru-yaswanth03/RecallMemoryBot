package com.recallbot.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust implementation of {@link MultilingualQueryProcessor} providing:
 * 1. Unicode decomposition and conservative normalization.
 * 2. Script detection (English, Hindi Devanagari, Telugu Script, Hinglish, Telugish).
 * 3. Exact identifier and entity extraction (names like yo_siri, task IDs, numbers, dates, URLs).
 * 4. Cross-lingual vocabulary expansion for key project concepts without corrupting the original query.
 * 5. Multi-item intent detection for exhaustive recall queries.
 */
@Component
public class MultilingualQueryProcessorImpl implements MultilingualQueryProcessor {

    private static final Logger log = LoggerFactory.getLogger(MultilingualQueryProcessorImpl.class);

    private static final Pattern DEVANAGARI_PATTERN = Pattern.compile("\\p{InDevanagari}");
    private static final Pattern TELUGU_PATTERN = Pattern.compile("\\p{InTelugu}");

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "\\b[a-zA-Z0-9]+[_-][a-zA-Z0-9_-]+\\b|@[a-zA-Z0-9_]+|https?://\\S+|\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b"
    );

    private static final Pattern MULTI_ITEM_PATTERN = Pattern.compile(
            "\\b(all|every|list|remaining|what else|more|tasks|decisions|items|commitments|sab|sare|anni|inkenti|batao)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Common abbreviations and informal shortcuts
    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("tmrw", "tomorrow"),
            Map.entry("2mrw", "tomorrow"),
            Map.entry("yday", "yesterday"),
            Map.entry("pls", "please"),
            Map.entry("plz", "please"),
            Map.entry("asap", "as soon as possible"),
            Map.entry("wip", "work in progress"),
            Map.entry("eta", "estimated completion time"),
            Map.entry("wbu", "what about you"),
            Map.entry("msg", "message")
    );

    // Transliterated keyword markers
    private static final Set<String> HINGLISH_MARKERS = Set.of(
            "kab", "kahan", "kaun", "kya", "kyun", "kaise", "kitna", "kitne", "kal", "aaj", "parso",
            "faisla", "baat", "kaam", "bhai", "hai", "hain", "karna", "karo", "nahi", "matlab", "sab",
            "wala", "wali", "karenge", "dekh", "pata", "bataya"
    );

    private static final Set<String> TELUGISH_MARKERS = Set.of(
            "eppudu", "ekkada", "evaru", "enti", "enduku", "ela", "enno", "repu", "ninna", "ee", "roju",
            "pani", "nirnayam", "cheyali", "chesam", "undhi", "undi", "ledu", "kadu", "anni", "inka",
            "chestadu", "chesaru", "ivvali", "kavali", "telusa"
    );

    // Cross-lingual concept synonyms
    private static final Map<String, List<String>> CROSS_LINGUAL_DICT = new HashMap<>();

    static {
        addCrossLingualMapping(List.of("task", "tasks", "todo", "action item", "work"),
                List.of("काम", "पनि", "పని", "kaam", "pani", "task", "tasks", "work"));

        addCrossLingualMapping(List.of("meeting", "meetings", "sync", "call"),
                List.of("मीटिंग", "మీటింగ్", "meeting", "baithak", "call"));

        addCrossLingualMapping(List.of("decision", "decisions", "decided", "agreed"),
                List.of("फैसला", "निर्णय", "నిర్ణయం", "faisla", "nirnayam", "decision", "decided"));

        addCrossLingualMapping(List.of("deadline", "due", "due date"),
                List.of("deadline", "due date", "aakhri tarikh", "last date"));

        addCrossLingualMapping(List.of("tomorrow", "tmrw", "2mrw"),
                List.of("कल", "రేపు", "kal", "repu", "tomorrow"));

        addCrossLingualMapping(List.of("yesterday", "yday"),
                List.of("कल", "నిన్న", "kal", "ninna", "yesterday"));

        addCrossLingualMapping(List.of("today"),
                List.of("आज", "ఈ రోజు", "aaj", "ee roju", "today"));

        addCrossLingualMapping(List.of("who", "assignee", "assigned", "responsible"),
                List.of("कौन", "ఎవరు", "kaun", "evaru", "assigned", "zimmedari", "badhyatha"));

        addCrossLingualMapping(List.of("when", "time", "date"),
                List.of("कब", "ఎప్పుడు", "kab", "eppudu", "when", "time"));

        addCrossLingualMapping(List.of("where", "location"),
                List.of("कहाँ", "ఎక్కడ", "kahan", "kidhar", "ekkada", "where"));
    }

    private static void addCrossLingualMapping(List<String> keys, List<String> synonyms) {
        for (String k : keys) {
            CROSS_LINGUAL_DICT.put(k.toLowerCase(), synonyms);
        }
        for (String s : synonyms) {
            CROSS_LINGUAL_DICT.put(s.toLowerCase(), synonyms);
        }
    }

    @Override
    public ProcessedQuery process(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new ProcessedQuery("", "", Collections.emptySet(), "", Collections.emptySet(), false, QueryLanguage.ENGLISH);
        }

        String original = rawQuery.trim();
        String nfkc = Normalizer.normalize(original, Normalizer.Form.NFKC);

        // 1. Detect language / script
        QueryLanguage language = detectLanguage(nfkc);

        // 2. Extract exact identifiers (names like yo_siri, dates, task IDs, numbers, @mentions)
        Set<String> exactKeywords = extractIdentifiers(nfkc);

        // 3. Normalize informal abbreviations and produce cleaned semantic query
        String cleanedForSemantic = expandAbbreviations(nfkc);

        // 4. Extract cross-lingual expansion terms
        Set<String> crossLingualTerms = extractCrossLingualTerms(nfkc);

        // 5. Build full-text search query for PostgreSQL tsv_content
        String ftsQuery = buildFtsQuery(nfkc, exactKeywords, crossLingualTerms);

        // 6. Check for multi-item recall intent
        boolean isMultiItem = MULTI_ITEM_PATTERN.matcher(original).find();

        log.debug("Processed query: lang={}, isMulti={}, exactCount={}, crossLingualCount={}",
                language, isMultiItem, exactKeywords.size(), crossLingualTerms.size());

        return new ProcessedQuery(
                original,
                cleanedForSemantic,
                exactKeywords,
                ftsQuery,
                crossLingualTerms,
                isMultiItem,
                language
        );
    }

    private QueryLanguage detectLanguage(String text) {
        if (DEVANAGARI_PATTERN.matcher(text).find()) {
            return QueryLanguage.HINDI_DEVANAGARI;
        }
        if (TELUGU_PATTERN.matcher(text).find()) {
            return QueryLanguage.TELUGU_SCRIPT;
        }

        String lower = text.toLowerCase();
        String[] words = lower.split("\\W+");
        int hinglishMatches = 0;
        int telugishMatches = 0;

        for (String w : words) {
            if (HINGLISH_MARKERS.contains(w)) hinglishMatches++;
            if (TELUGISH_MARKERS.contains(w)) telugishMatches++;
        }

        if (hinglishMatches > 0 && hinglishMatches >= telugishMatches) {
            return QueryLanguage.HINGLISH;
        }
        if (telugishMatches > 0) {
            return QueryLanguage.TELUGISH;
        }

        return QueryLanguage.ENGLISH;
    }

    private Set<String> extractIdentifiers(String text) {
        Set<String> identifiers = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().trim();
            while (token.endsWith("?") || token.endsWith(".") || token.endsWith(",") || token.endsWith("!")) {
                token = token.substring(0, token.length() - 1);
            }
            if (!token.isBlank()) {
                identifiers.add(token);
                // Also add stripped version (e.g. "@alice" -> "alice")
                if (token.startsWith("@") && token.length() > 1) {
                    identifiers.add(token.substring(1));
                }
            }
        }

        // Also extract distinct word tokens with length >= 3 that look like specific names
        String[] words = text.split("[\\s,;!?\"'()\\[\\]{}]+");
        for (String w : words) {
            if (w.contains("_") || w.contains("-")) {
                identifiers.add(w);
            } else if (w.matches("\\d+")) {
                identifiers.add(w);
            }
        }

        return identifiers;
    }

    private String expandAbbreviations(String text) {
        String[] tokens = text.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            String lower = token.toLowerCase().replaceAll("[^a-z0-9]", "");
            String replacement = ABBREVIATIONS.get(lower);
            if (replacement != null) {
                sb.append(replacement);
            } else {
                sb.append(token);
            }
            if (i < tokens.length - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    private Set<String> extractCrossLingualTerms(String text) {
        Set<String> result = new LinkedHashSet<>();
        String lower = text.toLowerCase();
        String[] words = lower.split("[\\s\\p{Punct}]+");

        for (String word : words) {
            if (CROSS_LINGUAL_DICT.containsKey(word)) {
                result.addAll(CROSS_LINGUAL_DICT.get(word));
            }
        }

        // Check for Indic markers and map to English
        for (String word : words) {
            if (HINGLISH_MARKERS.contains(word) || TELUGISH_MARKERS.contains(word)) {
                if ("kab".equals(word) || "eppudu".equals(word)) result.add("when");
                if ("kahan".equals(word) || "kidhar".equals(word) || "ekkada".equals(word)) result.add("where");
                if ("kaun".equals(word) || "evaru".equals(word)) result.add("who");
                if ("kya".equals(word) || "enti".equals(word)) result.add("what");
                if ("faisla".equals(word) || "nirnayam".equals(word)) result.addAll(List.of("decision", "decided"));
                if ("kaam".equals(word) || "pani".equals(word)) result.addAll(List.of("task", "work", "todo"));
                if ("kal".equals(word)) result.addAll(List.of("tomorrow", "yesterday"));
                if ("repu".equals(word)) result.add("tomorrow");
                if ("ninna".equals(word)) result.add("yesterday");
                if ("aaj".equals(word) || "roju".equals(word)) result.add("today");
            }
        }

        return result;
    }

    private String buildFtsQuery(String text, Set<String> identifiers, Set<String> crossLingual) {
        Set<String> terms = new LinkedHashSet<>();

        // Add extracted identifiers
        for (String id : identifiers) {
            String clean = id.replaceAll("[^a-zA-Z0-9_]", " ").trim();
            if (!clean.isBlank()) {
                terms.add(clean);
            }
        }

        // Add substantive tokens from original text
        String[] words = text.toLowerCase().split("\\W+");
        for (String w : words) {
            if (w.length() >= 3 && !isStopword(w)) {
                terms.add(w);
            }
        }

        // Add high-priority cross-lingual English terms
        for (String cl : crossLingual) {
            if (cl.matches("[a-zA-Z0-9]+") && cl.length() >= 3 && !isStopword(cl)) {
                terms.add(cl);
            }
        }

        if (terms.isEmpty()) {
            return text.replaceAll("[^a-zA-Z0-9\\s]", " ").trim();
        }

        return String.join(" ", terms);
    }

    private boolean isStopword(String w) {
        return switch (w) {
            case "the", "and", "for", "with", "that", "this", "what", "when", "where", "who", "why", "how",
                 "are", "was", "were", "been", "have", "has", "had", "does", "did", "tell", "about",
                 "from", "into", "group", "please", "know" -> true;
            default -> false;
        };
    }
}
