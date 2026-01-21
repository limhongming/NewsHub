package com.newsinsight.controller;

import com.newsinsight.model.AnalysisRequest;
import com.newsinsight.model.AnalysisResponse;
import com.newsinsight.model.NewsItem;
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
