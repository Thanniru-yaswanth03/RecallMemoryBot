package com.recallbot.telegram.util;

/**
 * Utility for formatting and escaping text for Telegram MarkdownV2 presentation.
 */
public final class TelegramMarkdownFormatter {

    private static final String CHARS_TO_ESCAPE = "_*[]()~`>#+-=|{}.!";

    private TelegramMarkdownFormatter() {
    }

    /**
     * Escapes characters that have special syntactic meaning in Telegram MarkdownV2 mode.
     * Special characters: _, *, [, ], (, ), ~, `, >, #, +, -, =, |, {, }, ., !
     *
     * @param text the raw unescaped string
     * @return the escaped string safe for MarkdownV2 transmission
     */
    public static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (CHARS_TO_ESCAPE.indexOf(c) != -1) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
