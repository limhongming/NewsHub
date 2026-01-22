package com.newsinsight.controller;

import com.newsinsight.model.AnalysisRequest;
import com.newsinsight.model.AnalysisResponse;
import com.newsinsight.model.MergedNewsCluster;
import com.newsinsight.model.NewsItem;
import com.newsinsight.model.GeminiModel;
import com.newsinsight.service.NewsSystemService;
import com.newsinsight.service.NewsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class NewsController {

    private final NewsService newsService;
    private final NewsSystemService newsSystemService;

    public NewsController(NewsService newsService, NewsSystemService newsSystemService) {
        this.newsService = newsService;
        this.newsSystemService = newsSystemService;
    }

    @GetMapping("/news")
    public List<NewsItem> getNews() {
        return newsService.getTopNews();
    }

    @GetMapping("/models")
    public List<GeminiModel> getModels() {
        return newsSystemService.listAvailableModels();
    }

    @GetMapping("/news/merged")
    public List<MergedNewsCluster> getMergedNews(@RequestParam(defaultValue = "English") String lang, @RequestParam(defaultValue = "gemini-2.5-flash") String model) {
        List<NewsItem> cnnNews = newsService.getCNNNews();
        return newsSystemService.processAndClusterNews(cnnNews, lang, true, model);
    }

    @GetMapping("/news/bbc/merged")
    public List<MergedNewsCluster> getBBCMergedNews(@RequestParam(defaultValue = "English") String lang, @RequestParam(defaultValue = "gemini-2.5-flash") String model) {
        List<NewsItem> bbcNews = newsService.getTopNews();
        return newsSystemService.processAndClusterNews(bbcNews, lang, false, model);
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
            String content = newsService.scrapeArticleContent(request.url());
            
            if (content.isEmpty()) {
                return ResponseEntity.badRequest().body("Could not scrape content from URL.");
            }

            // 2. Analyze
            AnalysisResponse.AnalysisData analysisData = newsSystemService.analyzeText(content);
            
            // 3. Return Combined Response
            return ResponseEntity.ok(new AnalysisResponse(analysisData, content));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}
