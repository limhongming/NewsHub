package com.newsinsight.model;

public record SnippetRequest(
    String title,
    String snippet,
    String link,
    String lang,
    String model
) {}
