package com.newsinsight.service;

import com.newsinsight.model.MergedNewsCluster;
import com.newsinsight.model.NewsItem;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    private static final String TARGET_MODEL = "gemini-2.5-flash-lite";

    public NewsSchedulerService(NewsService newsService, NewsSystemService newsSystemService, NewsCacheService newsCacheService) {
        this.newsService = newsService;
        this.newsSystemService = newsSystemService;
        this.newsCacheService = newsCacheService;
    }

    /**
     * Periodically fetches BBC news, checks for new items, analyzes them,
     * and updates the cache without removing old summaries.
     * Runs every 15 minutes.
     */
    @Scheduled(fixedDelay = 900000) // 15 minutes
    public void updateBBCNewsBackground() {
        System.out.println("SCHEDULER: Starting background update for BBC News...");

        try {
            // 1. Fetch latest RSS Feed
            List<NewsItem> latestNews = newsService.getTopNews(); // BBC
            if (latestNews.isEmpty()) {
                System.out.println("SCHEDULER: No news found in RSS feed.");
                return;
            }

            // 2. Get existing cached summaries
            List<MergedNewsCluster> cachedClusters = newsCacheService.getCachedNews("bbc", TARGET_LANG, TARGET_MODEL);
            if (cachedClusters == null) {
                cachedClusters = new ArrayList<>();
            }

            // 3. Identify new items (not present in cache)
            Set<String> processedLinks = new HashSet<>();
            for (MergedNewsCluster cluster : cachedClusters) {
                if (cluster.related_links() != null) {
                    processedLinks.addAll(cluster.related_links());
                }
            }

            List<NewsItem> newItems = latestNews.stream()
                    .filter(item -> !processedLinks.contains(item.link()))
                    .collect(Collectors.toList());

            if (newItems.isEmpty()) {
                System.out.println("SCHEDULER: No new un-summarized articles found.");
                // We still might want to refresh the cache timestamp to prevent expiration?
                // The current logic only writes if there's a change. 
                // But if we don't write, the cache might expire after 6 hours.
                // To keep "old news" as requested, we should refresh the cache entry.
                if (!cachedClusters.isEmpty()) {
                    newsCacheService.cacheNews("bbc", TARGET_LANG, TARGET_MODEL, cachedClusters);
                    System.out.println("SCHEDULER: Refreshed cache timestamp for existing news.");
                }
                return;
            }

            System.out.println("SCHEDULER: Found " + newItems.size() + " new articles to analyze.");

            // 4. Analyze new items
            // processAndClusterNews handles clustering of the input list.
            List<MergedNewsCluster> newClusters = newsSystemService.processAndClusterNews(newItems, TARGET_LANG, false, TARGET_MODEL);

            if (newClusters != null && !newClusters.isEmpty()) {
                // Filter out errors if we want to be clean, or keep them to show issues
                // We will add new clusters to the TOP of the list (latest first)
                List<MergedNewsCluster> validNewClusters = newClusters.stream()
                        .filter(c -> !c.topic().contains("Error"))
                        .collect(Collectors.toList());

                if (!validNewClusters.isEmpty()) {
                    // Prepend new clusters to existing ones
                    List<MergedNewsCluster> mergedList = new ArrayList<>(validNewClusters);
                    mergedList.addAll(cachedClusters);

                    // 5. Save updated list to DB
                    newsCacheService.cacheNews("bbc", TARGET_LANG, TARGET_MODEL, mergedList);
                    System.out.println("SCHEDULER: Successfully added " + validNewClusters.size() + " new summaries. Total cached: " + mergedList.size());
                } else {
                    System.out.println("SCHEDULER: Analysis returned only errors or empty results.");
                }
            }

        } catch (Exception e) {
            System.err.println("SCHEDULER: Error during background update: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
