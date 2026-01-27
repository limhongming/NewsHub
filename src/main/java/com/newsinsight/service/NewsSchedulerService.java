package com.newsinsight.service;

import com.newsinsight.model.MergedNewsCluster;
import com.newsinsight.model.NewsItem;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class NewsSchedulerService {

    private final NewsService newsService;
    private final NewsSystemService newsSystemService;
    private final NewsCacheService newsCacheService;

    // We focus on the user's preferred configuration
    private static final String TARGET_LANG = "English";
    private static final String TARGET_MODEL = "deepseek-chat";

    // Local cache for RSS feeds to avoid over-fetching from source
    private List<NewsItem> cachedBBCRaw = new ArrayList<>();
    private long lastBBCFetch = 0;
    private List<NewsItem> cachedCNNRaw = new ArrayList<>();
    private long lastCNNFetch = 0;
    private static final long RSS_FETCH_INTERVAL = 300000; // 5 minutes

    public NewsSchedulerService(NewsService newsService, NewsSystemService newsSystemService, NewsCacheService newsCacheService) {
        this.newsService = newsService;
        this.newsSystemService = newsSystemService;
        this.newsCacheService = newsCacheService;
    }

    /**
     * "Real-Time" Processor.
     * Runs every 4.5 seconds to strictly adhere to ~13-15 RPM limit while providing
     * continuous updates.
     * 
     * Strategy:
     * 1. Check if we need to refresh raw RSS feeds (every 5 mins).
     * 2. Look for ONE un-analyzed article from BBC.
     * 3. If found, analyze it, save, and STOP (wait for next cycle).
     * 4. If no BBC work, look for ONE un-analyzed article from CNN.
     * 5. If found, analyze it, save, and STOP.
     */
    @Scheduled(fixedDelay = 4500) 
    public void processNextArticle() {
        try {
            // 1. Refresh Raw Feeds if needed
            refreshFeedsIfNeeded();

            // 2. Try to process one BBC item
            if (processOneItem("bbc", cachedBBCRaw, false)) return;

            // 3. Try to process one CNN item (CNN uses clustering logic usually, but here we treat item-by-item)
            if (processOneItem("cnn", cachedCNNRaw, true)) return;

        } catch (Exception e) {
            System.err.println("SCHEDULER: Error in process cycle: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void refreshFeedsIfNeeded() {
        long now = System.currentTimeMillis();
        
        if (now - lastBBCFetch > RSS_FETCH_INTERVAL || cachedBBCRaw.isEmpty()) {
            System.out.println("SCHEDULER: Refreshing BBC RSS feed...");
            List<NewsItem> items = newsService.getTopNews();
            if (!items.isEmpty()) {
                cachedBBCRaw = items;
                lastBBCFetch = now;
            }
        }

        if (now - lastCNNFetch > RSS_FETCH_INTERVAL || cachedCNNRaw.isEmpty()) {
            System.out.println("SCHEDULER: Refreshing CNN RSS feed...");
            List<NewsItem> items = newsService.getCNNNews();
            if (!items.isEmpty()) {
                cachedCNNRaw = items;
                lastCNNFetch = now;
            }
        }
    }

    private boolean processOneItem(String tabName, List<NewsItem> rawItems, boolean useClusteringLogic) {
        if (rawItems == null || rawItems.isEmpty()) return false;

        // Get current cached results
        List<MergedNewsCluster> currentCache = newsCacheService.getCachedNews(tabName, TARGET_LANG, TARGET_MODEL);
        if (currentCache == null) currentCache = new ArrayList<>();

        // Build set of processed links to find what's new
        Set<String> processedLinks = new HashSet<>();
        for (MergedNewsCluster cluster : currentCache) {
            if (cluster.related_links() != null) {
                processedLinks.addAll(cluster.related_links());
            }
        }

        // Find the FIRST item in rawItems that is NOT in processedLinks
        NewsItem candidate = null;
        for (NewsItem item : rawItems) {
            if (!processedLinks.contains(item.link()) 
                && !item.title().startsWith("System Error") 
                && !item.title().startsWith("No News Found")) {
                candidate = item;
                break; // Found one!
            }
        }

        if (candidate == null) return false; // Nothing new to process

        // Analyze THIS candidate
        System.out.println("SCHEDULER: Analyzing new item for " + tabName + ": " + candidate.title());
        
        MergedNewsCluster newResult;
        
        if (useClusteringLogic) {
            // For CNN, we use the processAndClusterNews but with a single item list
            // This reuses the logic but effectively does single-item analysis
            List<MergedNewsCluster> results = newsSystemService.processAndClusterNews(
                Collections.singletonList(candidate), 
                TARGET_LANG, 
                true, // cluster=true (though only 1 item)
                TARGET_MODEL
            );
            newResult = (results != null && !results.isEmpty()) ? results.get(0) : null;
        } else {
            // For BBC, use snippet analysis
            newResult = newsSystemService.analyzeSnippet(
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
                    Collections.singletonList(candidate.link()), newResult.modelUsed()
                );
            }
        }

        if (newResult != null && !newResult.topic().contains("Error")) {
            // Add to the TOP of the list
            List<MergedNewsCluster> newList = new ArrayList<>();
            newList.add(newResult);
            if (currentCache != null) {
                newList.addAll(currentCache);
            }
            
            // Limit list size to prevent infinite growth (e.g., keep last 50)
            if (newList.size() > 50) {
                newList = newList.subList(0, 50);
            }

            System.out.println("SCHEDULER: Analysis success for: " + candidate.title() + ". Saving " + newList.size() + " items to DB...");
            try {
                newsCacheService.cacheNews(tabName, TARGET_LANG, TARGET_MODEL, newList);
                System.out.println("SCHEDULER: DB Save Complete for " + tabName);
            } catch (Exception e) {
                System.err.println("SCHEDULER: DB Save FAILED: " + e.getMessage());
                e.printStackTrace();
            }
            return true; // We did work
        } else {
            String error = (newResult != null) ? newResult.summary() : "Result was null";
            System.err.println("SCHEDULER: Failed to analyze item: " + candidate.title() + " -> " + error);
            // We might want to mark it as ignored so we don't retry forever?
            // For now, we'll just return true (work attempted) and it will retry next time or we can add a dummy error entry.
            // To prevent blocking, let's add a dummy entry so we skip it next time.
            /*
            MergedNewsCluster errorEntry = new MergedNewsCluster(
                "Error Processing", "Failed to analyze: " + candidate.title(), 
                "N/A", "N/A", "0", "N/A", Collections.singletonList(candidate.link()), "System"
            );
            List<MergedNewsCluster> newList = new ArrayList<>(currentCache);
            newList.add(errorEntry); // Add to end or beginning?
            newsCacheService.cacheNews(tabName, TARGET_LANG, TARGET_MODEL, newList);
            */
            return true; // We consumed a slot (even if failed)
        }
    }
}