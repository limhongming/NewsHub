package com.newsinsight.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newsinsight.model.AnalysisResponse;
import com.newsinsight.model.MergedNewsCluster;
import com.newsinsight.model.NewsCacheClusterEntity;
import com.newsinsight.model.ArticleCacheEntity;
import com.newsinsight.repository.NewsCacheClusterRepository;
import com.newsinsight.repository.ArticleCacheRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class NewsCacheService {

    private final ObjectMapper objectMapper;
    private final NewsCacheClusterRepository clusterRepository;
    private final ArticleCacheRepository articleRepository;
    
    @Value("${app.cache.duration:2592000000}") // 30 days default
    private long cacheDurationMs;

    public NewsCacheService(ObjectMapper objectMapper, 
                           NewsCacheClusterRepository clusterRepository,
                           ArticleCacheRepository articleRepository) {
        this.objectMapper = objectMapper;
        this.clusterRepository = clusterRepository;
        this.articleRepository = articleRepository;
    }

    public List<MergedNewsCluster> getCachedNews(String tab, String lang, String model) {
        // If specific model is requested, try to use the "All Models" merge strategy to find data
        // This ensures data saved under 'gemini-1.5' is visible even if 'deepseek' is selected.
        return getAllCachedNewsForTab(tab, lang);
    }

    public List<MergedNewsCluster> getAllCachedNewsForTab(String tab, String lang) {
        List<NewsCacheClusterEntity> entities = clusterRepository.findByTabAndLanguageAndNotExpired(tab, lang, LocalDateTime.now());
        if (entities.isEmpty()) return null;

        java.util.Set<String> seenTopics = new java.util.HashSet<>();
        List<MergedNewsCluster> merged = new java.util.ArrayList<>();

        // Sort entities by newest first to prioritize recent caches
        entities.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        for (NewsCacheClusterEntity entity : entities) {
            try {
                List<MergedNewsCluster> clusters = objectMapper.readValue(entity.getDataJson(), 
                        new TypeReference<List<MergedNewsCluster>>() {});
                
                if (clusters != null) {
                    for (MergedNewsCluster c : clusters) {
                        // Deduplicate based on topic or link
                        String key = c.topic() + (c.related_links() != null && !c.related_links().isEmpty() ? c.related_links().get(0) : "");
                        if (!seenTopics.contains(key)) {
                            seenTopics.add(key);
                            merged.add(c);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Error parsing cache JSON for ID " + entity.getId() + ": " + e.getMessage());
            }
        }
        return merged.isEmpty() ? null : merged;
    }

    @Transactional
    public void cacheNews(String tab, String lang, String model, List<MergedNewsCluster> clusters) {
        String cacheKey = generateCacheKey(tab, lang, model);
        
        try {
            String dataJson = objectMapper.writeValueAsString(clusters);
            
            // Delete existing cache entry if exists
            clusterRepository.deleteByCacheKey(cacheKey);
            
            // Create new cache entry
            NewsCacheClusterEntity entity = new NewsCacheClusterEntity(
                    cacheKey, tab, lang, model, dataJson, cacheDurationMs);
            
            clusterRepository.save(entity);
            System.out.println("DEBUG: Saving to MySQL cache: " + cacheKey);
        } catch (IOException e) {
            System.err.println("Error serializing cache data: " + e.getMessage());
        }
    }

    public MergedNewsCluster getCachedSnippet(String link, String lang, String model) {
        String urlHash = generateUrlHash(link);
        
        return articleRepository.findByUrlHashLanguageModelTypeAndNotExpired(
                    urlHash, lang, model, "snippet", LocalDateTime.now())
                .map(entity -> {
                    try {
                        return objectMapper.readValue(entity.getDataJson(), MergedNewsCluster.class);
                    } catch (IOException e) {
                        System.err.println("Error parsing snippet cache JSON: " + e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    @Transactional
    public void cacheSnippet(String link, String lang, String model, MergedNewsCluster cluster) {
        String urlHash = generateUrlHash(link);
        
        try {
            String dataJson = objectMapper.writeValueAsString(cluster);
            
            // Delete existing cache entry if exists
            articleRepository.deleteByUrlHash(urlHash);
            
            // Create new cache entry
            ArticleCacheEntity entity = new ArticleCacheEntity(
                    urlHash, link, lang, model, "snippet", dataJson, cacheDurationMs);
            
            articleRepository.save(entity);
        } catch (IOException e) {
            System.err.println("Error serializing snippet cache data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public AnalysisResponse getCachedArticleAnalysis(String url) {
        String urlHash = generateUrlHash(url);
        
        return articleRepository.findByUrlHashLanguageModelTypeAndNotExpired(
                    urlHash, "English", "default", "full_article", LocalDateTime.now())
                .map(entity -> {
                    try {
                        System.out.println("DEBUG: Loading article analysis from MySQL cache: " + urlHash);
                        return objectMapper.readValue(entity.getDataJson(), AnalysisResponse.class);
                    } catch (IOException e) {
                        System.err.println("Error reading article cache from DB: " + e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    @Transactional
    public void cacheArticleAnalysis(String url, AnalysisResponse response) {
        String urlHash = generateUrlHash(url);
        
        try {
            String dataJson = objectMapper.writeValueAsString(response);
            
            // Delete existing cache entry if exists
            articleRepository.deleteByUrlHash(urlHash);
            
            // Create new cache entry
            ArticleCacheEntity entity = new ArticleCacheEntity(
                    urlHash, url, "English", "default", "full_article", dataJson, cacheDurationMs);
            
            articleRepository.save(entity);
            System.out.println("DEBUG: Saving article analysis to MySQL cache: " + urlHash);
        } catch (IOException e) {
            System.err.println("Error writing article cache to DB: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public List<ArticleCacheEntity> getAllCachedArticles() {
        return articleRepository.findAll();
    }

    public List<NewsCacheClusterEntity> getAllCachedClusters() {
        return clusterRepository.findAll();
    }

    private String generateCacheKey(String tab, String lang, String model) {
        return String.format("%s_%s_%s", 
            tab.replaceAll("[^a-zA-Z0-9]", ""), 
            lang.replaceAll("[^a-zA-Z0-9]", ""), 
            model.replaceAll("[^a-zA-Z0-9]", ""));
    }

    private String generateUrlHash(String url) {
        return String.valueOf(url.hashCode()).replace("-", "n");
    }

    @Scheduled(fixedDelay = 3600000) // Run every hour
    @Transactional
    public void cleanupExpiredCache() {
        LocalDateTime now = LocalDateTime.now();
        int clusterDeleted = clusterRepository.deleteExpired(now);
        int articleDeleted = articleRepository.deleteExpired(now);
        
        if (clusterDeleted > 0 || articleDeleted > 0) {
            System.out.println("DEBUG: Cleaned up expired cache entries. " +
                              "Clusters: " + clusterDeleted + ", Articles: " + articleDeleted);
        }
    }

    @Transactional
    public void clearAllCaches() {
        System.out.println("WARN: Clearing ALL database caches manually.");
        clusterRepository.deleteAll();
        articleRepository.deleteAll();
    }
}