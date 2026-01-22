package com.newsinsight.model;

import java.util.List;

public record MergedNewsCluster(
    String topic,
    String summary,
    String economic_impact,
    String global_impact,
    String impact_rating,
    String what_next,
    List<String> related_links,
    String modelUsed
) {}
