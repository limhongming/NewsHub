package com.newsinsight.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MergedNewsCluster(
    @JsonProperty("topic") String topic,
    @JsonProperty("summary") String summary,
    @JsonProperty("economic_impact") String economic_impact,
    @JsonProperty("global_impact") String global_impact,
    @JsonProperty("impact_rating") String impact_rating,
    @JsonProperty("what_next") String what_next,
    @JsonProperty("related_links") List<String> related_links,
    @JsonProperty("model_used") String modelUsed,
    @JsonProperty("image_url") String imageUrl,
    @JsonProperty("published_date") String publishedDate,
    @JsonProperty("author") String author
) {
    // Compact constructor for backward compatibility or cleaner instantiation
    public MergedNewsCluster(String topic, String summary, String economic_impact, String global_impact, String impact_rating, String what_next, List<String> related_links, String modelUsed, String imageUrl) {
        this(topic, summary, economic_impact, global_impact, impact_rating, what_next, related_links, modelUsed, imageUrl, null, null);
    }
}
