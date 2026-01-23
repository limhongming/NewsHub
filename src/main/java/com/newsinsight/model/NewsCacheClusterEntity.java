package com.newsinsight.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "news_cache_clusters", indexes = {
    @Index(name = "idx_cache_key", columnList = "cacheKey", unique = true),
    @Index(name = "idx_expires_at", columnList = "expiresAt")
})
public class NewsCacheClusterEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private String cacheKey;  // Format: tab_language_model (e.g., "bbc_english_gemini-2.5-flash")
    
    @Column(nullable = false)
    private String tab;       // e.g., "bbc", "cnn"
    
    @Column(nullable = false)
    private String language;  // e.g., "English", "Chinese"
    
    @Column(nullable = false)
    private String model;     // e.g., "gemini-2.5-flash"
    
    @Column(columnDefinition = "JSON", nullable = false)
    private String dataJson;  // JSON representation of List<MergedNewsCluster>
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false)
    private LocalDateTime expiresAt;
    
    public NewsCacheClusterEntity() {
        this.createdAt = LocalDateTime.now();
    }
    
    public NewsCacheClusterEntity(String cacheKey, String tab, String language, String model, String dataJson, long cacheDurationMs) {
        this();
        this.cacheKey = cacheKey;
        this.tab = tab;
        this.language = language;
        this.model = model;
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

    public String getCacheKey() {
        return cacheKey;
    }

    public void setCacheKey(String cacheKey) {
        this.cacheKey = cacheKey;
    }

    public String getTab() {
        return tab;
    }

    public void setTab(String tab) {
        this.tab = tab;
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