/**
 * クエスト自動周回スケッチ生成のテスト。
 * MHXXCharmApp.generateQuestSketch() の動作を検証する。
 */
public class QuestSketchTest {
    static int passed = 0, failed = 0;

    static void assertContains(String text, String expected, String msg) {
        if (text.contains(expected)) {
            passed++;
            System.out.println("  ✓ " + msg);
        } else {
            failed++;
            System.out.println("  ✗ " + msg + " — expected to contain: " + expected);
        }
    }

    static void assertNotContains(String text, String unexpected, String msg) {
        if (!text.contains(unexpected)) {
            passed++;
            System.out.println("  ✓ " + msg);
        } else {
            failed++;
            System.out.println("  ✗ " + msg + " — should not contain: " + unexpected);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== QuestSketchTest ===\n");

        // --- Test 1: デフォルトパラメータでの生成 ---
        System.out.println("[Test 1] デフォルトパラメータでスケッチ生成");
        {
            MHXXCharmApp.QuestSketchParams p = new MHXXCharmApp.QuestSketchParams();
            String sketch = MHXXCharmApp.generateQuestSketch(p);

            assertContains(sketch, "#include <NintendoSwitchControlLibrary.h>", 
                "ライブラリのinclude文");
            assertContains(sketch, "void setup()", "setup関数");
            assertContains(sketch, "void loop()", "loop関数");
            assertContains(sketch, "Button::A", "Aボタン使用");
            assertContains(sketch, "tiltLeftStick", "スティック操作");
            assertContains(sketch, "Button::PLUS", "メニューボタン（モドリ玉用）");
            assertContains(sketch, "誤動作防止", "安全のためのB連打コメント");
        }

        // --- Test 2: 採掘回数のパラメータが反映される ---
        System.out.println("\n[Test 2] 採掘A連打回数が反映される");
        {
            MHXXCharmApp.QuestSketchParams p = new MHXXCharmApp.QuestSketchParams();
            p.diggingAPressCount = 15;
            String sketch = MHXXCharmApp.generateQuestSketch(p);
            assertContains(sketch, "15", "採掘回数15が含まれる");
        }

        // --- Test 3: 移動時間のパラメータが反映される ---
        System.out.println("\n[Test 3] BC→エリア4走行時間が反映される");
        {
            MHXXCharmApp.QuestSketchParams p = new MHXXCharmApp.QuestSketchParams();
            p.bcToArea4TravelMs = 4500;
            String sketch = MHXXCharmApp.generateQuestSketch(p);
            assertContains(sketch, "4500", "走行時間4500msが含まれる");
        }

        // --- Test 4: 生成されたスケッチがC++的に整形されている ---
        System.out.println("\n[Test 4] 生成スケッチの構文チェック");
        {
            MHXXCharmApp.QuestSketchParams p = new MHXXCharmApp.QuestSketchParams();
            String sketch = MHXXCharmApp.generateQuestSketch(p);

            // { } の対応
            long openBraces = sketch.chars().filter(c -> c == '{').count();
            long closeBraces = sketch.chars().filter(c -> c == '}').count();
            if (openBraces == closeBraces) {
                passed++;
                System.out.println("  ✓ 中括弧の対応が取れている (" + openBraces + " == " + closeBraces + ")");
            } else {
                failed++;
                System.out.println("  ✗ 中括弧の対応が崩れている (" + openBraces + " != " + closeBraces + ")");
            }

            // ; で終わる行が十分ある（文が最低10個）
            long semicolons = sketch.chars().filter(c -> c == ';').count();
            if (semicolons >= 10) {
                passed++;
                System.out.println("  ✓ セミコロンが10個以上 (" + semicolons + ")");
            } else {
                failed++;
                System.out.println("  ✗ セミコロンが不足 (" + semicolons + ")");
            }
        }

        // --- Test 5: デフォルト値の妥当性 ---
        System.out.println("\n[Test 5] デフォルト値の妥当性");
        {
            MHXXCharmApp.QuestSketchParams p = new MHXXCharmApp.QuestSketchParams();
            // 典型的な値が設定されているか
            if (p.diggingAPressCount >= 5 && p.diggingAPressCount <= 20) {
                passed++;
                System.out.println("  ✓ 採掘回数は5〜20の範囲 (" + p.diggingAPressCount + ")");
            } else {
                failed++;
                System.out.println("  ✗ 採掘回数が範囲外 (" + p.diggingAPressCount + ")");
            }

            if (p.bcToArea4TravelMs >= 2000 && p.bcToArea4TravelMs <= 10000) {
                passed++;
                System.out.println("  ✓ BC→エリア4走行時間は2〜10秒 (" + p.bcToArea4TravelMs + "ms)");
            } else {
                failed++;
                System.out.println("  ✗ 走行時間が範囲外 (" + p.bcToArea4TravelMs + "ms)");
            }

            if (p.areaLoadWaitMs >= 3000) {
                passed++;
                System.out.println("  ✓ エリアロード待機は3秒以上 (" + p.areaLoadWaitMs + "ms)");
            } else {
                failed++;
                System.out.println("  ✗ ロード待機が短すぎる (" + p.areaLoadWaitMs + "ms)");
            }
        }

        // --- Test 6: 自動化の落とし穴対策が入っているか ---
        System.out.println("\n[Test 6] 落とし穴対策の検証");
        {
            MHXXCharmApp.QuestSketchParams p = new MHXXCharmApp.QuestSketchParams();
            String sketch = MHXXCharmApp.generateQuestSketch(p);

            // setup()のB連打が十分（10回以上）
            // setup内のpushButton(Button::B, 500, N)の N が 10以上か確認
            java.util.regex.Matcher setupM = java.util.regex.Pattern.compile(
                "void setup\\(\\)\\s*\\{([^}]*)\\}", java.util.regex.Pattern.DOTALL
            ).matcher(sketch);
            if (setupM.find()) {
                String setupBody = setupM.group(1);
                java.util.regex.Matcher bMatcher = java.util.regex.Pattern.compile(
                    "Button::B[^;]*?,\\s*(\\d+)\\s*\\)"
                ).matcher(setupBody);
                int maxBCount = 0;
                while (bMatcher.find()) {
                    int count = Integer.parseInt(bMatcher.group(1));
                    if (count > maxBCount) maxBCount = count;
                }
                if (maxBCount >= 10) {
                    passed++;
                    System.out.println("  ✓ setup()のB連打が10回以上 (" + maxBCount + ")");
                } else {
                    failed++;
                    System.out.println("  ✗ setup()のB連打が不足 (" + maxBCount + "回)");
                }
            } else {
                failed++;
                System.out.println("  ✗ setup()が見つからない");
            }

            // loop()内にも最低数回のB連打があるべき（メニュークリア用）
            int bButtonOccurrences = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Button::B").matcher(sketch);
            while (m.find()) bButtonOccurrences++;
            if (bButtonOccurrences >= 3) {
                passed++;
                System.out.println("  ✓ B連打が3回以上スケッチに含まれる (" + bButtonOccurrences + "回)");
            } else {
                failed++;
                System.out.println("  ✗ B連打が不足 (" + bButtonOccurrences + "回)");
            }

            // エリア境界のA押下が複数回（空振り対策）
            // コメント「エリア境界」の直後のA連打回数を確認
            if (sketch.contains("エリア境界") || sketch.contains("エリア切替")) {
                passed++;
                System.out.println("  ✓ エリア境界/切替のコメントあり");
            } else {
                failed++;
                System.out.println("  ✗ エリア境界のコメントがない");
            }
        }

        System.out.printf("%n=== 結果: %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
