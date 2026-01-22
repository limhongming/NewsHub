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

        StringBuilder itemsText = new StringBuilder();
        for (NewsItem item : items) {
            itemsText.append("- Title: ").append(item.title()).append("\n");
            itemsText.append("  Link: ").append(item.link()).append("\n");
            itemsText.append("  Snippet: ").append(item.summary()).append("\n\n");
        }

        String prompt = """
                Analyze the following news items. Group them into clusters based on shared events or related incidents (e.g. "incidents after this news").
                
                %s
                
                For each cluster, provide:
                1. "topic": Main headline for the cluster.
                2. "summary": A combined summary of the event.
                3. "economic_impact": Potential economic impacts.
                4. "related_links": A list of the original links that belong to this cluster.
                
                Translate the "topic", "summary", and "economic_impact" to: %s
                
                Return ONLY a JSON Array of objects (no markdown):
                [
                  {
                    "topic": "...",
                    "summary": "...",
                    "economic_impact": "...",
                    "related_links": ["...", "..."]
                  }
                ]
                """.formatted(itemsText.toString(), language);

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
}
