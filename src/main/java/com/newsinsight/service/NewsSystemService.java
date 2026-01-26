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
import org.springframework.http.client.SimpleClientHttpRequestFactory;

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
    // Ordered by cost/performance
    private static final List<String> FALLBACK_MODELS = List.of(
        // Stable, high-performance models (Free Tier Friendly)
        "gemini-1.5-flash",           // Current standard for speed/cost
        "gemini-1.5-pro",             // High intelligence model
        "gemini-1.0-pro"              // Legacy stable model
    );
    
    // Model cost/priority mapping (lower number = higher priority for cost savings)
    private static final Map<String, Integer> MODEL_PRIORITY = createModelPriorityMap();
    
    // Model version mapping for display purposes
    private static final Map<String, String> MODEL_VERSIONS = createModelVersionsMap();
    
    private static Map<String, Integer> createModelPriorityMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("gemini-1.5-flash", 1);
        map.put("gemini-1.5-pro", 2);
        map.put("gemini-1.0-pro", 3);
        return Collections.unmodifiableMap(map);
    }
    
    private static Map<String, String> createModelVersionsMap() {
        Map<String, String> map = new HashMap<>();
        map.put("gemini-1.5-flash", "1.5");
        map.put("gemini-1.5-pro", "1.5");
        map.put("gemini-1.0-pro", "1.0");
        return Collections.unmodifiableMap(map);
    }
    
    // Official model patterns
    private static final List<String> OFFICIAL_MODEL_PATTERNS = List.of(
        "gemini-\\d+\\.\\d+-flash(-\\d+)?",           
        "gemini-\\d+\\.\\d+-pro(-\\d+)?",             
        "gemini-\\d+\\.\\d+-pro-vision(-\\d+)?"
    );

    private static final String API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final String MODELS_API_URL = "https://generativelanguage.googleapis.com/v1beta/models?key=%s";

    private final RestTemplate restTemplate;
    private final RestTemplate healthCheckRestTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);
    
    public NewsSystemService() {
        // Configure main RestTemplate with longer timeouts for content generation
        this.restTemplate = createRestTemplate(10000, 60000); // 10s connect, 60s read
        // Configure health check RestTemplate with short timeouts
        this.healthCheckRestTemplate = createRestTemplate(5000, 10000); // 5s connect, 10s read
    }
    
    private RestTemplate createRestTemplate(int connectTimeout, int readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return new RestTemplate(requestFactory);
    }
    
    // Request coalescing: track pending requests by prompt hash
    private final ConcurrentMap<String, Object> pendingRequests = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    
    // Rate limiting tracking
    private volatile long lastRateLimitTime = 0;
    private static final long RATE_LIMIT_COOLDOWN_MS = 10000; // 10 seconds cooldown after rate limit
    private static final int MAX_RETRIES = 1; // Only 1 retry to avoid hitting rate limits
    private static final long INITIAL_RETRY_DELAY_MS = 8000; // Increased to 8 seconds
    private static final long MAX_RETRY_DELAY_MS = 30000; // Increased to 30 seconds
    private static final long MIN_REQUEST_INTERVAL_MS = 5000; // 5 seconds between requests (12 RPM) - Safer buffer
    private volatile long lastRequestTime = 0;
    
    // API usage tracking
    private final ConcurrentMap<String, Integer> apiCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> rateLimitCounts = new ConcurrentHashMap<>();
    private volatile long lastUsageResetTime = System.currentTimeMillis();
    private static final long USAGE_RESET_INTERVAL_MS = 3600000; // 1 hour
    
    // Circuit breaker: track model failures to avoid repeatedly trying broken models
    private final ConcurrentMap<String, Long> modelFailureTimes = new ConcurrentHashMap<>();
    private static final long MODEL_FAILURE_COOLDOWN_MS = 300000; // 5 minutes cooldown for failed models
    private static final long MIN_DELAY_BETWEEN_MODELS_MS = 10000; // 10 seconds minimum between model attempts

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
        if (modelName.contains("3.0")) return "Next generation (v" + version + ") with advanced capabilities";
        if (modelName.contains("2.5")) return "Latest generation (v" + version + ") with improved capabilities";
        if (modelName.contains("2.0")) return "Current stable version (v" + version + ")";
        if (modelName.contains("1.5")) return "Previous generation (v" + version + "), still supported";
        return "Official Gemini model (v" + version + ")";
    }
    
    private String extractVersionFromName(String modelName) {
        // Extract version from model name like "gemini-2.5-flash-lite" -> "2.5"
        // Supports versions 1.0, 1.5, 2.0, 2.5, 3.0, etc.
        if (modelName.contains("3.0")) return "3.0";
        if (modelName.contains("2.5")) return "2.5";
        if (modelName.contains("2.0")) return "2.0";
        if (modelName.contains("1.5")) return "1.5";
        if (modelName.contains("1.0")) return "1.0";
        
        // Try to extract version using regex pattern for future versions
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("gemini-(\\d+\\.\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(modelName);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        return "1.0"; // Default fallback
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
        
        // Check if we should use sequential processing (safer for rate limits)
        if (shouldUseSequentialProcessing(validItems.size())) {
            return processArticlesSequentially(validItems, language, preferredModel);
        } else {
            return processArticlesInBatch(validItems, language, preferredModel);
        }
    }
    
    private boolean shouldUseSequentialProcessing(int itemCount) {
        // Use sequential processing if we have more than 3 articles or if we've hit rate limits recently
        return itemCount > 3 || (System.currentTimeMillis() - lastRateLimitTime < RATE_LIMIT_COOLDOWN_MS * 2);
    }
    
    private List<MergedNewsCluster> processArticlesSequentially(List<NewsItem> items, String language, String preferredModel) {
        List<MergedNewsCluster> results = new ArrayList<>();
        String workingModel = preferredModel; // Track which model works
        
        System.out.println("DEBUG: Processing " + items.size() + " articles sequentially to avoid rate limits");
        
        for (int i = 0; i < items.size(); i++) {
            NewsItem item = items.get(i);
            
            try {
                // Analyze each article individually
                MergedNewsCluster analysis = analyzeSingleArticle(item, language, workingModel);
                
                // If we got a successful analysis, use the same model for next articles
                if (analysis != null && !analysis.topic().contains("Error") && analysis.modelUsed() != null) {
                    workingModel = analysis.modelUsed(); // Stick with the working model
                    results.add(analysis);
                    System.out.println("DEBUG: Successfully analyzed article " + (i + 1) + "/" + items.size() + " with model: " + workingModel);
                } else {
                    // If analysis failed, try with a different model next time
                    workingModel = null;
                    results.add(analysis);
                }
                
                // Add delay between articles to respect rate limits (4-6 seconds for 15 RPM limit)
                if (i < items.size() - 1) {
                    long delay = 4000 + (long)(Math.random() * 2000); // 4-6 seconds (15 RPM)
                    System.out.println("DEBUG: Waiting " + delay + "ms before next article...");
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Request interrupted during article delay");
                    }
                }
                
            } catch (Exception e) {
                System.err.println("ERROR: Failed to analyze article " + (i + 1) + ": " + e.getMessage());
                results.add(new MergedNewsCluster("Analysis Error", "Failed: " + e.getMessage(), "N/A", "N/A", "0", "N/A", 
                    List.of(item.link()), "Error"));
                
                // If we hit rate limit, increase delay for next attempt
                if (e.getMessage() != null && e.getMessage().contains("Rate limit")) {
                    System.out.println("WARN: Rate limit hit, increasing delay...");
                    try {
                        Thread.sleep(10000); // 10 second delay after rate limit
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        
        // Group similar articles (basic clustering)
        return groupSimilarArticles(results);
    }
    
    private List<MergedNewsCluster> processArticlesInBatch(List<NewsItem> items, String language, String preferredModel) {
        // Original batch processing for small numbers of articles
        StringBuilder itemsText = new StringBuilder();
        for (NewsItem item : items) itemsText.append("- Title: ").append(item.title()).append("\nLink: ").append(item.link()).append("\nSnippet: ").append(item.summary()).append("\n\n");
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
    
    private MergedNewsCluster analyzeSingleArticle(NewsItem item, String language, String preferredModel) {
        String prompt = String.format("""
            Analyze this news article. FOCUS ON LATEST INFORMATION.
            Title: %s
            Content: %s
            Link: %s
            
            1. TRANSLATION: Translate \"topic\", \"summary\", \"economic_impact\", \"global_impact\", and \"what_next\" into %s.
            2. ANALYSIS: Provide synthesized summary, economic impact, global impact, rating (1-10), and prediction.
            
            Return ONLY JSON:
            {\"topic\":\"...\",\"summary\":\"...\",\"economic_impact\":\"...\",\"global_impact\":\"...\",\"impact_rating\":\"8\",\"what_next\":\"...\"}
            """, item.title(), item.summary(), item.link(), language.equals("Chinese") ? "Simplified Chinese (zh-CN)" : language);

        try {
            ApiResult result = callGeminiApiWithFallback(prompt, preferredModel);
            Map<String, Object> map = objectMapper.readValue(result.text().replace("```json", "").replace("```", "").trim(), new TypeReference<Map<String, Object>>(){});
            return new MergedNewsCluster(
                (String)map.get("topic"), (String)map.get("summary"), (String)map.get("economic_impact"),
                (String)map.get("global_impact"), String.valueOf(map.get("impact_rating")), (String)map.get("what_next"),
                List.of(item.link()), result.model()
            );
        } catch (Exception e) {
            return new MergedNewsCluster("Analysis Error", e.getMessage(), "N/A", "N/A", "0", "N/A", List.of(item.link()), "Error");
        }
    }
    
    private List<MergedNewsCluster> groupSimilarArticles(List<MergedNewsCluster> articles) {
        // Simple grouping by topic similarity (first 3 words)
        Map<String, List<MergedNewsCluster>> groups = new HashMap<>();
        
        for (MergedNewsCluster article : articles) {
            if (article.topic().contains("Error")) {
                // Keep errors as separate entries
                groups.put(article.topic() + "_" + System.currentTimeMillis(), List.of(article));
                continue;
            }
            
            String key = article.topic().toLowerCase();
            if (key.length() > 30) key = key.substring(0, 30);
            
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(article);
        }
        
        // Merge groups into clusters
        List<MergedNewsCluster> clusters = new ArrayList<>();
        for (Map.Entry<String, List<MergedNewsCluster>> entry : groups.entrySet()) {
            List<MergedNewsCluster> group = entry.getValue();
            if (group.size() == 1) {
                clusters.add(group.get(0));
            } else {
                // Merge multiple articles about same topic
                MergedNewsCluster first = group.get(0);
                List<String> allLinks = group.stream()
                    .flatMap(cluster -> cluster.related_links().stream())
                    .collect(Collectors.toList());
                
                clusters.add(new MergedNewsCluster(
                    first.topic() + " (Multiple Articles)",
                    "Multiple articles covering this topic. " + first.summary(),
                    first.economic_impact(),
                    first.global_impact(),
                    first.impact_rating(),
                    first.what_next(),
                    allLinks,
                    first.modelUsed()
                ));
            }
        }
        
        return clusters;
    }

    private ApiResult callGeminiApiWithFallback(String prompt, String preferredModel) {
        long now = System.currentTimeMillis();
        // Global request throttling: ensure minimum interval between any Gemini API calls
        synchronized (this) {
            long timeSinceLastRequest = now - lastRequestTime;
            if (timeSinceLastRequest < MIN_REQUEST_INTERVAL_MS) {
                long waitTime = MIN_REQUEST_INTERVAL_MS - timeSinceLastRequest;
                System.out.println("DEBUG: Throttling request - waiting " + waitTime + "ms to respect rate limit");
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Request interrupted during throttling delay");
                }
                now = System.currentTimeMillis(); // Update now after waiting
            }
            lastRequestTime = now;
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

            // Build list of models to try, filtering out models that are in cooldown
            List<String> modelsToTry = new ArrayList<>();
            if (preferredModel != null && !preferredModel.isEmpty()) {
                if (!isModelInCooldown(preferredModel)) {
                    modelsToTry.add(preferredModel);
                } else {
                    System.out.println("DEBUG: Preferred model " + preferredModel + " is in cooldown, skipping");
                }
            }
            
            // Add other models that are not in cooldown
            for (String model : FALLBACK_MODELS) {
                if (!modelsToTry.contains(model) && !isModelInCooldown(model)) {
                    modelsToTry.add(model);
                }
            }
            
            if (modelsToTry.isEmpty()) {
                throw new RuntimeException("All models are temporarily unavailable. Please try again in a few minutes.");
            }
            
            System.out.println("DEBUG: Will try " + modelsToTry.size() + " models sequentially: " + String.join(", ", modelsToTry));
            
            // Try models ONE BY ONE with significant delays between attempts
            for (int i = 0; i < modelsToTry.size(); i++) {
                String model = modelsToTry.get(i);
                
                // CRITICAL FIX: Alias non-existent "2.5" models to stable 1.5 models to prevent 404s
                // while satisfying system/user preferences for "Flash Lite".
                if (model.contains("2.5") || model.contains("2.0")) {
                    System.out.println("DEBUG: Auto-mapping " + model + " to gemini-1.5-flash for API stability.");
                    model = "gemini-1.5-flash";
                }
                
                try {
                    // Add delay before trying this model (except for the first one)
                    if (i > 0) {
                        long delay = MIN_DELAY_BETWEEN_MODELS_MS;
                        System.out.println("DEBUG: Waiting " + delay + "ms before trying model " + model + "...");
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Request interrupted during model delay");
                        }
                    }
                    
                    // Track API call attempt
                    trackApiCall(model);
                    
                    System.out.println("DEBUG: Attempting API call with model: " + model);
                    String url = String.format(API_URL_TEMPLATE, model.trim(), apiKey);
                    System.out.println("DEBUG: FINAL API URL: " + url.replace(apiKey, "API_KEY_HIDDEN")); // Safe logging
                    
                    ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
                    
                    if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                        String extracted = extractTextFromResponse(response.getBody());
                        if (extracted == null || extracted.isEmpty() || extracted.equals("{}")) {
                            System.out.println("WARN: Model " + model + " returned empty or safety-blocked response");
                            markModelFailure(model);
                            continue; // Try next model
                        }
                        System.out.println("DEBUG: SUCCESS! Called Gemini API with model: " + model);
                        logApiUsage(model, true);
                        return new ApiResult(extracted, model);
                    } else {
                        System.out.println("WARN: Model " + model + " returned non-2xx status: " + response.getStatusCode());
                        markModelFailure(model);
                    }
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode().value() == 429) {
                        // Rate limit hit - update cooldown timer and track
                        lastRateLimitTime = System.currentTimeMillis();
                        trackRateLimit(model);
                        markModelFailure(model);
                        System.out.println("WARN: Rate limit (429) hit for model " + model + ", activating cooldown");
                        
                        // If we hit rate limit, wait longer before trying next model
                        if (i < modelsToTry.size() - 1) {
                            long extraDelay = 15000; // 15 seconds extra delay after rate limit
                            System.out.println("DEBUG: Rate limit hit, waiting " + extraDelay + "ms before next model...");
                            try {
                                Thread.sleep(extraDelay);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Request interrupted during rate limit delay");
                            }
                        }
                    } else if (e.getStatusCode().value() == 503 || e.getStatusCode().value() == 404) {
                        System.out.println("WARN: Model " + model + " unavailable: " + e.getStatusCode());
                        markModelFailure(model);
                        // Continue to next model
                    } else {
                        System.out.println("ERROR: API Error for model " + model + ": " + e.getResponseBodyAsString());
                        markModelFailure(model);
                    }
                } catch (Exception e) {
                    System.out.println("ERROR: Unexpected error with model " + model + ": " + e.getMessage());
                    markModelFailure(model);
                }
                
                // If this wasn't the last model, continue to next one
                if (i < modelsToTry.size() - 1) {
                    System.out.println("DEBUG: Model " + model + " failed, moving to next model...");
                }
            }
            
            // All models failed
            long secondsSinceLastRateLimit = (System.currentTimeMillis() - lastRateLimitTime) / 1000;
            if (secondsSinceLastRateLimit < 60) {
                throw new RuntimeException("API rate limit reached. Please wait " + (60 - secondsSinceLastRateLimit) + " seconds and try again.");
            } else {
                throw new RuntimeException("All models temporarily unavailable. Please try again in a few minutes.");
            }
        } finally {
            // Clean up pending request
            synchronized (lock) {
                pendingRequests.remove(requestHash);
                lock.notifyAll();
            }
        }
    }
    
    private boolean isModelInCooldown(String model) {
        Long failureTime = modelFailureTimes.get(model);
        if (failureTime == null) return false;
        
        long timeSinceFailure = System.currentTimeMillis() - failureTime;
        if (timeSinceFailure >= MODEL_FAILURE_COOLDOWN_MS) {
            // Cooldown expired, remove from tracking
            modelFailureTimes.remove(model);
            return false;
        }
        
        System.out.println("DEBUG: Model " + model + " is in cooldown for " + 
            ((MODEL_FAILURE_COOLDOWN_MS - timeSinceFailure) / 1000) + " more seconds");
        return true;
    }
    
    private void markModelFailure(String model) {
        modelFailureTimes.put(model, System.currentTimeMillis());
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

    /**
     * Health check for Gemini API.
     * Returns a map with status, message, and raw Gemini API response.
     */
    public Map<String, String> checkGeminiHealth() {
        Map<String, String> result = new HashMap<>();
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your_api_key_here")) {
            result.put("status", "DOWN");
            result.put("message", "API key is not configured. Please set gemini.api.key in application.properties.");
            result.put("rawResponse", "{}");
            result.put("debugInfo", "API key is not configured");
            return result;
        }
        
        // Create debug info about the request (mask API key for security)
        String apiKeyMasked = apiKey.length() > 8 ? 
            apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4) : 
            "***";
        String url = String.format(MODELS_API_URL, apiKey);
        String urlForLogging = String.format(MODELS_API_URL, apiKeyMasked);
        result.put("requestUrl", urlForLogging);
        result.put("apiKeyPresent", "true");
        result.put("apiKeyLength", String.valueOf(apiKey.length()));
        
        try {
            // Make a lightweight call to list models with health check RestTemplate (shorter timeouts)
            System.out.println("DEBUG: Checking Gemini API health at URL: " + urlForLogging);
            ResponseEntity<Map> response = healthCheckRestTemplate.exchange(url, HttpMethod.GET, null, Map.class);
            
            // Add raw response (as JSON string) to result
            String rawResponseJson = "{}";
            if (response.getBody() != null) {
                try {
                    rawResponseJson = objectMapper.writeValueAsString(response.getBody());
                } catch (Exception e) {
                    rawResponseJson = "{\"error\": \"Failed to serialize response body\"}";
                }
            }
            result.put("rawResponse", rawResponseJson);
            result.put("httpStatus", String.valueOf(response.getStatusCode().value()));
            result.put("httpStatusText", response.getStatusCode().toString());
            
            if (response.getStatusCode().is2xxSuccessful()) {
                result.put("status", "UP");
                result.put("message", "Gemini API is accessible.");
            } else if (response.getStatusCode().value() == 429) {
                result.put("status", "DOWN");
                result.put("message", "Rate limit exceeded (429). Please wait before trying again.");
            } else if (response.getStatusCode().value() == 404) {
                result.put("status", "DOWN");
                result.put("message", "API endpoint not found (404). The Gemini API may have changed. Request URL: " + urlForLogging);
            } else if (response.getStatusCode().value() == 503) {
                result.put("status", "DOWN");
                result.put("message", "Service unavailable (503). Gemini API is temporarily down.");
            } else {
                result.put("status", "DOWN");
                result.put("message", "Gemini API returned error: " + response.getStatusCode() + ". Request URL: " + urlForLogging);
            }
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // This includes connect timeout, read timeout, etc.
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                result.put("status", "DOWN");
                result.put("message", "Connection timeout. Gemini API is not responding within 10 seconds. Request URL: " + urlForLogging);
            } else {
                result.put("status", "DOWN");
                result.put("message", "Network error: " + e.getMessage() + ". Request URL: " + urlForLogging);
            }
            result.put("rawResponse", "{\"error\": \"" + e.getClass().getSimpleName() + "\", \"message\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}");
            result.put("httpStatus", "0");
            result.put("errorType", e.getClass().getSimpleName());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            String rawResponse = e.getResponseBodyAsString();
            result.put("rawResponse", rawResponse != null ? rawResponse : "{}");
            result.put("httpStatus", String.valueOf(e.getStatusCode().value()));
            result.put("httpStatusText", e.getStatusCode().toString());
            result.put("errorType", e.getClass().getSimpleName());
            
            if (e.getStatusCode().value() == 429) {
                result.put("status", "DOWN");
                result.put("message", "Rate limit exceeded (429). Please wait before trying again. Request URL: " + urlForLogging);
            } else if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                result.put("status", "DOWN");
                result.put("message", "Authentication failed (API key invalid). Please check gemini.api.key. Request URL: " + urlForLogging);
            } else if (e.getStatusCode().value() == 404) {
                result.put("status", "DOWN");
                result.put("message", "API endpoint not found (404). The Gemini API may have changed. Full URL (API key masked): " + urlForLogging + ". Check if the API endpoint is correct.");
            } else {
                result.put("status", "DOWN");
                result.put("message", "HTTP error " + e.getStatusCode() + ": " + rawResponse + ". Request URL: " + urlForLogging);
            }
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("message", "Gemini API is unavailable: " + e.getClass().getSimpleName() + " - " + e.getMessage() + ". Request URL: " + urlForLogging);
            result.put("rawResponse", "{\"error\": \"" + e.getClass().getSimpleName() + "\", \"message\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}");
            result.put("httpStatus", "0");
            result.put("errorType", e.getClass().getSimpleName());
        }
        return result;
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
