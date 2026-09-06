package com.recallbot.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramApiResponse<T>(
        @JsonProperty("ok") boolean ok,
        @JsonProperty("description") String description,
        @JsonProperty("error_code") Integer errorCode,
        @JsonProperty("result") T result
) {}
