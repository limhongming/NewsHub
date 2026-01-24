# NewsHub

A Spring Boot based News Aggregator and AI Analyzer.

## Project Structure
- **Backend:** Java Spring Boot 3.2
- **Frontend:** HTML/CSS/JS (Embedded in `src/main/resources/static`)
- **AI:** Google Gemini 2.5 Flash
- **Deployment:** GitHub Actions -> VPS (Port 80)

## 🔒 Security & Configuration
This project uses **Environment Variables** for secrets. The `application.properties` file is git-ignored globally.

### 1. GitHub Secrets (Required for Pipeline)
Go to **Settings > Secrets and variables > Actions** and add:
- `host`: Your VPS IP address.
- `username`: VPS username (e.g., `root` or `ubuntu`).
- `key`: Your VPS login password (or SSH private key).

### 2. VPS Configuration (Required for App)
You must manually create the config file on your VPS because it is ignored by Git. This file is **backed up and restored** automatically during every deployment, so your secrets are safe.

**Run this on your server:**

```bash
mkdir -p /var/www/html/NewsHub/src/main/resources/
nano /var/www/html/NewsHub/src/main/resources/application.properties
```

**Option A (Recommended - Secure):**
Paste this content using a placeholder, then use environment variables:
```properties
server.port=80
gemini.api.key=${GEMINI_API_KEY}
```
Then set the variable in your system: `echo 'export GEMINI_API_KEY="your_key"' >> ~/.bashrc`

**Option B (Simple):**
Paste your real key directly into the file. You DO NOT need environment variables if you do this.
```properties
server.port=80
gemini.api.key=AIzaSy_YOUR_ACTUAL_KEY_HERE
```

## Automated Workflow
**For Developers:**
After changes, run:
```bash
.\git-sync.bat
```
This triggers the CI/CD pipeline which pulls code, compiles on the VPS, and restarts the server.

---
*Last Triggered Deployment: Sat Jan 24 2026*
