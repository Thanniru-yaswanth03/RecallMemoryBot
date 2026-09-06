package com.recallbot.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatDto(
        @JsonProperty("id") Long id,
        @JsonProperty("type") String type,
        @JsonProperty("title") String title,
        @JsonProperty("username") String username
) {
    public boolean isGroupOrSupergroup() {
        return "group".equalsIgnoreCase(type) || "supergroup".equalsIgnoreCase(type);
    }
}
