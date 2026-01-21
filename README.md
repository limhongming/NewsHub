# NewsHub

A Spring Boot based News Aggregator and AI Analyzer.

## Project Structure
- **Backend:** Java Spring Boot 3.2
- **Frontend:** HTML/CSS/JS (Embedded in `src/main/resources/static`)
- **AI:** Google Gemini 2.5 Flash
- **Deployment:** GitHub Actions -> VPS (Port 80)

## Automated Workflow
This project uses a custom script `git-sync.bat` to handle version control.

**For AI Agents / Developers:**
After making ANY changes to the codebase, you must run:
```bash
.\git-sync.bat
```
This script will:
1. Add all changes.
2. Commit with a timestamped message.
3. Push to GitHub (which triggers the VPS deployment pipeline).

## Setup
1. **API Key:** Set `GEMINI_API_KEY` in your VPS environment variables.
2. **Build:** `mvn clean package`
3. **Run:** `java -jar target/*.jar`
