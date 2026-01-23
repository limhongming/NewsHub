# AGENTS.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Project Overview
NewsHub is a Spring Boot application that aggregates news from BBC and CNN via RSS feeds, scrapes article content with Jsoup, and uses Google Gemini AI to provide analysis (urgency, economic impact, global impact ratings).

## Build & Run Commands
```powershell
# Build (skipping tests)
.\mvnw clean package -DskipTests

# Run locally (default port 8080)
.\mvnw spring-boot:run

# Run tests
.\mvnw test

# Run a single test class
.\mvnw test -Dtest=TestClassName

# Deploy to VPS (triggers CI/CD pipeline)
.\git-sync.bat
```

## Configuration
- `src/main/resources/application.properties` is git-ignored for security
- Requires `gemini.api.key` property (via file or `GEMINI_API_KEY` environment variable)
- Production runs on port 80; local development uses port 8080

## Architecture

### Layer Structure
```
controller/NewsController.java  → REST API endpoints (/api/*)
    ↓
service/
├── NewsService.java           → RSS fetching (Rome) + HTML scraping (Jsoup)
├── NewsSystemService.java     → Gemini API integration with model fallback
└── NewsCacheService.java      → File-based caching (1-hour TTL)
    ↓
model/                         → Java records (NewsItem, MergedNewsCluster, etc.)
```

### Key API Endpoints
- `GET /api/news` - BBC news items
- `GET /api/news/cnn` - CNN news items  
- `GET /api/news/bbc/merged` - BBC news with AI clustering/analysis
- `GET /api/news/merged` - CNN news with AI clustering/analysis
- `POST /api/analyze` - Analyze a URL with full article scraping
- `POST /api/analyze/snippet` - Analyze a news snippet
- `GET /api/models` - List available Gemini models

### AI Integration
`NewsSystemService` handles all Gemini API calls with automatic fallback through multiple models (`gemini-2.5-flash-lite` → `gemini-2.0-flash-lite-001` → etc.) when rate-limited (429) or unavailable (503/404).

### Caching
`NewsCacheService` uses file-based JSON caching in `./cache/` directory with 1-hour expiration. Cache keys combine source tab, language, and model name.

### Frontend
Single-page app in `src/main/resources/static/index.html` - all HTML/CSS/JS embedded in one file.

## Deployment
Pushing to `main` triggers GitHub Actions workflow (`.github/workflows/maven.yml`) which:
1. SSHs into VPS
2. Backs up `application.properties`
3. Force-syncs code
4. Restores config
5. Builds on VPS with Maven wrapper
6. Kills port 80 and starts new JAR

## Post-Change Workflow
After any code modification, run `.\git-sync.bat` to push and trigger deployment.
