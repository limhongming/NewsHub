@echo off
echo Initializing Git Repository...
git init
git branch -M main
git remote add origin https://github.com/LHM69/NewsHub.git
echo.
echo Remote 'origin' added.
echo.
echo Performing initial commit...
git add .
git commit -m "Initial commit - News Insight Spring Boot"
git push -u origin main
pause
