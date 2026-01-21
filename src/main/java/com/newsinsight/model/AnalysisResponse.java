package com.newsinsight.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AnalysisResponse {
    
    @JsonProperty("analysis")
    private AnalysisData analysis;
    
    @JsonProperty("full_text")
    private String fullText;

    public AnalysisResponse(AnalysisData analysis, String fullText) {
        this.analysis = analysis;
        this.fullText = fullText;
    }

    // Getters
    public AnalysisData getAnalysis() { return analysis; }
    public String getFullText() { return fullText; }

    public static class AnalysisData {
        public String summary;
        public String economic_impact;
        public String global_impact;
        public int impact_rating;
        public String urgency;
        
        // Default constructor for Jackson
        public AnalysisData() {} 
        
        // Error constructor
        public AnalysisData(String error) {
            this.summary = error;
            this.economic_impact = "N/A";
            this.global_impact = "N/A";
            this.impact_rating = 0;
            this.urgency = "Unknown";
        }
    }
}
