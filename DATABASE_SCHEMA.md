# NewsHub Database Documentation

This document details the database schema, table structures, and key queries for the NewsHub application. The application uses **MySQL** with **Spring Data JPA** (Hibernate) for persistence.

## 1. Naming Conventions

Spring Boot's default naming strategy converts Java **camelCase** fields to **snake_case** database columns.

| Java Entity Field | MySQL Column Name | Description |
| :--- | :--- | :--- |
| `cacheKey` | `cache_key` | Unique identifier for the cached item |
| `createdAt` | `created_at` | Timestamp when the record was created |
| `expiresAt` | `expires_at` | Timestamp when the cache entry expires |
| `dataJson` | `data_json` | The actual content payload (JSON format) |
| `imageUrl` | `image_url` | URL to the article's image (if applicable) |

## 2. Tables

### A. `news_cache_clusters`
Stores the aggregated/summarized news lists for the main feed tabs. This is what you see when you switch tabs (e.g., "BBC AI Analyzed", "CNN").

| Column Name | Type | Key | Description |
| :--- | :--- | :--- | :--- |
| `id` | `bigint` | PK | Auto-increment primary key |
| `cache_key` | `varchar(255)` | UNI | Unique Key. Format: `tab_Language_Model` (e.g., `bbc-world_English_deepseek-chat`) |
| `tab` | `varchar(255)` | | The source/category tab (e.g., `bbc`, `bbc-world`, `cnn`) |
| `language` | `varchar(255)` | | Language of the analysis (e.g., `English`) |
| `model` | `varchar(255)` | | AI Model used (e.g., `deepseek-chat`, `gemini-1.5-flash`) |
| `data_json` | `json` | | **CRITICAL:** Stores the list of `MergedNewsCluster` objects. Contains `topic`, `summary`, `image_url`, etc. |
| `created_at` | `datetime(6)` | | When this batch was processed |
| `expires_at` | `datetime(6)` | MUL | When this batch becomes stale (indexed for cleanup) |

### B. `article_cache`
Stores the detailed analysis for *individual* articles. This is accessed when you click "Read Full Article" on a manually imported link or a specific item.

| Column Name | Type | Key | Description |
| :--- | :--- | :--- | :--- |
| `id` | `bigint` | PK | Auto-increment primary key |
| `url_hash` | `varchar(255)` | UNI | SHA-256 or similar hash of the article URL (primary lookup key) |
| `url` | `varchar(768)` | | The full original URL of the article |
| `data_json` | `json` | | Stores `AnalysisResponse` (summary, ratings, full text, extracted image) |
| `created_at` | `datetime(6)` | | Creation time |
| `expires_at` | `datetime(6)` | | Expiration time |

---

## 3. Useful SQL Queries

### Check Stored Cache Keys
See what tabs and models are currently active in the database.
```sql
SELECT id, cache_key, tab, model, language FROM news_cache_clusters;
```

### Inspect Raw JSON Data (Debug Images)
Check the actual JSON payload for a specific feed to verify if `image_url` is present.
```sql
-- Replace 'bbc-world_English_deepseek-chat' with a key found in the previous query
SELECT data_json FROM news_cache_clusters WHERE cache_key = 'bbc-world_English_deepseek-chat';
```

### Find Specific Articles (JSON Search)
Search the JSON blob for a specific headline or topic to see its details.
```sql
SELECT id, cache_key FROM news_cache_clusters 
WHERE JSON_SEARCH(data_json, 'one', '%Gunfire%') IS NOT NULL;
```

### Check Article Cache
See what individual articles have been fully analyzed.
```sql
SELECT id, url, created_at FROM article_cache ORDER BY created_at DESC LIMIT 10;
```

### Manually Expire/Clear Cache
To force the system to re-fetch and re-analyze everything, delete the cache entries.
```sql
-- Clear Main Feed Cache
DELETE FROM news_cache_clusters;

-- Clear Individual Article Cache
DELETE FROM article_cache;
```

## 4. Troubleshooting Common Issues

### "Unknown column 'cacheKey' in 'where clause'"
**Cause:** You are using the Java field name.
**Fix:** Use the MySQL column name `cache_key`.

### "Images are missing in Frontend but present in DB"
1. Run the **Inspect Raw JSON** query above.
2. Look for `"image_url": "..."` in the output.
   - If it says `null`, the backend extraction failed.
   - If it has a URL, the frontend rendering is the issue (check browser console).

### "Data is old/stale"
Check the `created_at` timestamp.
```sql
SELECT cache_key, created_at FROM news_cache_clusters;
```
If the timestamp is old, the background scheduler might have stopped or the app was restarted without persistence. The system is designed to auto-refresh every 5-60 minutes depending on configuration.
