package com.newsinsight.model;

public record GeminiModel(
    String name,
    String version,
    String displayName,
    String description,
    int inputTokenLimit,
    int outputTokenLimit
) {}
