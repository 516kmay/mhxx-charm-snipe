@echo off
cd /d "%~dp0"
java -jar "MHXX-Charm-Snipe.jar"
if errorlevel 1 pause
