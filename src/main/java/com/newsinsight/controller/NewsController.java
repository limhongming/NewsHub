package com.newsinsight.controller;

import com.newsinsight.model.MergedNewsCluster;
import com.newsinsight.model.NewsItem;
import com.newsinsight.model.GeminiModel;
import com.newsinsight.model.SnippetRequest;
import com.newsinsight.model.AnalysisRequest;
import com.newsinsight.model.AnalysisResponse;
import com.newsinsight.model.ArticleCacheEntity;
import com.newsinsight.service.NewsSystemService;
import com.newsinsight.service.NewsService;
import com.newsinsight.service.NewsCacheService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class NewsController {

    private final NewsService newsService;
    private final NewsSystemService newsSystemService;
    private final NewsCacheService newsCacheService;

    public NewsController(NewsService newsService, NewsSystemService newsSystemService, NewsCacheService newsCacheService) {
        this.newsService = newsService;
        this.newsSystemService = newsSystemService;
        this.newsCacheService = newsCacheService;
    }

    @GetMapping
    public List<NewsItem> getNews(@RequestParam(defaultValue = "world") String category) {
        System.out.println("CONTROLLER: getNews called for category: " + category);
        return newsService.getNewsByCategory(category);
    }

    @GetMapping("/models")
    public List<GeminiModel> getModels() {
        return newsSystemService.listAvailableModels();
    }

    @PostMapping("/analyze/snippet")
    public MergedNewsCluster analyzeSnippet(@RequestBody SnippetRequest request) {
        // 1. Check Cache
        MergedNewsCluster cached = newsCacheService.getCachedSnippet(request.link(), request.lang(), request.model());
        if (cached != null) return cached;

        // 2. Analyze
        MergedNewsCluster result = newsSystemService.analyzeSnippet(request.title(), request.snippet(), request.lang(), request.model());

        // 3. Cache Success
        if (result != null && !result.topic().contains("Error")) {
            newsCacheService.cacheSnippet(request.link(), request.lang(), request.model(), result);
        }
        return result;
    }

    @GetMapping("/news/merged")
    public List<MergedNewsCluster> getMergedNews(@RequestParam(defaultValue = "English") String lang, @RequestParam(defaultValue = "gemini-1.5-flash") String model) {
        // Fix for deprecated/invalid model names: Map "2.5" requests to a stable model.
        if (model.contains("2.5") || model.contains("2.0")) {
            System.out.println("CONTROLLER: Model " + model + " requested, mapping to gemini-1.5-flash.");
            model = "gemini-1.5-flash";
        }
        
        List<MergedNewsCluster> cached = newsCacheService.getCachedNews("cnn", lang, model);
        if (cached != null) return cached;

        // Background scheduler handles updates. Return empty if nothing yet.
        return List.of();
    }

    @GetMapping("/news/bbc/merged")
    public List<MergedNewsCluster> getBBCMergedNews(@RequestParam(defaultValue = "English") String lang, 
                                                    @RequestParam(defaultValue = "gemini-1.5-flash") String model,
                                                    @RequestParam(defaultValue = "world") String category) {
        System.out.println("CONTROLLER: getBBCMergedNews request for model: " + model + ", category: " + category);
        
        // Fix for deprecated/invalid model names
        if (model.contains("2.5") || model.contains("2.0")) {
            model = "gemini-1.5-flash";
        }

        // Construct unique tab name for this category to isolate cache
        String tabName = "bbc-" + category.toLowerCase();

        // Return cached news immediately. 
        List<MergedNewsCluster> cached = newsCacheService.getCachedNews(tabName, lang, model);
        
        // Fallback: If "bbc-world" is empty (maybe migration phase), check the legacy "bbc" key
        if ((cached == null || cached.isEmpty()) && "world".equalsIgnoreCase(category)) {
             cached = newsCacheService.getCachedNews("bbc", lang, model);
        }
        
        if (cached != null) return cached;
        
        return List.of();
    }

    @GetMapping("/news/cnn")
    public List<NewsItem> getCNNNews() {
        return newsService.getCNNNews();
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeNews(@RequestBody AnalysisRequest request) {
        if (request.url() == null || request.url().isEmpty()) {
            return ResponseEntity.badRequest().body("URL is required");
        }

        // 1. Check cache first
        AnalysisResponse cachedResponse = newsCacheService.getCachedArticleAnalysis(request.url());
        if (cachedResponse != null) {
            System.out.println("INFO: Returning cached analysis for URL: " + request.url());
            return ResponseEntity.ok(cachedResponse);
        }

        try {
            // 2. Scrape
            System.out.println("INFO: Starting article analysis for URL: " + request.url());
            NewsItem scrapedItem;
            try {
                scrapedItem = newsService.scrapeNewsItem(request.url());
            } catch (Exception scrapeException) {
                System.err.println("ERROR: Failed to scrape article content: " + scrapeException.getMessage());
                return ResponseEntity.status(500).body("Scraping Failed: " + scrapeException.getMessage());
            }
            
            String content = scrapedItem.summary();
            if (content == null || content.isEmpty() || content.startsWith("Content could not")) {
                return ResponseEntity.badRequest().body("Could not scrape content form URL.");
            }

            System.out.println("INFO: Successfully scraped " + content.length() + " characters, now analyzing...");
            
            // 3. Analyze
            AnalysisResponse.AnalysisData analysisData;
            try {
                analysisData = newsSystemService.analyzeText(content);
            } catch (Exception analysisException) {
                System.err.println("ERROR: Failed to analyze article content: " + analysisException.getMessage());
                AnalysisResponse errorResponse = new AnalysisResponse(
                    new AnalysisResponse.AnalysisData("Analysis Failed: " + analysisException.getMessage()),
                    content
                );
                errorResponse.setDebugInfo("Scraping Source: " + request.url());
                return ResponseEntity.ok(errorResponse);
            }
            
            // 4. Cache the successful analysis
            AnalysisResponse successResponse = new AnalysisResponse(analysisData, content);
            successResponse.setImageUrl(scrapedItem.imageUrl());
            successResponse.setDebugInfo("Scraped from: " + request.url() + " | Title: " + scrapedItem.title());
            
            try {
                newsCacheService.cacheArticleAnalysis(request.url(), successResponse);
                System.out.println("INFO: Cached analysis for URL: " + request.url());
            } catch (Exception cacheException) {
                System.err.println("WARN: Failed to cache analysis for URL " + request.url() + ": " + cacheException.getMessage());
            }
            
            // 5. Return Combined Response
            System.out.println("INFO: Analysis complete for URL: " + request.url());
            return ResponseEntity.ok(successResponse);

        } catch (Exception e) {
            System.err.println("ERROR: Unexpected error in analyzeNews: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Unexpected Error: " + e.getMessage());
        }
    }

    @GetMapping("/debug/clusters")
    public List<com.newsinsight.model.NewsCacheClusterEntity> getAllCachedClusters() {
        return newsCacheService.getAllCachedClusters();
    }

    @GetMapping("/debug/articles")
    public ResponseEntity<List<ArticleCacheEntity>> getCachedArticles() {
        return ResponseEntity.ok(newsCacheService.getAllCachedArticles());
    }

    @GetMapping("/debug/backup")
    public ResponseEntity<Map<String, Object>> downloadBackup() {
        Map<String, Object> backup = new java.util.HashMap<>();
        backup.put("export_timestamp", java.time.LocalDateTime.now().toString());
        
        // Process Clusters: Parse dataJson string into real JSON for the backup file
        List<Map<String, Object>> clusterExport = newsCacheService.getAllCachedClusters().stream().map(entity -> {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", entity.getId());
            map.put("cache_key", entity.getCacheKey());
            map.put("tab", entity.getTab());
            map.put("language", entity.getLanguage());
            map.put("model", entity.getModel());
            map.put("created_at", entity.getCreatedAt());
            map.put("expires_at", entity.getExpiresAt());
            try {
                // Convert the stored JSON string back into a real object/list for the export
                map.put("data_json", new com.fasterxml.jackson.databind.ObjectMapper().readTree(entity.getDataJson()));
            } catch (Exception e) {
                map.put("data_json", entity.getDataJson());
            }
            return map;
        }).collect(java.util.stream.Collectors.toList());
        
        // Process Articles
        List<Map<String, Object>> articleExport = newsCacheService.getAllCachedArticles().stream().map(entity -> {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", entity.getId());
            map.put("url", entity.getUrl());
            map.put("url_hash", entity.getUrlHash());
            map.put("cache_type", entity.getCacheType());
            try {
                map.put("data_json", new com.fasterxml.jackson.databind.ObjectMapper().readTree(entity.getDataJson()));
            } catch (Exception e) {
                map.put("data_json", entity.getDataJson());
            }
            return map;
        }).collect(java.util.stream.Collectors.toList());

        backup.put("news_cache_clusters", clusterExport);
        backup.put("article_cache", articleExport);
        
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=newshub_full_backup_" + java.time.LocalDate.now() + ".json")
            .body(backup);
    }

    @PostMapping("/news/import")
    public ResponseEntity<?> manualImport(@RequestBody Map<String, List<String>> payload) {
        List<String> urls = payload.get("urls");
        if (urls == null || urls.isEmpty()) {
            return ResponseEntity.badRequest().body("No URLs provided");
        }

        System.out.println("IMPORT: Received " + urls.size() + " URLs for manual import.");
        
        try {
            // 1. Scrape Items
            List<NewsItem> items = new java.util.ArrayList<>();
            for (String url : urls) {
                items.add(newsService.scrapeNewsItem(url));
            }

            // 2. Analyze (using existing logic)
            // This will process them (sequentially or batch depending on logic) and return clusters
            List<MergedNewsCluster> newClusters = newsSystemService.processAndClusterNews(
                items, "English", false, "gemini-1.5-flash"
            );
            
            // 3. Update Cache
            // We append these to the BBC cache for now, or we could have a "Manual" tab. 
            // User requested "Manual Import" to backfill, likely for BBC/General.
            // Let's add to BBC cache as that's the primary view.
            List<MergedNewsCluster> currentCache = newsCacheService.getCachedNews("bbc", "English", "gemini-1.5-flash");
            if (currentCache == null) currentCache = new java.util.ArrayList<>();
            
            List<MergedNewsCluster> validNew = new java.util.ArrayList<>();
            if (newClusters != null) {
                // Create a map of URL to Content for easy lookup
                Map<String, String> urlContentMap = new java.util.HashMap<>();
                for (NewsItem item : items) {
                    urlContentMap.put(item.link(), item.summary()); // summary holds full text here
                }

                for (MergedNewsCluster c : newClusters) {
                    if (!c.topic().contains("Error")) {
                        validNew.add(c);
                        
                        // ALSO cache as "Full Article" so "Read Full Article" works instantly
                        if (c.related_links() != null && !c.related_links().isEmpty()) {
                            String link = c.related_links().get(0);
                            String content = urlContentMap.get(link);
                            
                            if (content != null) {
                                AnalysisResponse.AnalysisData data = new AnalysisResponse.AnalysisData();
                                data.setSummary(c.summary());
                                data.setEconomic_impact(c.economic_impact());
                                data.setGlobal_impact(c.global_impact());
                                try {
                                    data.setImpact_rating(Integer.parseInt(c.impact_rating()));
                                } catch (Exception e) { data.setImpact_rating(0); }
                                data.setUrgency("Imported"); // Default for manual import

                                AnalysisResponse response = new AnalysisResponse(data, content);
                                newsCacheService.cacheArticleAnalysis(link, response);
                            }
                        }
                    }
                }
            }
            
            if (!validNew.isEmpty()) {
                // Add to top
                List<MergedNewsCluster> merged = new java.util.ArrayList<>(validNew);
                merged.addAll(currentCache);
                newsCacheService.cacheNews("bbc", "English", "gemini-1.5-flash", merged);
                return ResponseEntity.ok("Successfully imported " + validNew.size() + " articles.");
            } else {
                return ResponseEntity.ok("No valid articles could be imported (Analysis failed or empty).");
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Import Failed: " + e.getMessage());
        }
    }

    @GetMapping("/usage")
    public Map<String, Object> getApiUsageStats() {
        return newsSystemService.getApiUsageStats();
    }

    @GetMapping("/health")
    public Map<String, String> getGeminiHealth() {
        return newsSystemService.checkGeminiHealth();
    }
}