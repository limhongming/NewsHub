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

    // Exact IDs from your screenshots for maximum reliability
    private static final List<String> FALLBACK_MODELS = List.of(
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash-lite-001",
        "gemini-2.0-flash-001",
        "gemini-2.5-flash",
        "gemini-2.0-flash"
    );

    private static final String API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final String MODELS_API_URL = "https://generativelanguage.googleapis.com/v1beta/models?key=%s";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);

    private static class ApiResult {
        private final String text;
        private final String model;
        
        public ApiResult(String text, String model) {
            this.text = text;
            this.model = model;
        }
        
        public String text() { return text; }
        public String model() { return model; }
    }

    public List<GeminiModel> listAvailableModels() {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your_api_key_here")) {
            return Collections.emptyList();
        }
        try {
            String url = String.format(MODELS_API_URL, apiKey);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, null, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object modelsObj = response.getBody().get("models");
                if (modelsObj instanceof List) {
                    List<?> modelsList = (List<?>) modelsObj;
                    List<GeminiModel> geminiModels = new ArrayList<>();
                    geminiModels.add(new GeminiModel("gemini-2.5-flash-lite", "v1beta", "Gemini 2.5 Flash-Lite", "Highest Volume (1000 RPD)", 1000000, 65535));
                    for (Object obj : modelsList) {
                        if (obj instanceof Map) {
                            Map<?, ?> m = (Map<?, ?>) obj;
                            String name = (String) m.get("name"); 
                            if (name != null && name.contains("gemini")) {
                                String shortName = name.replace("models/", "");
                                if (shortName.equals("gemini-2.5-flash-lite")) continue;
                                Object version = m.get("version");
                                Object displayName = m.get("displayName");
                                Object description = m.get("description");
                                Object inputTokenLimit = m.get("inputTokenLimit");
                                Object outputTokenLimit = m.get("outputTokenLimit");
                                
                                geminiModels.add(new GeminiModel(
                                    shortName, 
                                    version != null ? (String) version : "", 
                                    displayName != null ? (String) displayName : "",
                                    description != null ? (String) description : "",
                                    inputTokenLimit != null ? ((Number) inputTokenLimit).intValue() : 0,
                                    outputTokenLimit != null ? ((Number) outputTokenLimit).intValue() : 0
                                ));
                            }
                        }
                    }
                    return geminiModels;
                }
            }
        } catch (Exception e) { 
            e.printStackTrace(); 
        }
        return List.of(new GeminiModel("gemini-2.5-flash-lite", "v1beta", "Gemini 2.5 Flash-Lite", "Manual Fallback", 1000000, 65535));
    }

    public AnalysisResponse.AnalysisData analyzeText(String text) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your_api_key_here")) return new AnalysisResponse.AnalysisData("API Key Missing");
        String prompt = "Analyze the following news text. Focus on current developments.\n\n" + text + "\n\nReturn JSON: {\"summary\":\"...\",\"economic_impact\":\"...\",\"global_impact\":\"...\",\"impact_rating\":5,\"urgency\":\"Medium\"}";
        try {
            ApiResult result = callGeminiApiWithFallback(prompt, null);
            return parseJsonFromAI(result.text());
        } catch (Exception e) { return new AnalysisResponse.AnalysisData("Analysis failed: " + e.getMessage()); }
    }

    public MergedNewsCluster analyzeSnippet(String title, String snippet, String lang, String preferredModel) {
        String prompt = String.format("""
            Analyze this news snippet. FOCUS ON LATEST INFORMATION.
            Title: %s
            Content: %s
            
            1. TRANSLATION: Translate \"topic\", \"summary\", \"economic_impact\", \"global_impact\", and \"what_next\" into %s.
            2. ANALYSIS: Provide synthesized summary, economic impact, global impact, rating (1-10), and prediction.
            
            Return ONLY JSON:
            {\"topic\":\"...\",\"summary\":\"...\",\"economic_impact\":\"...\",\"global_impact\":\"...\",\"impact_rating\":\"8\",\"what_next\":\"...\"}
            """, title, snippet, lang.equals("Chinese") ? "Simplified Chinese (zh-CN)" : lang);

        try {
            ApiResult result = callGeminiApiWithFallback(prompt, preferredModel);
            Map<String, Object> map = objectMapper.readValue(result.text().replace("```json", "").replace("```", "").trim(), new TypeReference<Map<String, Object>>(){});
            return new MergedNewsCluster(
                (String)map.get("topic"), (String)map.get("summary"), (String)map.get("economic_impact"),
                (String)map.get("global_impact"), String.valueOf(map.get("impact_rating")), (String)map.get("what_next"),
                Collections.emptyList(), result.model()
            );
        } catch (Exception e) {
            return new MergedNewsCluster("Analysis Error", e.getMessage(), "N/A", "N/A", "0", "N/A", Collections.emptyList(), "Error");
        }
    }

    public List<MergedNewsCluster> processAndClusterNews(List<NewsItem> items, String language, boolean shouldCluster, String preferredModel) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your_api_key_here")) {
             return List.of(new MergedNewsCluster("API Key Missing", "Please configure gemini.api.key", "N/A", "N/A", "0", "N/A", Collections.emptyList(), "None"));
        }
        List<NewsItem> validItems = items.stream()
                .filter(item -> !item.title().startsWith("System Error") && !item.title().startsWith("No News Found"))
                .collect(Collectors.toList());
        if (validItems.isEmpty()) return List.of(new MergedNewsCluster("No Content", "No valid articles found.", "N/A", "N/A", "0", "N/A", Collections.emptyList(), "None"));
        
        StringBuilder itemsText = new StringBuilder();
        for (NewsItem item : validItems) itemsText.append("- Title: ").append(item.title()).append("\nLink: ").append(item.link()).append("\nSnippet: ").append(item.summary()).append("\n\n");
        String fullText = itemsText.toString();
        if (fullText.length() > 30000) fullText = fullText.substring(0, 30000) + "...[TRUNCATED]";

        String prompt = String.format("""
                Expert Analyst Task: Group articles about SAME event. Synthesize unified summary. LATEST INFO FIRST.
                2. TRANSLATE: \"topic\", \"summary\", \"economic_impact\", \"global_impact\", \"what_next\" into %s.
                Input:
                %s
                Output Schema: [{\"topic\":\"...\",\"summary\":\"...\",\"economic_impact\":\"...\",\"global_impact\":\"...\",\"impact_rating\":\"8\",\"what_next\":\"...\",\"related_links\":[\"url1\"]}]
                """, language.equals("Chinese") ? "Simplified Chinese (zh-CN)" : language, fullText);

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
        for (String m : FALLBACK_MODELS) if (!modelsToTry.contains(m)) modelsToTry.add(m);

        List<String> errors = new ArrayList<>();
        for (String model : modelsToTry) {
            try {
                String url = String.format(API_URL_TEMPLATE, model, apiKey);
                ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    String extracted = extractTextFromResponse(response.getBody());
                    if (extracted == null || extracted.isEmpty() || extracted.equals("{}")) throw new RuntimeException("Safety Blocked/Empty");
                    return new ApiResult(extracted, model);
                }
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 503 || e.getStatusCode().value() == 404) {
                    errors.add(model + ": " + e.getStatusCode());
                    continue;
                }
                throw new RuntimeException("API Error (" + model + "): " + e.getResponseBodyAsString());
            } catch (Exception e) { errors.add(model + ": " + e.getMessage()); }
        }
        throw new RuntimeException("All models failed. Attempts: " + String.join(" | ", errors));
    }

    private List<MergedNewsCluster> parseClusterJsonFromAI(String rawText) {
        try {
            String jsonText = rawText.replace("```json", "").replace("```", "").trim();
            return objectMapper.readValue(jsonText, new TypeReference<List<MergedNewsCluster>>(){});
        } catch (Exception e) {
            return List.of(new MergedNewsCluster("Analysis Error", "Failed to parse JSON.", "N/A", "N/A", "0", "N/A", Collections.emptyList(), "ParseError"));
        }
    }

    private AnalysisResponse.AnalysisData parseJsonFromAI(String rawText) {
        try {
            String jsonText = rawText.replace("```json", "").replace("```", "").trim();
            return objectMapper.readValue(jsonText, AnalysisResponse.AnalysisData.class);
        } catch (Exception e) { return new AnalysisResponse.AnalysisData("Failed to parse response."); }
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
