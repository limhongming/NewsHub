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
- **Current Strategy:** The API Key is hardcoded in the file on the VPS (Option B) to avoid environment variable complexity with `sudo`.
- **Database Env Variables:** If using environment variables for the database, the format is:
  ```bash
  DB_URL=jdbc:mysql://localhost:3306/newshub
  DB_USERNAME=your_username
  DB_PASSWORD=your_password
  ```

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
