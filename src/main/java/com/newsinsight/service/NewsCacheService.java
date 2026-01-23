package com.newsinsight.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newsinsight.model.MergedNewsCluster;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
public class NewsCacheService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String CACHE_DIR = "cache";
    private static final long CACHE_DURATION_MS = 6 * 60 * 60 * 1000; // 6 hours

    public NewsCacheService() {
        try {
            Files.createDirectories(Paths.get(CACHE_DIR));
        } catch (IOException e) {
            System.err.println("Could not create cache directory: " + e.getMessage());
        }
    }

    public List<MergedNewsCluster> getCachedNews(String tab, String lang, String model) {
        String fileName = getCacheFileName(tab, lang, model);
        File cacheFile = new File(CACHE_DIR, fileName);

        if (cacheFile.exists()) {
            long lastModified = cacheFile.lastModified();
            if (System.currentTimeMillis() - lastModified < CACHE_DURATION_MS) {
                try {
                    System.out.println("DEBUG: Loading from cache: " + fileName);
                    return objectMapper.readValue(cacheFile, new TypeReference<List<MergedNewsCluster>>() {});
                } catch (IOException e) {
                    System.err.println("Error reading cache file: " + e.getMessage());
                }
            } else {
                System.out.println("DEBUG: Cache expired: " + fileName);
            }
        }
        return null;
    }

    public void cacheNews(String tab, String lang, String model, List<MergedNewsCluster> clusters) {
        String fileName = getCacheFileName(tab, lang, model);
        File cacheFile = new File(CACHE_DIR, fileName);
        try {
            System.out.println("DEBUG: Saving to cache: " + fileName);
            objectMapper.writeValue(cacheFile, clusters);
        } catch (IOException e) {
            System.err.println("Error writing cache file: " + e.getMessage());
        }
    }

    private String getCacheFileName(String tab, String lang, String model) {
        return String.format("%s_%s_%s.json", 
            tab.replaceAll("[^a-zA-Z0-9]", ""), 
            lang.replaceAll("[^a-zA-Z0-9]", ""), 
            model.replaceAll("[^a-zA-Z0-9]", ""));
    }

    public MergedNewsCluster getCachedSnippet(String link, String lang, String model) {
        String hash = String.valueOf(link.hashCode()).replace("-", "n");
        String fileName = String.format("snippet_%s_%s_%s.json", hash, lang, model);
        File cacheFile = new File(CACHE_DIR, fileName);

        if (cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified() < CACHE_DURATION_MS)) {
            try {
                return objectMapper.readValue(cacheFile, MergedNewsCluster.class);
            } catch (IOException e) { return null; }
        }
        return null;
    }

    public void cacheSnippet(String link, String lang, String model, MergedNewsCluster cluster) {
        String hash = String.valueOf(link.hashCode()).replace("-", "n");
        String fileName = String.format("snippet_%s_%s_%s.json", hash, lang, model);
        File cacheFile = new File(CACHE_DIR, fileName);
        try {
            objectMapper.writeValue(cacheFile, cluster);
        } catch (IOException e) { e.printStackTrace(); }
    }
}
