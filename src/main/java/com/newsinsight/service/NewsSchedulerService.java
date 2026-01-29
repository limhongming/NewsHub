package com.newsinsight.service;

import com.newsinsight.model.MergedNewsCluster;
import com.newsinsight.model.NewsItem;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NewsSchedulerService {

    private final NewsService newsService;
    private final NewsSystemService newsSystemService;
    private final NewsCacheService newsCacheService;

    // We focus on the user's preferred configuration
    private static final String TARGET_LANG = "English";
    private static final String TARGET_MODEL = "deepseek-chat"; 

    // Category Management
    private final List<String> categories = new ArrayList<>();
    private int currentCategoryIndex = 0;

    // Local cache for RSS feeds per category
    private final Map<String, List<NewsItem>> categoryRawCache = new ConcurrentHashMap<>();
    private final Map<String, Long> categoryLastFetch = new ConcurrentHashMap<>();
    
    private static final long RSS_FETCH_INTERVAL = 300000; // 5 minutes

    public NewsSchedulerService(NewsService newsService, NewsSystemService newsSystemService, NewsCacheService newsCacheService) {
        this.newsService = newsService;
        this.newsSystemService = newsSystemService;
        this.newsCacheService = newsCacheService;
        
        // Initialize categories
        this.categories.addAll(newsService.getCategoryNames());
        // Ensure 'world' is first or present
        if (!this.categories.contains("world")) {
            this.categories.add(0, "world");
        }
        Collections.sort(this.categories); // consistent order
    }

    /**
     * "Real-Time" Processor.
     * Runs every 4.5 seconds to strictly adhere to rate limits.
     * Cycles through categories (World -> Sport -> Business -> ...) processing one item at a time.
     */
    @Scheduled(fixedDelay = 4500) 
    public void processNextArticle() {
        if (categories.isEmpty()) return;

        try {
            // 1. Pick current category
            String currentCategory = categories.get(currentCategoryIndex);
            
            // 2. Refresh feed for this category if needed
            refreshFeedIfNeeded(currentCategory);
            
            // 3. Process one item for this category
            // Tab name format: "bbc-world", "bbc-sport", etc.
            String tabName = "bbc-" + currentCategory.toLowerCase();
            processOneItem(tabName, categoryRawCache.getOrDefault(currentCategory, new ArrayList<>()));

            // 4. Move to next category for next run
            currentCategoryIndex = (currentCategoryIndex + 1) % categories.size();

        } catch (Exception e) {
            System.err.println("SCHEDULER: Error in process cycle: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void refreshFeedIfNeeded(String category) {
        long now = System.currentTimeMillis();
        long lastFetch = categoryLastFetch.getOrDefault(category, 0L);
        List<NewsItem> cached = categoryRawCache.get(category);
        
        if (now - lastFetch > RSS_FETCH_INTERVAL || cached == null || cached.isEmpty()) {
            System.out.println("SCHEDULER: Refreshing RSS feed for category: " + category);
            try {
                List<NewsItem> items = newsService.getNewsByCategory(category);
                if (!items.isEmpty()) {
                    categoryRawCache.put(category, items);
                    categoryLastFetch.put(category, now);
                }
            } catch (Exception e) {
                System.err.println("SCHEDULER: Failed to fetch RSS for " + category + ": " + e.getMessage());
            }
        }
    }

    private boolean processOneItem(String tabName, List<NewsItem> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) return false;

        // Get current cached results for this specific category tab
        List<MergedNewsCluster> currentCache = newsCacheService.getCachedNews(tabName, TARGET_LANG, TARGET_MODEL);
        if (currentCache == null) currentCache = new ArrayList<>();

        // Build set of processed links to find what's new
        Set<String> processedLinks = new HashSet<>();
        for (MergedNewsCluster cluster : currentCache) {
            if (cluster.related_links() != null) {
                for (String link : cluster.related_links()) {
                    processedLinks.add(normalizeLink(link));
                }
            }
        }

        // Find the FIRST item in rawItems that is NOT in processedLinks
        NewsItem candidate = null;
        for (NewsItem item : rawItems) {
            if (!processedLinks.contains(normalizeLink(item.link())) 
                && !item.title().startsWith("System Error") 
                && !item.title().startsWith("No News Found")) {
                candidate = item;
                break; // Found one!
            }
        }

        if (candidate == null) return false; // Nothing new to process

        // Analyze THIS candidate
        System.out.println("SCHEDULER: Analyzing new item for " + tabName + ": " + candidate.title());
        
        // For BBC, use snippet analysis
        MergedNewsCluster newResult = newsSystemService.analyzeSnippet(
            candidate.title(), 
            candidate.summary(), 
            TARGET_LANG, 
            TARGET_MODEL
        );
        
        // Manually set related links as analyzeSnippet might not set them exactly as we want for tracking
        if (newResult != null) {
            newResult = new MergedNewsCluster(
                newResult.topic(), newResult.summary(), newResult.economic_impact(),
                newResult.global_impact(), newResult.impact_rating(), newResult.what_next(),
                Collections.singletonList(candidate.link()), newResult.modelUsed(),
                candidate.imageUrl(),
                candidate.published() != null ? candidate.published().toString() : null
            );
        }

        if (newResult != null && !newResult.topic().contains("Error")) {
            // Add to the TOP of the list
            List<MergedNewsCluster> newList = new ArrayList<>();
            newList.add(newResult);
            if (currentCache != null) {
                newList.addAll(currentCache);
            }
            
            // Limit list size to prevent infinite growth (keep last 100 per category)
            if (newList.size() > 100) {
                newList = newList.subList(0, 100);
            }

            System.out.println("SCHEDULER: Analysis success for: " + candidate.title() + ". Saving to DB (" + tabName + ")...");
            try {
                newsCacheService.cacheNews(tabName, TARGET_LANG, TARGET_MODEL, newList);
            } catch (Exception e) {
                System.err.println("SCHEDULER: DB Save FAILED: " + e.getMessage());
                e.printStackTrace();
            }
            return true; // We did work
        } else {
            String error = (newResult != null) ? newResult.summary() : "Result was null";
            System.err.println("SCHEDULER: Failed to analyze item: " + candidate.title() + " -> " + error);
            return true; // We consumed a slot (even if failed)
        }
    }
    
    private String normalizeLink(String url) {
        if (url == null) return "";
        return url.split("\\?")[0].toLowerCase().trim();
    }
}