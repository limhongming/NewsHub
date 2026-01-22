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

    // Conservative fallback list using only highly reliable 2026 models
    private static final List<String> FALLBACK_MODELS = List.of(
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.0-flash-lite",
        "gemini-2.0-flash"
    );

    private static final String API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final String MODELS_API_URL = "https://generativelanguage.googleapis.com/v1beta/models?key=%s";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);

    private record ApiResult(String text, String model) {}

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
                    
                    // Always ensure flash-lite is at top for user selection
                    geminiModels.add(new GeminiModel("gemini-2.5-flash-lite", "v1beta", "Gemini 2.5 Flash-Lite", "Highest Volume (1000 RPD)", 1000000, 65535));
                    
                    for (Map<String, Object> m : modelsList) {
                        String name = (String) m.get("name"); 
                        if (name != null && name.contains("gemini")) {
                            String shortName = name.replace("models/", "");
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
        } catch (Exception e) { e.printStackTrace(); }
        return List.of(new GeminiModel("gemini-2.5-flash-lite", "v1beta", "Gemini 2.5 Flash-Lite", "Manual Fallback", 1000000, 65535));
    }

    public AnalysisResponse.AnalysisData analyzeText(String text) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your_api_key_here")) {
            return new AnalysisResponse.AnalysisData("API Key is not configured.");
        }
        String prompt = """
                Analyze the following news article text. Focus on current developments.
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
            ApiResult result = callGeminiApiWithFallback(prompt, null);
            return parseJsonFromAI(result.text());
        } catch (Exception e) {
            return new AnalysisResponse.AnalysisData("Analysis failed: " + e.getMessage());
        }
    }

    public List<MergedNewsCluster> processAndClusterNews(List<NewsItem> items, String language, boolean shouldCluster, String preferredModel) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your_api_key_here")) {
             return List.of(new MergedNewsCluster("API Key Missing", "Please configure gemini.api.key", "N/A", "N/A", "0", "N/A", Collections.emptyList(), "None"));
        }
        List<NewsItem> validItems = items.stream()
                .filter(item -> !item.title().startsWith("System Error") && !item.title().startsWith("No News Found"))
                .collect(Collectors.toList());
        if (validItems.isEmpty()) {
            return List.of(new MergedNewsCluster("No Content to Analyze", "The news feed returned no valid articles.", "N/A", "N/A", "0", "N/A", Collections.emptyList(), "None"));
        }
        StringBuilder itemsText = new StringBuilder();
        for (NewsItem item : validItems) {
            itemsText.append("- Title: ").append(item.title()).append("\nLink: ").append(item.link()).append("\nSnippet: ").append(item.summary()).append("\n\n");
        }
        String fullText = itemsText.toString();
        if (fullText.length() > 30000) fullText = fullText.substring(0, 30000) + "...[TRUNCATED]";

        String clusteringInstruction = shouldCluster 
            ? "1. CLUSTERING: Group articles that are about the SAME event. Synthesize a single comprehensive summary. Prioritize LATEST info."
            : "1. NO CLUSTERING: Treat each item separately.";

        String prompt = """
                You are an expert analyst. Analyze these news items. FOCUS ON LATEST INFORMATION.
                %s
                2. TRANSLATION: Translate \"topic\", \"summary\", \"economic_impact\", \"global_impact\", and \"what_next\" into %s.
                3. ANALYSIS: For each group, provide summary, economic impact, global impact, impact rating (1-10), and prediction.
                Input:
                %s
                Output Schema (JSON Array):
                [{"topic":"...","summary":"...","economic_impact":"...","global_impact":"...","impact_rating":"8","what_next":"...","related_links":["url1"]}]
                """.formatted(clusteringInstruction, language.equals("Chinese") ? "Simplified Chinese (zh-CN)" : language, fullText);

        try {
            ApiResult result = callGeminiApiWithFallback(prompt, preferredModel);
            List<MergedNewsCluster> clusters = parseClusterJsonFromAI(result.text());
            return clusters.stream()
                .map(c -> new MergedNewsCluster(c.topic(), c.summary(), c.economic_impact(), c.global_impact(), c.impact_rating(), c.what_next(), c.related_links(), result.model()))
                .collect(Collectors.toList());
        } catch (Exception e) {
            String msg = (e instanceof HttpClientErrorException) ? ((HttpClientErrorException) e).getResponseBodyAsString() : e.getMessage();
            return List.of(new MergedNewsCluster("System Error", "Analysis Failed: " + msg, "N/A", "N/A", "0", "N/A", Collections.emptyList(), "Error"));
        }
    }

    private ApiResult callGeminiApiWithFallback(String prompt, String preferredModel) {
        List<Map<String, String>> safetySettings = List.of(
            Map.of("category", "HARM_CATEGORY_HARASSMENT", "threshold", "BLOCK_NONE"),
            Map.of("category", "HARM_CATEGORY_HATE_SPEECH", "threshold", "BLOCK_NONE"),
            Map.of("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold", "BLOCK_NONE"),
            Map.of("category", "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold", "BLOCK_NONE")
        );
        Map<String, Object> requestBody = Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))), "safetySettings", safetySettings);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        List<String> modelsToTry = new ArrayList<>();
        if (preferredModel != null && !preferredModel.isEmpty()) modelsToTry.add(preferredModel);
        for (String m : FALLBACK_MODELS) { if (!modelsToTry.contains(m)) modelsToTry.add(m); }

        List<String> errors = new ArrayList<>();
        for (String model : modelsToTry) {
            try {
                System.out.println("Trying Gemini Model: " + model);
                String url = String.format(API_URL_TEMPLATE, model, apiKey);
                ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    String extracted = extractTextFromResponse(response.getBody());
                    if (extracted == null || extracted.isEmpty() || extracted.equals("{}")) throw new RuntimeException("Safety Blocked/Empty");
                    return new ApiResult(extracted, model);
                }
            } catch (HttpClientErrorException e) {
                String error = "Model " + model + ": " + e.getStatusCode();
                errors.add(error);
                if (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 503 || e.getStatusCode().value() == 404) continue;
                throw new RuntimeException("Critical API Error: " + e.getResponseBodyAsString());
            } catch (Exception e) { 
                errors.add("Model " + model + ": " + e.getMessage());
            }
        }
        throw new RuntimeException("All models failed. Attempts: " + String.join(" | ", errors));
    }

    private List<MergedNewsCluster> parseClusterJsonFromAI(String rawText) {
        try {
            String jsonText = rawText.replace("```json", "").replace("```", "").trim();
            return objectMapper.readValue(jsonText, new TypeReference<List<MergedNewsCluster>>(){});
        } catch (Exception e) {
            return List.of(new MergedNewsCluster("Analysis Error", "Failed to parse AI response.", "N/A", "N/A", "0", "N/A", Collections.emptyList(), "ParseError"));
        }
    }

    private String extractTextFromResponse(Map<String, Object> responseBody) {
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> candidate = candidates.get(0);
                if ("SAFETY".equals(candidate.get("finishReason"))) return null;
                Map<String, Object> content = (Map<String, Object>) candidate.get("content");
                if (content != null) {
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                    if (parts != null && !parts.isEmpty()) return (String) parts.get(0).get("text");
                }
            }
        } catch (Exception e) {}
        return "{}";
    }
}