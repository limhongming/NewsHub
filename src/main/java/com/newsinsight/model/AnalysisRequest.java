package com.newsinsight.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class AnalysisRequest {
    private String url;
    
    // Default constructor for Jackson
    public AnalysisRequest() {
    }
    
    @JsonCreator
    public AnalysisRequest(@JsonProperty("url") String url) {
        this.url = url;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
    
    // Keep the old method for compatibility
    public String url() {
        return url;
    }
}
