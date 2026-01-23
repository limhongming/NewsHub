package com.newsinsight.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "article_cache", indexes = {
    @Index(name = "idx_url_hash", columnList = "urlHash", unique = true),
    @Index(name = "idx_expires_at_article", columnList = "expiresAt"),
    @Index(name = "idx_cache_type", columnList = "cacheType")
})
public class ArticleCacheEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private String urlHash;   // Hashed URL for uniqueness
    
    @Column(columnDefinition = "TEXT", nullable = false)
    private String url;       // Original URL
    
    @Column(nullable = false)
    private String language;  // e.g., "English", "Chinese"
    
    @Column(nullable = false)
    private String model;     // e.g., "gemini-2.5-flash"
    
    @Column(nullable = false)
    private String cacheType; // "snippet" or "full_article"
    
    @Column(columnDefinition = "JSON", nullable = false)
    private String dataJson;  // JSON representation of MergedNewsCluster or AnalysisResponse
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false)
    private LocalDateTime expiresAt;
    
    public ArticleCacheEntity() {
        this.createdAt = LocalDateTime.now();
    }
    
    public ArticleCacheEntity(String urlHash, String url, String language, String model, 
                             String cacheType, String dataJson, long cacheDurationMs) {
        this();
        this.urlHash = urlHash;
        this.url = url;
        this.language = language;
        this.model = model;
        this.cacheType = cacheType;
        this.dataJson = dataJson;
        this.expiresAt = this.createdAt.plusNanos(cacheDurationMs * 1_000_000);
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUrlHash() {
        return urlHash;
    }

    public void setUrlHash(String urlHash) {
        this.urlHash = urlHash;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getCacheType() {
        return cacheType;
    }

    public void setCacheType(String cacheType) {
        this.cacheType = cacheType;
    }

    public String getDataJson() {
        return dataJson;
    }

    public void setDataJson(String dataJson) {
        this.dataJson = dataJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}