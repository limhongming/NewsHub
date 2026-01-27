package com.newsinsight.repository;

import com.newsinsight.model.NewsCacheClusterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface NewsCacheClusterRepository extends JpaRepository<NewsCacheClusterEntity, Long> {
    
    Optional<NewsCacheClusterEntity> findByCacheKey(String cacheKey);
    
    @Query("SELECT n FROM NewsCacheClusterEntity n WHERE n.cacheKey = :cacheKey AND n.expiresAt > :now")
    Optional<NewsCacheClusterEntity> findByCacheKeyAndNotExpired(
            @Param("cacheKey") String cacheKey, 
            @Param("now") LocalDateTime now);
            
    @Query("SELECT n FROM NewsCacheClusterEntity n WHERE n.tab = :tab AND n.language = :language AND n.expiresAt > :now")
    java.util.List<NewsCacheClusterEntity> findByTabAndLanguageAndNotExpired(
            @Param("tab") String tab,
            @Param("language") String language,
            @Param("now") LocalDateTime now);
    
    @Modifying
    @Query("DELETE FROM NewsCacheClusterEntity n WHERE n.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
    
    @Modifying
    @Query("DELETE FROM NewsCacheClusterEntity n WHERE n.cacheKey = :cacheKey")
    void deleteByCacheKey(@Param("cacheKey") String cacheKey);
}