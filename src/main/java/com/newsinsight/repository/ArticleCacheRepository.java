package com.newsinsight.repository;

import com.newsinsight.model.ArticleCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ArticleCacheRepository extends JpaRepository<ArticleCacheEntity, Long> {
    
    Optional<ArticleCacheEntity> findByUrlHash(String urlHash);
    
    @Query("SELECT a FROM ArticleCacheEntity a WHERE a.urlHash = :urlHash AND a.expiresAt > :now")
    Optional<ArticleCacheEntity> findByUrlHashAndNotExpired(
            @Param("urlHash") String urlHash, 
            @Param("now") LocalDateTime now);
    
    @Query("SELECT a FROM ArticleCacheEntity a WHERE a.urlHash = :urlHash AND a.language = :language AND a.model = :model AND a.cacheType = :cacheType AND a.expiresAt > :now")
    Optional<ArticleCacheEntity> findByUrlHashLanguageModelTypeAndNotExpired(
            @Param("urlHash") String urlHash,
            @Param("language") String language,
            @Param("model") String model,
            @Param("cacheType") String cacheType,
            @Param("now") LocalDateTime now);
    
    @Modifying
    @Query("DELETE FROM ArticleCacheEntity a WHERE a.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
    
    @Modifying
    @Query("DELETE FROM ArticleCacheEntity a WHERE a.urlHash = :urlHash")
    void deleteByUrlHash(@Param("urlHash") String urlHash);
}