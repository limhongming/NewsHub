package com.newsinsight.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class AnalysisRequest {
    private final String url;
    
    @JsonCreator
    public AnalysisRequest(@JsonProperty("url") String url) {
        this.url = url;
    }
    
    public String url() {
        return url;
    }
}
