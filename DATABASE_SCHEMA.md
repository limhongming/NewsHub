# NewsHub Database Documentation (v1.1 - 100% Accurate)

This document details the exact database schema, column mappings, and JSON structures for NewsHub. **Always refer to this file when querying MySQL directly.**

---

## 1. Global Naming Strategy
Spring Data JPA / Hibernate maps Java **camelCase** fields to MySQL **snake_case** columns.

---

## 2. Table: `news_cache_clusters`
Stores aggregated news lists for main tabs.

### Schema (MySQL)
| Column Name | Type | Java Property | Description |
| :--- | :--- | :--- | :--- |
| `id` | `bigint` | `id` | PK (Auto-increment) |
| `cache_key` | `varchar(255)` | `cacheKey` | UNI. Format: `tab_lang_model` |
| `tab` | `varchar(255)` | `tab` | e.g., `bbc-world`, `bbc-sport` |
| `language` | `varchar(255)` | `language` | e.g., `English` |
| `model` | `varchar(255)` | `model` | e.g., `deepseek-chat` |
| `data_json` | `json` | `dataJson` | **JSON Array** of Analyzed Items (see below) |
| `created_at` | `datetime(6)` | `createdAt` | Processing time |
| `expires_at` | `datetime(6)` | `expiresAt` | MUL. Stale date |

### `data_json` Structure (Array of Objects)
Every field is **snake_case** due to `@JsonProperty` annotations in `MergedNewsCluster.java`.
```json
[
  {
    "topic": "Title or Category",
    "summary": "AI summary text",
    "economic_impact": "Econ details",
    "global_impact": "Global details",
    "impact_rating": "5",          // String ("1"-"10")
    "what_next": "Prediction",
    "related_links": ["https://..."],
    "model_used": "deepseek-chat",
    "image_url": "https://...",    // CRITICAL: Standard for images
    "published_date": "Date String",
    "author": "String or null"
  }
]
```

---

## 3. Table: `article_cache`
Stores deep-dive analysis for individual URLs (Manually imported or "Read Full Article").

### Schema (MySQL)
| Column Name | Type | Java Property | Description |
| :--- | :--- | :--- | :--- |
| `id` | `bigint` | `id` | PK |
| `url_hash` | `varchar(255)` | `urlHash` | UNI. Primary lookup index |
| `url` | `text` | `url` | Full article URL |
| `language` | `varchar(255)` | `language` | Analysis language |
| `model` | `varchar(255)` | `model` | AI Model used |
| `cache_type` | `varchar(255)` | `cacheType` | `snippet` or `full_article` |
| `data_json` | `json` | `dataJson` | **JSON Object** (see below) |
| `created_at` | `datetime(6)` | `createdAt` | Creation date |
| `expires_at` | `datetime(6)` | `expiresAt` | MUL. Stale date |

### `data_json` Structure (Single Object)
Mapped from `AnalysisResponse.java`. Keys are **snake_case** due to `@JsonProperty`.
```json
{
  "analysis": {
    "summary": "Analysis text",
    "economic_impact": "...",
    "global_impact": "...",
    "impact_rating": 5,            // Integer (1-10)
    "urgency": "Medium"            // String
  },
  "full_text": "Full scraped article body",
  "image_url": "https://...",      // Scraped image URL
  "debug_info": "Metadata/Source string"
}
```

---

## 4. Key Maintenance Queries

### Verify All Active Tabs
```sql
SELECT tab, language, model, COUNT(*) as rows FROM news_cache_clusters GROUP BY tab, language, model;
```

### Reset / Clear All Caches
Use the frontend **Reset System** button or:
```sql
TRUNCATE TABLE news_cache_clusters;
TRUNCATE TABLE article_cache;
```

### Search for Missing Images
Find cached items where the image failed to be extracted:
```sql
SELECT cache_key FROM news_cache_clusters WHERE JSON_EXTRACT(data_json, '$[0].image_url') IS NULL;
```

---

## 5. Search by ID and Keywords

### Find a specific analysis by ID
Use the `\G` terminator instead of `;` for a clean, vertical output of the JSON data.
```sql
-- For individual articles (detailed analysis)
SELECT * FROM article_cache WHERE id = 1 \G

-- For batch clusters (news list tabs)
SELECT * FROM news_cache_clusters WHERE id = 1 \G
```

### Find articles by URL fragment
Useful for finding if a specific news link has been processed.
```sql
SELECT id, url, created_at FROM article_cache WHERE url LIKE '%bbc.com%';
```

### Search for a keyword inside the JSON list
Locates which batch row contains a specific topic or headline.
```sql
-- Finds the cluster ID containing a specific topic keyword (e.g., 'Niger')
SELECT id, cache_key FROM news_cache_clusters 
WHERE JSON_SEARCH(data_json, 'one', '%Niger%') IS NOT NULL;
```