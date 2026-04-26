@echo off
REM 開発用テスト実行スクリプト
REM このディレクトリからテストをコンパイル・実行する

where javac >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo JDK not found. Install from https://adoptium.net/
    pause
    exit /b 1
)

if not exist build mkdir build

echo === Compiling MHXXCharmApp.java and tests ===
javac --release 21 -encoding UTF-8 -d build ..\src\*.java *.java
if %ERRORLEVEL% neq 0 (
    echo Compile failed.
    pause
    exit /b 1
)

echo.
echo === Running RewardReverseTest ===
java -Dfile.encoding=UTF-8 -cp build RewardReverseTest
if %ERRORLEVEL% neq 0 exit /b 1

echo.
echo === Running JumpRawTest ===
java -Dfile.encoding=UTF-8 -cp build JumpRawTest
if %ERRORLEVEL% neq 0 exit /b 1

echo.
echo === Running AppraiseTimerTest ===
java -Dfile.encoding=UTF-8 -cp build AppraiseTimerTest
if %ERRORLEVEL% neq 0 exit /b 1

echo.
echo === Running QuestSketchTest ===
java -Dfile.encoding=UTF-8 -cp build QuestSketchTest
if %ERRORLEVEL% neq 0 exit /b 1

echo.
echo === Running CombinationTest ===
java -Dfile.encoding=UTF-8 -cp build CombinationTest
if %ERRORLEVEL% neq 0 exit /b 1

echo.
echo === All tests passed ===
pause
