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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class NewsSystemService {

    @Value("${gemini.api.key}")
    private String apiKey;

    // Official Gemini models - production-ready, stable models only
    // Ordered by cost/performance: free/cheaper models first, then premium
    // Version numbers are explicitly included in model names
    private static final List<String> FALLBACK_MODELS = List.of(
        // Free tier models (highest rate limits, lowest cost)
        "gemini-2.0-flash-lite",      // Version 2.0 - Official free tier
        "gemini-2.0-flash-lite-001",  // Version 2.0.001 - Alternative free tier variant
        "gemini-2.5-flash-lite",      // Version 2.5 - Latest free tier with improved capabilities
        
        // Standard tier models (good balance of cost and performance)
        "gemini-2.0-flash",           // Version 2.0 - Official standard tier
        "gemini-2.0-flash-001",       // Version 2.0.001 - Alternative standard variant
        "gemini-2.5-flash",           // Version 2.5 - Latest premium with best performance
        
        // Pro models (higher cost, better for complex tasks)
        "gemini-2.0-pro",             // Version 2.0 - Pro version for complex reasoning
        "gemini-1.5-pro",             // Version 1.5 - Legacy pro model (if still available)
        "gemini-1.5-flash"            // Version 1.5 - Flash model from previous generation
    );
    
    // Model cost/priority mapping (lower number = higher priority for cost savings)
    // Updated to include all official models with version clarity
    private static final Map<String, Integer> MODEL_PRIORITY = Map.of(
        "gemini-2.0-flash-lite", 1,      // v2.0
        "gemini-2.0-flash-lite-001", 2,  // v2.0.001
        "gemini-2.5-flash-lite", 3,      // v2.5
        "gemini-2.0-flash", 4,           // v2.0
        "gemini-2.0-flash-001", 5,       // v2.0.001
        "gemini-2.5-flash", 6,           // v2.5
        "gemini-2.0-pro", 7,             // v2.0
        "gemini-1.5-pro", 8,             // v1.5
        "gemini-1.5-flash", 9            // v1.5
    );
    
    // Model version mapping for display purposes
    private static final Map<String, String> MODEL_VERSIONS = Map.of(
        "gemini-2.0-flash-lite", "2.0",
        "gemini-2.0-flash-lite-001", "2.0.001",
        "gemini-2.5-flash-lite", "2.5",
        "gemini-2.0-flash", "2.0",
        "gemini-2.0-flash-001", "2.0.001",
        "gemini-2.5-flash", "2.5",
        "gemini-2.0-pro", "2.0",
        "gemini-1.5-pro", "1.5",
        "gemini-1.5-flash", "1.5"
    );
    
    // Official model patterns - used to filter experimental/preview models
    private static final List<String> OFFICIAL_MODEL_PATTERNS = List.of(
        "gemini-\\d+\\.\\d+-flash-lite(-\\d+)?",      // Flash Lite variants
        "gemini-\\d+\\.\\d+-flash(-\\d+)?",           // Flash variants  
        "gemini-\\d+\\.\\d+-pro(-\\d+)?",             // Pro variants
        "gemini-\\d+\\.\\d+-pro-vision(-\\d+)?"       // Pro Vision variants
    );

    private static final String API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final String MODELS_API_URL = "https://generativelanguage.googleapis.com/v1beta/models?key=%s";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);
    
    // Request coalescing: track pending requests by prompt hash
    private final ConcurrentMap<String, Object> pendingRequests = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    
    // Rate limiting tracking
    private volatile long lastRateLimitTime = 0;
    private static final long RATE_LIMIT_COOLDOWN_MS = 60000; // 1 minute cooldown after rate limit
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1000; // 1 second
    private static final long MAX_RETRY_DELAY_MS = 10000; // 10 seconds
    
    // API usage tracking
    private final ConcurrentMap<String, Integer> apiCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> rateLimitCounts = new ConcurrentHashMap<>();
    private volatile long lastUsageResetTime = System.currentTimeMillis();
    private static final long USAGE_RESET_INTERVAL_MS = 3600000; // 1 hour

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
                    
                    // Add our predefined official models first (ensuring they're always shown)
                    for (String modelName : FALLBACK_MODELS) {
                        geminiModels.add(new GeminiModel(
                            modelName,
                            "v1beta",
                            getDisplayNameForModel(modelName),
                            getDescriptionForModel(modelName),
                            getDefaultInputTokens(modelName),
                            getDefaultOutputTokens(modelName)
                        ));
                    }
                    
                    // Then add any other official models from the API response
                    for (Object obj : modelsList) {
                        if (obj instanceof Map) {
                            Map<?, ?> m = (Map<?, ?>) obj;
                            String name = (String) m.get("name"); 
                            if (name != null && name.contains("gemini")) {
                                String shortName = name.replace("models/", "");
                                
                                // Skip if already in our predefined list
                                if (FALLBACK_MODELS.contains(shortName)) continue;
                                
                                // Check if this is an official model (matches our patterns)
                                boolean isOfficial = false;
                                for (String pattern : OFFICIAL_MODEL_PATTERNS) {
                                    if (shortName.matches(pattern)) {
                                        isOfficial = true;
                                        break;
                                    }
                                }
                                
                                // Only add official models
                                if (isOfficial) {
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
                    }
                    return geminiModels;
                }
            }
        } catch (Exception e) { 
            e.printStackTrace(); 
        }
        
        // Fallback: return our predefined official models
        List<GeminiModel> fallbackModels = new ArrayList<>();
        for (String modelName : FALLBACK_MODELS) {
            fallbackModels.add(new GeminiModel(
                modelName,
                "v1beta",
                getDisplayNameForModel(modelName),
                getDescriptionForModel(modelName),
                getDefaultInputTokens(modelName),
                getDefaultOutputTokens(modelName)
            ));
        }
        return fallbackModels;
    }
    
    private String getDisplayNameForModel(String modelName) {
        String version = MODEL_VERSIONS.getOrDefault(modelName, extractVersionFromName(modelName));
        if (modelName.contains("flash-lite")) return "Gemini Flash Lite v" + version;
        if (modelName.contains("flash")) return "Gemini Flash v" + version;
        if (modelName.contains("pro")) return "Gemini Pro v" + version;
        return "Gemini Model v" + version;
    }
    
    private String getDescriptionForModel(String modelName) {
        String version = MODEL_VERSIONS.getOrDefault(modelName, extractVersionFromName(modelName));
        if (modelName.contains("2.5")) return "Latest generation (v" + version + ") with improved capabilities";
        if (modelName.contains("2.0")) return "Current stable version (v" + version + ")";
        if (modelName.contains("1.5")) return "Previous generation (v" + version + "), still supported";
        return "Official Gemini model (v" + version + ")";
    }
    
    private String extractVersionFromName(String modelName) {
        // Extract version from model name like "gemini-2.5-flash-lite" -> "2.5"
        if (modelName.contains("2.5")) return "2.5";
        if (modelName.contains("2.0")) return "2.0";
        if (modelName.contains("1.5")) return "1.5";
        if (modelName.contains("1.0")) return "1.0";
        return "1.0";
    }
    
    private int getDefaultInputTokens(String modelName) {
        if (modelName.contains("flash-lite")) return 1000000;
        if (modelName.contains("flash")) return 1000000;
        if (modelName.contains("pro")) return 2000000;
        return 1000000;
    }
    
    private int getDefaultOutputTokens(String modelName) {
        if (modelName.contains("flash-lite")) return 8192;
        if (modelName.contains("flash")) return 8192;
        if (modelName.contains("pro")) return 8192;
        return 8192;
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
        // Check if we're in rate limit cooldown
        long now = System.currentTimeMillis();
        if (now - lastRateLimitTime < RATE_LIMIT_COOLDOWN_MS) {
            throw new RuntimeException("Rate limit cooldown active. Please wait " + 
                ((RATE_LIMIT_COOLDOWN_MS - (now - lastRateLimitTime)) / 1000) + " seconds.");
        }
        
        // Reset usage stats if interval has passed
        resetUsageStatsIfNeeded();
        
        // Create request hash for coalescing
        String requestHash = Integer.toHexString((prompt + (preferredModel != null ? preferredModel : "")).hashCode());
        
        // Request coalescing: check if same request is already being processed
        synchronized (lock) {
            if (pendingRequests.containsKey(requestHash)) {
                // Wait for the pending request to complete
                try {
                    lock.wait(5000); // Wait up to 5 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // After waiting, check if result is available (simplified - in production would need more complex logic)
            }
            pendingRequests.put(requestHash, new Object());
        }
        
        try {
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
            
            // Exponential backoff with retries
            for (int retry = 0; retry < MAX_RETRIES; retry++) {
                for (String model : modelsToTry) {
                    try {
                        // Add delay between retries (exponential backoff)
                        if (retry > 0) {
                            long delay = Math.min(INITIAL_RETRY_DELAY_MS * (1L << (retry - 1)), MAX_RETRY_DELAY_MS);
                            System.out.println("DEBUG: Retry " + retry + " for model " + model + " after " + delay + "ms delay");
                            Thread.sleep(delay);
                        }
                        
                        // Track API call attempt
                        trackApiCall(model);
                        
                        String url = String.format(API_URL_TEMPLATE, model, apiKey);
                        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
                        
                        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                            String extracted = extractTextFromResponse(response.getBody());
                            if (extracted == null || extracted.isEmpty() || extracted.equals("{}")) {
                                throw new RuntimeException("Safety Blocked/Empty");
                            }
                            System.out.println("DEBUG: Successfully called Gemini API with model: " + model);
                            logApiUsage(model, true);
                            return new ApiResult(extracted, model);
                        }
                    } catch (HttpClientErrorException e) {
                        if (e.getStatusCode().value() == 429) {
                            // Rate limit hit - update cooldown timer and track
                            lastRateLimitTime = System.currentTimeMillis();
                            trackRateLimit(model);
                            System.out.println("WARN: Rate limit (429) hit for model " + model + ", activating cooldown");
                            errors.add(model + ": " + e.getStatusCode() + " (Rate Limit)");
                            continue; // Try next model
                        } else if (e.getStatusCode().value() == 503 || e.getStatusCode().value() == 404) {
                            errors.add(model + ": " + e.getStatusCode());
                            continue; // Try next model
                        }
                        throw new RuntimeException("API Error (" + model + "): " + e.getResponseBodyAsString());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Request interrupted");
                    } catch (Exception e) { 
                        errors.add(model + ": " + e.getMessage()); 
                    }
                }
                
                // If we've tried all models in this retry and failed, continue to next retry
                if (retry < MAX_RETRIES - 1) {
                    System.out.println("DEBUG: Retry " + (retry + 1) + " of " + MAX_RETRIES + " after all models failed");
                }
            }
            
            // All retries exhausted
            throw new RuntimeException("All models failed after " + MAX_RETRIES + " retries. Attempts: " + String.join(" | ", errors));
        } finally {
            // Clean up pending request
            synchronized (lock) {
                pendingRequests.remove(requestHash);
                lock.notifyAll();
            }
        }
    }
    
    private void trackApiCall(String model) {
        apiCallCounts.merge(model, 1, Integer::sum);
        System.out.println("API Usage: Model " + model + " called " + apiCallCounts.get(model) + " times (this hour)");
    }
    
    private void trackRateLimit(String model) {
        rateLimitCounts.merge(model, 1, Integer::sum);
        System.out.println("Rate Limit Alert: Model " + model + " hit rate limit " + rateLimitCounts.get(model) + " times (this hour)");
    }
    
    private void logApiUsage(String model, boolean success) {
        // Log detailed usage for monitoring
        System.out.println("API Call Summary - Model: " + model + ", Success: " + success + 
                         ", Total calls this hour: " + apiCallCounts.getOrDefault(model, 0) +
                         ", Rate limits hit: " + rateLimitCounts.getOrDefault(model, 0));
    }
    
    private void resetUsageStatsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastUsageResetTime >= USAGE_RESET_INTERVAL_MS) {
            apiCallCounts.clear();
            rateLimitCounts.clear();
            lastUsageResetTime = now;
            System.out.println("API usage statistics reset (hourly interval)");
        }
    }
    
    // Public method to get current API usage stats (could be exposed via REST endpoint)
    public Map<String, Object> getApiUsageStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("totalCalls", apiCallCounts.values().stream().mapToInt(Integer::intValue).sum());
        stats.put("rateLimitCount", rateLimitCounts.values().stream().mapToInt(Integer::intValue).sum());
        stats.put("lastReset", lastUsageResetTime);
        stats.put("nextResetInMs", USAGE_RESET_INTERVAL_MS - (System.currentTimeMillis() - lastUsageResetTime));
        stats.put("perModelCalls", new HashMap<>(apiCallCounts));
        stats.put("perModelRateLimits", new HashMap<>(rateLimitCounts));
        return stats;
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
