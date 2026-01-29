# NewsHub - Development Summary

## Project Overview
**NewsHub** is a Spring Boot application that aggregates news from the BBC via RSS, scrapes the content, and uses the Gemini 2.5 Flash model to provide AI-powered analysis (Urgency, Economic Impact, Global Impact).

- **Current Version:** v1.1.2
- **Hosting:** VPS (Host stored in GitHub Secrets)
- **Port:** 80 (Runs as root/sudo, Standalone, No Nginx)
- **Language:** Java 21 (OpenJDK)

## Critical Setup Details

### 1. Local Development Environment
**IMPORTANT: Local PC Setup**
- **Purpose:** Code editing and Git operations only
- **No Local Build Tools:** Maven is NOT installed on the local PC
- **No Local Hosting:** The application is NOT run or hosted on the local PC
- **Deployment Command:** `.\git-sync.bat` is the ONLY command needed to deploy changes

### 2. Automation Pipeline (CI/CD)
We have established a robust CI/CD pipeline using GitHub Actions.
- **Trigger:** Any push to the `main` branch (initiated by `.\git-sync.bat`)
- **Workflow (`maven.yml`):**
    1.  Verifies the build on GitHub.
    2.  SSH into VPS.
    3.  **Backs up** the local `application.properties`.
    4.  Force-syncs the code (Git Reset).
    5.  **Restores** the config file.
    6.  **Compiles the code ON THE VPS** using `./mvnw` (Maven Wrapper) and Java 21.
    7.  **Aggressively Kills** any process on Port 80.
    8.  Starts the new `app.jar` using `sudo` and `setsid` for detachment.

**Key Point:** All building (Maven) and running happens exclusively on the VPS, NOT on the local development PC.

### 3. Configuration & Secrets
The `application.properties` file is **Git-Ignored** for security.
- **Location:** `src/main/resources/application.properties`
- **Manual Setup Required:** When setting up a new server, this file must be created manually on the VPS.
- **Current Strategy:** Both the API Key and Database credentials (URL, Username, Password) are hardcoded directly into the `application.properties` file on the VPS to ensure reliability and avoid environment variable resolution issues during sudo execution.

### 4. Key Fixes Implemented
- **"Version 17 not supported":** Fixed by forcing `JAVA_HOME` to OpenJDK 21 and using Maven Wrapper.
- **"Port 80 in use":** Fixed by moving the kill command (`fuser -k -9 80/tcp`) to execute immediately before the Java start command.
- **"Status 143":** Fixed by detaching the process using `setsid` and redirecting input `< /dev/null`.
- **"No News Found":** Fixed by updating the BBC RSS URL to `https://` to avoid 302 redirects.

## How to Resume Work (Development Workflow)
1.  **Local Development:** Edit code on your local PC using any IDE/editor.
2.  **Deploy Changes:** Run `.\git-sync.bat` to:
    - Stage all changes
    - Commit with message "Update NewsHub"
    - Push to GitHub
    - Trigger GitHub Actions pipeline
3.  **Automated Deployment:** GitHub Actions will:
    - SSH into your VPS
    - Build the application using Maven Wrapper on the VPS
    - Deploy and restart the application on the VPS
4.  **Verify:** Check your VPS to ensure the application is running on port 80.

## Important Notes
- **DO NOT** run Maven commands (`mvn`, `./mvnw`) on your local PC
- **DO NOT** attempt to run the Spring Boot application locally
- **ONLY** use `.\git-sync.bat` for deployment
- The local PC is strictly for code editing and Git operations

## Major Milestones (Jan 2026)

### 1. Stability & AI Integration
- **API 404 Fix:** Resolved issues with Google Gemini API 404 errors by implementing `java.net.URI` to prevent double-encoding of URL paths.
- **Multi-Model Fallback:** Implemented a robust fallback system that cycles through Gemini 1.5/2.5 and DeepSeek (V3/R1) models if rate limits or errors occur.
- **DeepSeek Support:** Integrated DeepSeek API as a high-priority model option for sophisticated news analysis.

### 2. Enhanced Data Extraction
- **Robust RSS Images:** Implemented aggressive XML parsing for BBC/CNN feeds, capturing images from `media:thumbnail` and `media:content` namespaces that standard RSS parsers often miss.
- **JSON-LD Scraper:** Added a fallback scraper that extracts high-quality lead images from hidden JSON-LD script blocks on news websites.
- **Referrer Policy Fix:** Added `no-referrer` policies to frontend image tags to bypass hotlinking protections from major news providers.

### 3. Architecture & Persistence
- **MySQL Migration:** Fully migrated from memory-based storage to a persistent MySQL schema with robust indexing.
- **Unified History:** Updated the caching logic to retrieve news from *all* processed models, ensuring a continuous historical feed regardless of which model is currently selected.
- **Categorization:** Added BBC category support (World, Business, Sport, etc.) with dedicated database isolation per category.

### 4. Maintenance & Operations
- **System Reset:** Added a "Full Reset" feature to purge all database caches and re-fetch fresh data according to the latest schemas.
- **Backup System:** Implemented a one-click "Download JSON Backup" feature that exports the entire database in a clean, parsed format.
- **Schema Documentation:** Created `DATABASE_SCHEMA.md` as a source of truth for the persistent layer to prevent future naming or structure errors.
- **Kuala Lumpur Time:** Standardized the application to display article times in user-local time, which defaults to Kuala Lumpur (GMT+8) for targeted users.
