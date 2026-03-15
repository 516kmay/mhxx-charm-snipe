@echo off
where javac >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo JDK not found. Install from https://adoptium.net/
    pause
    exit /b 1
)
if not exist build mkdir build
echo Compiling...
javac --release 21 -encoding UTF-8 -d build MHXXCharmApp.java
if %ERRORLEVEL% neq 0 (
    echo Compile failed.
    pause
    exit /b 1
)
echo Creating JAR...
jar cfm MHXXCharmApp.jar MANIFEST.MF -C build .
echo Done! Double-click MHXXCharmApp.jar to run.
pause
