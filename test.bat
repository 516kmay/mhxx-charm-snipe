@echo off
if not exist build mkdir build
if not exist build-test mkdir build-test
javac --release 21 -encoding UTF-8 -d build src\*.java
if %ERRORLEVEL% neq 0 exit /b 1
javac --release 21 -encoding UTF-8 -cp build -d build-test test\*.java
if %ERRORLEVEL% neq 0 exit /b 1
java -ea -cp "build;build-test" MHXXCharmAppTest
