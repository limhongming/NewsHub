package com.newsinsight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newsinsight.model.AnalysisResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    // Use gemini-2.5-flash as requested by user's latest setup
    private static final String API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=%s";
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalysisResponse.AnalysisData analyzeText(String text) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your_api_key_here")) {
            return new AnalysisResponse.AnalysisData("Gemini API Key is not configured.");
        }

        String prompt = """
                Analyze the following news article text.
                
                "%s"
                
                Return a valid JSON object (and ONLY JSON, no markdown formatting) with the following specific fields:
                {
                    "summary": "A concise summary of the event",
                    "economic_impact": "Specific potential impacts on the economy (markets, prices, jobs, etc.)",
                    "global_impact": "Potential geopolitical or worldwide consequences",
                    "impact_rating": 5, // A number from 1-10 (10 being massive global event, 1 being minor local news)
                    "urgency": "Medium" // One of: "Low", "Medium", "High", "Critical"
                }
                """.formatted(text.replace("\"", "\\\"")); // Basic escape

        // Construct Request Body
        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(part));
        Map<String, Object> requestBody = Map.of("contents", List.of(content));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            String url = String.format(API_URL_TEMPLATE, apiKey);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String rawText = extractTextFromResponse(response.getBody());
                return parseJsonFromAI(rawText);
            }
 else {
                return new AnalysisResponse.AnalysisData("Error: " + response.getStatusCode());
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new AnalysisResponse.AnalysisData("AI Analysis failed: " + e.getMessage());
        }
    }

    private AnalysisResponse.AnalysisData parseJsonFromAI(String rawText) {
        try {
            // Clean markdown if present
            String jsonText = rawText.replace("```json", "").replace("```", "").trim();
            return objectMapper.readValue(jsonText, AnalysisResponse.AnalysisData.class);
        } catch (Exception e) {
            System.err.println("Failed to parse JSON from AI: " + rawText);
            return new AnalysisResponse.AnalysisData("Failed to parse AI response.");
        }
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromResponse(Map<String, Object> responseBody) {
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                if (parts != null && !parts.isEmpty()) {
                    return (String) parts.get(0).get("text");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "{}";
    }
}
