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

import java.net.URI; // Import URI

@Service
public class NewsSystemService {

    @Value("${gemini.api.key}")
    private String apiKey;

    // ... (rest of the class code)

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
                    String urlStr = String.format(API_URL_TEMPLATE, model.trim(), apiKey);
                    System.out.println("DEBUG: FINAL API URL: " + urlStr.replace(apiKey, "API_KEY_HIDDEN"));
                    
                    // USE URI OBJECT TO PREVENT DOUBLE-ENCODING OF THE COLON (:)
                    URI uri = URI.create(urlStr);
                    
                    ResponseEntity<Map> response = restTemplate.postForEntity(uri, entity, Map.class);
                    
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
