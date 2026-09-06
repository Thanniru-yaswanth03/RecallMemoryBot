package com.recallbot.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateDto(
        @JsonProperty("update_id") Long updateId,
        @JsonProperty("message") MessageDto message,
        @JsonProperty("edited_message") MessageDto editedMessage,
        @JsonProperty("channel_post") MessageDto channelPost
) {
    public MessageDto effectiveMessage() {
        if (message != null) {
            return message;
        }
        return editedMessage;
    }

    public boolean isSupported() {
        return effectiveMessage() != null;
    }
}
