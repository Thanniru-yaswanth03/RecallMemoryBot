package com.recallbot.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageDto(
        @JsonProperty("message_id") Long messageId,
        @JsonProperty("from") UserDto from,
        @JsonProperty("chat") ChatDto chat,
        @JsonProperty("date") Long date,
        @JsonProperty("text") String text,
        @JsonProperty("caption") String caption,
        @JsonProperty("reply_to_message") MessageDto replyToMessage
) {
    public String extractContent() {
        if (text != null && !text.isBlank()) {
            return text;
        }
        return caption;
    }
}
