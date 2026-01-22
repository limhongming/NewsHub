package com.newsinsight.model;

import java.util.List;

public record MergedNewsCluster(
    String topic,
    String summary,
    String economic_impact,
    List<String> related_links
) {}
