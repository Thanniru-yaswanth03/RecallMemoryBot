package com.recallbot.telegram.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramMarkdownFormatterTest {

    @Test
    @DisplayName("Escapes all standard Telegram MarkdownV2 reserved characters")
    void escapesReservedCharacters() {
        String input = "Hello _world_ *bold* [link](url) ~strike~ `code` >quote #tag +plus -minus =equals |pipe {brace} .dot !excl";
        String expected = "Hello \\_world\\_ \\*bold\\* \\[link\\]\\(url\\) \\~strike\\~ \\`code\\` \\>quote \\#tag \\+plus \\-minus \\=equals \\|pipe \\{brace\\} \\.dot \\!excl";

        String result = TelegramMarkdownFormatter.escape(input);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("Returns empty string for null or empty input")
    void handlesNullAndEmpty() {
        assertThat(TelegramMarkdownFormatter.escape(null)).isEmpty();
        assertThat(TelegramMarkdownFormatter.escape("")).isEmpty();
    }

    @Test
    @DisplayName("Leaves text without reserved characters unmodified")
    void leavesPlainTextUntouched() {
        String input = "Standard alphanumeric text 12345 with spaces and commas, quotes";
        assertThat(TelegramMarkdownFormatter.escape(input)).isEqualTo(input);
    }
}
