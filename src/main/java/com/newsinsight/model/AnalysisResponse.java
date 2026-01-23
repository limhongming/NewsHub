package com.newsinsight.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AnalysisResponse {
    
    @JsonProperty("analysis")
    private AnalysisData analysis;
    
    @JsonProperty("full_text")
    private String fullText;

    public AnalysisResponse() {
    }

    public AnalysisResponse(AnalysisData analysis, String fullText) {
        this.analysis = analysis;
        this.fullText = fullText;
    }

    // Getters and Setters
    public AnalysisData getAnalysis() { return analysis; }
    public void setAnalysis(AnalysisData analysis) { this.analysis = analysis; }
    
    public String getFullText() { return fullText; }
    public void setFullText(String fullText) { this.fullText = fullText; }

    public static class AnalysisData {
        @JsonProperty("summary")
        private String summary;
        
        @JsonProperty("economic_impact")
        private String economic_impact;
        
        @JsonProperty("global_impact")
        private String global_impact;
        
        @JsonProperty("impact_rating")
        private int impact_rating;
        
        @JsonProperty("urgency")
        private String urgency;
        
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
        
        // Getters and Setters
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        
        public String getEconomic_impact() { return economic_impact; }
        public void setEconomic_impact(String economic_impact) { this.economic_impact = economic_impact; }
        
        public String getGlobal_impact() { return global_impact; }
        public void setGlobal_impact(String global_impact) { this.global_impact = global_impact; }
        
        public int getImpact_rating() { return impact_rating; }
        public void setImpact_rating(int impact_rating) { this.impact_rating = impact_rating; }
        
        public String getUrgency() { return urgency; }
        public void setUrgency(String urgency) { this.urgency = urgency; }
    }
}
