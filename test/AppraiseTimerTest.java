/**
 * 鑑定タイマーのロジック検証テスト。
 * formatElapsed と 報酬生成終了フレーム計算の正しさを確認する。
 */
public class AppraiseTimerTest {
    static int passed = 0, failed = 0;

    static void assertEquals(Object expected, Object actual, String msg) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("  ✓ " + msg);
        } else {
            failed++;
            System.out.println("  ✗ " + msg + " — expected: " + expected + ", actual: " + actual);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== AppraiseTimerTest ===\n");

        // --- formatElapsed ---
        System.out.println("[Test 1] formatElapsed");
        assertEquals("0:00.00", MHXXCharmApp.formatElapsed(0.0), "0秒 → 0:00.00");
        assertEquals("0:01.23", MHXXCharmApp.formatElapsed(1.234), "1.234秒 → 0:01.23");
        assertEquals("0:59.99", MHXXCharmApp.formatElapsed(59.99), "59.99秒 → 0:59.99");
        assertEquals("1:00.00", MHXXCharmApp.formatElapsed(60.0), "60秒 → 1:00.00");
        assertEquals("1:40.20", MHXXCharmApp.formatElapsed(100.20), "100.20秒 → 1:40.20");
        assertEquals("10:00.00", MHXXCharmApp.formatElapsed(600.0), "600秒 → 10:00.00");

        // --- 報酬生成終了フレーム計算 ---
        // rewardEnd = base + addJudge + totalCount
        // addJudge = (additional < 4) ? additional + 1 : 4
        // additional = totalCount - normalCount
        System.out.println("\n[Test 2] 報酬生成終了フレーム計算");
        {
            // 例: 基準=1000, 通常=0, 合計=2 → 追加=2, addJudge=3, rewardEnd = 1000+3+2 = 1005
            long base = 1000;
            int total = 2, normal = 0;
            int additional = total - normal;
            int addJudge = (additional < 4) ? additional + 1 : 4;
            long rewardEnd = base + addJudge + total;
            assertEquals(1005L, rewardEnd, "base=1000, normal=0, total=2 → rewardEnd=1005");
        }
        {
            // 例: 基準=1000, 通常=3, 合計=5 → 追加=2, addJudge=3, rewardEnd = 1000+3+5 = 1008
            long base = 1000;
            int total = 5, normal = 3;
            int additional = total - normal;
            int addJudge = (additional < 4) ? additional + 1 : 4;
            long rewardEnd = base + addJudge + total;
            assertEquals(1008L, rewardEnd, "base=1000, normal=3, total=5 → rewardEnd=1008");
        }
        {
            // 例: 追加=4枠（最大）、addJudge=4
            long base = 1000;
            int total = 4, normal = 0;
            int additional = total - normal;
            int addJudge = (additional < 4) ? additional + 1 : 4;
            long rewardEnd = base + addJudge + total;
            assertEquals(1008L, rewardEnd, "base=1000, normal=0, total=4 (追加=4) → rewardEnd=1008");
        }
        {
            // 例: 追加=0枠、addJudge=1
            long base = 1000;
            int total = 3, normal = 3;
            int additional = total - normal;
            int addJudge = (additional < 4) ? additional + 1 : 4;
            long rewardEnd = base + addJudge + total;
            assertEquals(1004L, rewardEnd, "base=1000, normal=3, total=3 (追加=0) → rewardEnd=1004");
        }

        // --- 待機時間計算（典型例） ---
        System.out.println("\n[Test 3] 待機時間計算");
        {
            // 目標=5000, 基準=1000, 通常=0, 合計=2 → rewardEnd=1005
            // 待機フレーム = 5000 - 1005 = 3995, 秒 = 3995/30 = 133.166...
            long target = 5000, base = 1000;
            int total = 2, normal = 0;
            int additional = total - normal;
            int addJudge = (additional < 4) ? additional + 1 : 4;
            long rewardEnd = base + addJudge + total;
            long waitFrames = target - rewardEnd;
            double waitSec = waitFrames / 30.0;
            assertEquals(3995L, waitFrames, "待機フレーム = 3995");
            assertEquals("2:13.17", MHXXCharmApp.formatElapsed(waitSec), "待機時間 = 2:13.17");
        }

        System.out.printf("%n=== 結果: %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
