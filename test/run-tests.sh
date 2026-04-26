#!/bin/bash
# 開発用テスト実行スクリプト
set -e

mkdir -p build

echo "=== Compiling MHXXCharmApp.java and tests ==="
javac --release 21 -encoding UTF-8 -d build ../src/*.java *.java

echo ""
echo "=== Running RewardReverseTest ==="
java -Dfile.encoding=UTF-8 -cp build RewardReverseTest

echo ""
echo "=== Running JumpRawTest ==="
java -Dfile.encoding=UTF-8 -cp build JumpRawTest

echo ""
echo "=== Running AppraiseTimerTest ==="
java -Dfile.encoding=UTF-8 -cp build AppraiseTimerTest

echo ""
echo "=== Running QuestSketchTest ==="
java -Dfile.encoding=UTF-8 -cp build QuestSketchTest

echo ""
echo "=== Running ComboSnipeTest ==="
java -Dfile.encoding=UTF-8 -cp build ComboSnipeTest

echo ""
echo "=== All tests passed ==="
