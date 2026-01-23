package com.newsinsight.controller;

import com.newsinsight.model.MergedNewsCluster;
import com.newsinsight.model.NewsItem;
import com.newsinsight.model.GeminiModel;
import com.newsinsight.model.SnippetRequest;
import com.newsinsight.model.AnalysisRequest;
import com.newsinsight.model.AnalysisResponse;
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

    @GetMapping("/news")
    public List<NewsItem> getNews() {
        return newsService.getTopNews();
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
    public List<MergedNewsCluster> getMergedNews(@RequestParam(defaultValue = "English") String lang, @RequestParam(defaultValue = "gemini-2.5-flash") String model) {
        List<MergedNewsCluster> cached = newsCacheService.getCachedNews("cnn", lang, model);
        if (cached != null) return cached;

        List<NewsItem> cnnNews = newsService.getCNNNews();
        List<MergedNewsCluster> result = newsSystemService.processAndClusterNews(cnnNews, lang, true, model);
        
        if (result != null && !result.isEmpty() && !result.get(0).topic().contains("Error")) {
            newsCacheService.cacheNews("cnn", lang, model, result);
        }
        return result;
    }

    @GetMapping("/news/bbc/merged")
    public List<MergedNewsCluster> getBBCMergedNews(@RequestParam(defaultValue = "English") String lang, @RequestParam(defaultValue = "gemini-2.5-flash") String model) {
        List<MergedNewsCluster> cached = newsCacheService.getCachedNews("bbc", lang, model);
        if (cached != null) return cached;

        List<NewsItem> bbcNews = newsService.getTopNews();
        List<MergedNewsCluster> result = newsSystemService.processAndClusterNews(bbcNews, lang, false, model);

        if (result != null && !result.isEmpty() && !result.get(0).topic().contains("Error")) {
            newsCacheService.cacheNews("bbc", lang, model, result);
        }
        return result;
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

        try {
            // 1. Scrape
            System.out.println("INFO: Starting article analysis for URL: " + request.url());
            String content;
            try {
                content = newsService.scrapeArticleContent(request.url());
            } catch (Exception scrapeException) {
                System.err.println("ERROR: Failed to scrape article content: " + scrapeException.getMessage());
                // Differentiate between scraping failures and other errors
                String errorMsg = scrapeException.getMessage();
                if (errorMsg.contains("blocking access") || errorMsg.contains("temporarily unavailable")) {
                    return ResponseEntity.status(503).body("Service Unavailable: " + errorMsg);
                } else if (errorMsg.contains("content not found") || errorMsg.contains("too short")) {
                    return ResponseEntity.status(404).body("Not Found: " + errorMsg);
                } else {
                    return ResponseEntity.status(500).body("Scraping Failed: " + errorMsg);
                }
            }
            
            if (content == null || content.isEmpty()) {
                return ResponseEntity.badRequest().body("Could not scrape content from URL.");
            }

            System.out.println("INFO: Successfully scraped " + content.length() + " characters, now analyzing...");
            
            // 2. Analyze
            AnalysisResponse.AnalysisData analysisData;
            try {
                analysisData = newsSystemService.analyzeText(content);
            } catch (Exception analysisException) {
                System.err.println("ERROR: Failed to analyze article content: " + analysisException.getMessage());
                // If analysis fails, still return the scraped content with a note
                return ResponseEntity.ok(new AnalysisResponse(
                    new AnalysisResponse.AnalysisData("Analysis Failed: " + analysisException.getMessage()),
                    content
                ));
            }
            
            // 3. Return Combined Response
            System.out.println("INFO: Analysis complete for URL: " + request.url());
            return ResponseEntity.ok(new AnalysisResponse(analysisData, content));

        } catch (Exception e) {
            System.err.println("ERROR: Unexpected error in analyzeNews: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Unexpected Error: " + e.getMessage());
        }
    }

    @GetMapping("/api/usage")
    public Map<String, Object> getApiUsageStats() {
        return newsSystemService.getApiUsageStats();
    }

    @GetMapping("/health")
    public Map<String, String> getGeminiHealth() {
        return newsSystemService.checkGeminiHealth();
    }
}
