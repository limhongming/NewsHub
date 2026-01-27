package com.newsinsight.model;

import java.util.Date;

public record NewsItem(
    String title,
    String link,
    Date published,
    String summary,
    String imageUrl
) {}
