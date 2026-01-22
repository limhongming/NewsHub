package com.newsinsight.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.newsinsight.model.AnalysisResponse;
import com.newsinsight.model.GeminiModel;
import com.newsinsight.model.MergedNewsCluster;
import com.newsinsight.model.NewsItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class NewsSystemService {

    @Value("${gemini.api.key}")
    private String apiKey;

    // List of models to try in order of preference (Updated for 2026 availability)
    private static final List<String> FALLBACK_MODELS = List.of(
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.0-flash-lite",
        "gemini-2.0-flash",
        "gemini-2.5-pro",
        "gemini-2.0-pro-exp"
    );

    private static final String API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final String MODELS_API_URL = "https://generativelanguage.googleapis.com/v1beta/models?key=%s";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);

    public List<GeminiModel> listAvailableModels() {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your_api_key_here")) {
            return Collections.emptyList();
        }

        try {
            String url = String.format(MODELS_API_URL, apiKey);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, null, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> modelsList = (List<Map<String, Object>>) response.getBody().get("models");
                if (modelsList != null) {
                    List<GeminiModel> geminiModels = new ArrayList<>();
                    // Explicitly add the 2.5 flash-lite at the top if not returned by API
                    // (Sometimes new models don't show up in listModels immediately)
                    geminiModels.add(new GeminiModel("gemini-2.5-flash-lite", "v1beta", "Gemini 2.5 Flash-Lite", "Optimized for massive scale", 1000000, 65535));
                    
                    for (Map<String, Object> m : modelsList) {
                        String name = (String) m.get("name"); 
                        if (name != null && name.contains("gemini")) {
                            String shortName = name.replace("models/", "");
                            // Avoid adding duplicate flash-lite
                            if (shortName.equals("gemini-2.5-flash-lite")) continue;
                            
                            geminiModels.add(new GeminiModel(
                                shortName,
                                (String) m.get("version"),
                                (String) m.get("displayName"),
                                (String) m.get("description"),
                                m.get("inputTokenLimit") != null ? (int) m.get("inputTokenLimit") : 0,
                                m.get("outputTokenLimit") != null ? (int) m.get("outputTokenLimit") : 0
                            ));
                        }
                    }
                    return geminiModels;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Fallback if API fails
        return List.of(new GeminiModel("gemini-2.5-flash-lite", "v1beta", "Gemini 2.5 Flash-Lite", "High volume fallback", 1000000, 65535));
    }

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
                    "impact_rating": 5, 
                    "urgency": "Medium" 
                }
                """.formatted(text.replace("\"", "\\\"")); 

        try {
            String rawText = callGeminiApiWithFallback(prompt, null);
            return parseJsonFromAI(rawText);
        } catch (Exception e) {
            return new AnalysisResponse.AnalysisData("Analysis failed: " + e.getMessage());
        }
    }

    private AnalysisResponse.AnalysisData parseJsonFromAI(String rawText) {
        try {
            String jsonText = rawText.replace("```json", "").replace("```", "").trim();
            return objectMapper.readValue(jsonText, AnalysisResponse.AnalysisData.class);
        } catch (Exception e) {
            System.err.println("Failed to parse JSON from AI: " + rawText);
            return new AnalysisResponse.AnalysisData("Failed to parse AI response.");
        }
    }

    public List<MergedNewsCluster> processAndClusterNews(List<NewsItem> items, String language, boolean shouldCluster, String preferredModel) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your_api_key_here")) {
             return List.of(new MergedNewsCluster("API Key Missing", "Please configure gemini.api.key in application.properties", "N/A", "N/A", "0", "N/A", Collections.emptyList()));
        }

        List<NewsItem> validItems = items.stream()
                .filter(item -> !item.title().startsWith("System Error") && !item.title().startsWith("No News Found"))
                .collect(Collectors.toList());

        if (validItems.isEmpty()) {
            return List.of(new MergedNewsCluster("No Content to Analyze", "The news feed returned no valid articles to analyze.", "N/A", "N/A", "0", "N/A", Collections.emptyList()));
        }

        StringBuilder itemsText = new StringBuilder();
        for (NewsItem item : validItems) {
            itemsText.append("- Title: ").append(item.title()).append("\n");
            itemsText.append("  Link: ").append(item.link()).append("\n");
            itemsText.append("  Snippet: ").append(item.summary()).append("\n\n");
        }
        
        String fullText = itemsText.toString();
        if (fullText.length() > 30000) {
            fullText = fullText.substring(0, 30000) + "...[TRUNCATED]";
        }

        String clusteringInstruction = shouldCluster 
            ? "1. CLUSTERING: Group articles that are about the SAME event or directly related incidents. You MUST synthesize a single, comprehensive summary that combines unique details from ALL articles in the cluster."
            : "1. NO CLUSTERING: Treat each news item as a completely separate topic. Do NOT merge them. Create one output object for each input item.";

        String prompt = """
                You are an expert news analyst. Analyze the following news items.
                
                CRITICAL INSTRUCTIONS:
                %s
                2. TRANSLATION: You MUST translate the values of "topic", "summary", "economic_impact", "global_impact", and "what_next" into %s. Do NOT return English unless the target language is English.
                3. ANALYSIS: For each group (or item), provide a comprehensive summary, economic impact, global impact, impact rating (1-10), and a prediction of what happens next.
                
                Input News Items:
                %s
                
                Output Schema (JSON Array):
                [
                  {
                    "topic": "Translated Headline",
                    "summary": "Translated detailed summary.",
                    "economic_impact": "Translated economic analysis.",
                    "global_impact": "Translated geopolitical/global impact.",
                    "impact_rating": "8",
                    "what_next": "Translated prediction.",
                    "related_links": ["url1", "url2"]
                  }
                ]
                """.formatted(clusteringInstruction, language.equals("Chinese") ? "Simplified Chinese (zh-CN)" : language, fullText);

        try {
            String rawText = callGeminiApiWithFallback(prompt, preferredModel);
            System.out.println("DEBUG: AI Cluster Response: " + rawText);
            return parseClusterJsonFromAI(rawText);
        } catch (Exception e) {
            e.printStackTrace();
            String errorMessage = e.getMessage();
            if (e instanceof HttpClientErrorException) {
                errorMessage = ((HttpClientErrorException) e).getResponseBodyAsString();
            }
            return List.of(new MergedNewsCluster("System Error", "Analysis Failed: " + errorMessage, "N/A", "N/A", "0", "N/A", Collections.emptyList()));
        }
    }

    private String callGeminiApiWithFallback(String prompt, String preferredModel) {
        // Safety Settings
        List<Map<String, String>> safetySettings = List.of(
            Map.of("category", "HARM_CATEGORY_HARASSMENT", "threshold", "BLOCK_NONE"),
            Map.of("category", "HARM_CATEGORY_HATE_SPEECH", "threshold", "BLOCK_NONE"),
            Map.of("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold", "BLOCK_NONE"),
            Map.of("category", "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold", "BLOCK_NONE")
        );

        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(part));
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(content),
            "safetySettings", safetySettings
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        Exception lastException = null;
        
        java.util.List<String> modelsToTry = new java.util.ArrayList<>();
        if (preferredModel != null && !preferredModel.isEmpty()) {
            modelsToTry.add(preferredModel);
        }
        for (String m : FALLBACK_MODELS) {
            if (!modelsToTry.contains(m)) {
                modelsToTry.add(m);
            }
        }

        for (String model : modelsToTry) {
            try {
                System.out.println("Trying Gemini Model: " + model);
                String url = String.format(API_URL_TEMPLATE, model, apiKey);
                ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    String extracted = extractTextFromResponse(response.getBody());
                    if (extracted == null || extracted.isEmpty() || extracted.equals("{}")) {
                        throw new RuntimeException("Model " + model + " returned empty content (Safety Filter?)");
                    }
                    return extracted;
                }
            } catch (HttpClientErrorException e) {
                System.err.println("Model " + model + " failed: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
                if (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 503 || e.getStatusCode().value() == 404) {
                    lastException = e;
                    continue; 
                } else {
                    throw new RuntimeException("Gemini API Error (" + model + "): " + e.getResponseBodyAsString());
                }
            } catch (Exception e) {
                System.err.println("Model " + model + " error: " + e.getMessage());
                lastException = e;
            }
        }
        
        throw new RuntimeException("All Gemini models failed. Last error: " + (lastException != null ? lastException.getMessage() : "Unknown"));
    }

    private List<MergedNewsCluster> parseClusterJsonFromAI(String rawText) {
        try {
            if (rawText == null || rawText.isEmpty()) {
                 return List.of(new MergedNewsCluster("Analysis Error", "AI returned empty response.", "N/A", "N/A", "0", "N/A", Collections.emptyList()));
            }
            String jsonText = rawText.replace("```json", "").replace("```", "").trim();
            return objectMapper.readValue(jsonText, new TypeReference<List<MergedNewsCluster>>(){});
        } catch (Exception e) {
            System.err.println("Failed to parse Cluster JSON: " + rawText);
            e.printStackTrace();
            return List.of(new MergedNewsCluster(
                "Analysis Error",
                "Failed to parse AI response. Raw output logged on server.",
                "N/A", "N/A", "0", "N/A",
                Collections.emptyList()
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromResponse(Map<String, Object> responseBody) {
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> candidate = candidates.get(0);
                String finishReason = (String) candidate.get("finishReason");
                if ("SAFETY".equals(finishReason)) {
                    System.err.println("Gemini Safety Filter Triggered!");
                    return null;
                }
                
                Map<String, Object> content = (Map<String, Object>) candidate.get("content");
                if (content != null) {
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                    if (parts != null && !parts.isEmpty()) {
                        return (String) parts.get(0).get("text");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "{}";
    }
}
