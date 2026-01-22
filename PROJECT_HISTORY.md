# NewsHub - Development Summary

## Project Overview
**NewsHub** is a Spring Boot application that aggregates news from the BBC via RSS, scrapes the content, and uses the Gemini 2.5 Flash model to provide AI-powered analysis (Urgency, Economic Impact, Global Impact).

- **Current Version:** v1.1.2
- **Hosting:** VPS (Host stored in GitHub Secrets)
- **Port:** 80 (Runs as root/sudo, Standalone, No Nginx)
- **Language:** Java 21 (OpenJDK)

## Critical Setup Details

### 1. Automation Pipeline
We have established a robust CI/CD pipeline using GitHub Actions.
- **Trigger:** Any push to the `main` branch.
- **Workflow (`maven.yml`):**
    1.  Verifies the build on GitHub.
    2.  SSH into VPS.
    3.  **Backs up** the local `application.properties`.
    4.  Force-syncs the code (Git Reset).
    5.  **Restores** the config file.
    6.  Compiles the code ON the VPS using `./mvnw` (Maven Wrapper) and Java 21.
    7.  **Aggressively Kills** any process on Port 80.
    8.  Starts the new `app.jar` using `sudo` and `setsid` for detachment.

### 2. Configuration & Secrets
The `application.properties` file is **Git-Ignored** for security.
- **Location:** `src/main/resources/application.properties`
- **Manual Setup Required:** When setting up a new server, this file must be created manually.
- **Current Strategy:** The API Key is hardcoded in the file on the VPS (Option B) to avoid environment variable complexity with `sudo`.

### 3. Key Fixes Implemented
- **"Version 17 not supported":** Fixed by forcing `JAVA_HOME` to OpenJDK 21 and using Maven Wrapper.
- **"Port 80 in use":** Fixed by moving the kill command (`fuser -k -9 80/tcp`) to execute immediately before the Java start command.
- **"Status 143":** Fixed by detaching the process using `setsid` and redirecting input `< /dev/null`.
- **"No News Found":** Fixed by updating the BBC RSS URL to `https://` to avoid 302 redirects.

## How to Resume Work
1.  **Open CLI:** Navigate to this folder.
2.  **Make Changes:** Edit code as needed.
3.  **Sync:** ALWAYS run `.\git-sync.bat` to deploy changes.