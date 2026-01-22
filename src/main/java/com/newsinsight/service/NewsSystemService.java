package com.newsinsight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.newsinsight.model.AnalysisResponse;
import com.newsinsight.model.MergedNewsCluster;
import com.newsinsight.model.NewsItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class NewsSystemService {

    @Value("${gemini.api.key}")
    private String apiKey;

    // Using Gemini 2.5 Flash model
    private static final String API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=%s";
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalysisResponse.AnalysisData analyzeText(String text) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your_api_key_here")) {
            return new AnalysisResponse.AnalysisData("API Key is not configured.");
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
            return new AnalysisResponse.AnalysisData("Analysis failed: " + e.getMessage());
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

    public List<MergedNewsCluster> processAndClusterNews(List<NewsItem> items, String language) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your_api_key_here")) {
            return Collections.emptyList();
        }

        // Filter out system error or no news items to avoid confusing the AI
        List<NewsItem> validItems = items.stream()
                .filter(item -> !item.title().startsWith("System Error") && !item.title().startsWith("No News Found"))
                .collect(Collectors.toList());

        if (validItems.isEmpty()) {
            // Return a dummy cluster so the user sees something other than "API Key missing"
            return List.of(new MergedNewsCluster(
                "No Content to Analyze",
                "The news feed returned no valid articles to analyze. Please try again later.",
                "N/A",
                Collections.emptyList()
            ));
        }

        StringBuilder itemsText = new StringBuilder();
        for (NewsItem item : validItems) {
            itemsText.append("- Title: ").append(item.title()).append("\n");
            itemsText.append("  Link: ").append(item.link()).append("\n");
            itemsText.append("  Snippet: ").append(item.summary()).append("\n\n");
        }

        String prompt = """
                You are an expert news analyst. Analyze the following news items and group them into logical clusters.
                
                CRITICAL INSTRUCTIONS:
                1. CLUSTERING: Group articles that are about the SAME event or directly related incidents. Do not leave related stories separate.
                2. TRANSLATION: You MUST translate the values of "topic", "summary", and "economic_impact" into %s. Do NOT return English unless the target language is English.
                
                Input News Items:
                %s
                
                Output Schema (JSON Array):
                [
                  {
                    "topic": "Translated Headline",
                    "summary": "Translated detailed summary of the event group.",
                    "economic_impact": "Translated economic analysis.",
                    "related_links": ["url1", "url2"] // Keep original URLs
                  }
                ]
                """.formatted(language.equals("Chinese") ? "Simplified Chinese (zh-CN)" : language, itemsText.toString());

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
                System.out.println("DEBUG: AI Cluster Response: " + rawText); // Log response
                return parseClusterJsonFromAI(rawText);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    private List<MergedNewsCluster> parseClusterJsonFromAI(String rawText) {
        try {
            String jsonText = rawText.replace("```json", "").replace("```", "").trim();
            return objectMapper.readValue(jsonText, new TypeReference<List<MergedNewsCluster>>(){});
        } catch (Exception e) {
            System.err.println("Failed to parse Cluster JSON: " + rawText);
            return Collections.emptyList();
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
