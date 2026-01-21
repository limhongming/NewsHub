@echo off
echo Syncing with GitHub...
"C:\Program Files\Git\bin\git.exe" add .
"C:\Program Files\Git\bin\git.exe" commit -m "Update NewsHub"
"C:\Program Files\Git\bin\git.exe" push
echo Done.
