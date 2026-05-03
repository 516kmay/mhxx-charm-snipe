/**
 * 調合スナイプ（Lv2通常弾の個数列から現在フレーム特定）のテスト。
 *
 * 仕様:
 *   Lv2通常弾 = ハリの実 + カラの実（各16個以上用意）
 *   1回の調合で乱数5消費
 *   (val & 0xFFFF) % 100 の値で個数が決まる:
 *     0-24  → 2個
 *     25-74 → 3個
 *     75-99 → 4個
 */
public class ComboSnipeTest {
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
        System.out.println("=== ComboSnipeTest ===\n");

        // --- Test 1: 個数マッピングの境界値 ---
        System.out.println("[Test 1] 調合個数マッピング");
        assertEquals(2, MHXXCharmApp.comboCountFromValue(0), "val=0 → 2個");
        assertEquals(2, MHXXCharmApp.comboCountFromValue(24), "val=24 → 2個");
        assertEquals(3, MHXXCharmApp.comboCountFromValue(25), "val=25 → 3個");
        assertEquals(3, MHXXCharmApp.comboCountFromValue(74), "val=74 → 3個");
        assertEquals(4, MHXXCharmApp.comboCountFromValue(75), "val=75 → 4個");
        assertEquals(4, MHXXCharmApp.comboCountFromValue(99), "val=99 → 4個");

        // --- Test 2: simulateCombo: あるフレームから連続調合した結果 ---
        System.out.println("\n[Test 2] simulateCombo の出力確認");
        {
            int[] result = MHXXCharmApp.simulateCombo(0, 5);
            System.out.println("  frame=0 から5回調合: " + java.util.Arrays.toString(result));
            // 全要素が 2, 3, 4 のいずれかであること
            boolean allValid = true;
            for (int c : result) {
                if (c != 2 && c != 3 && c != 4) allValid = false;
            }
            if (allValid) {
                passed++;
                System.out.println("  ✓ 全要素が2〜4の範囲");
            } else {
                failed++;
                System.out.println("  ✗ 範囲外の値を含む");
            }
        }

        // --- Test 3: 一貫性: simulateCombo → reverseSearchCombo でフレーム復元 ---
        System.out.println("\n[Test 3] simulate→reverseSearch の一貫性");
        {
            long targetFrame = 100;
            int[] expected = MHXXCharmApp.simulateCombo(targetFrame, 8);
            var results = MHXXCharmApp.reverseSearchCombo(expected, 200, null);
            boolean found = false;
            for (long f : results) {
                if (f == targetFrame) { found = true; break; }
            }
            assertEquals(true, found, "frame=100 の個数列で逆算→100 が候補に含まれる");
            System.out.println("  候補数: " + results.size() + " (200F中)");
        }

        // --- Test 4: 複数フレームでの一貫性 ---
        System.out.println("\n[Test 4] 0〜99F全フレームの一貫性");
        {
            int tested = 0, foundAll = 0;
            for (int f = 0; f < 100; f++) {
                int[] expected = MHXXCharmApp.simulateCombo(f, 8);
                tested++;
                var results = MHXXCharmApp.reverseSearchCombo(expected, 100, null);
                boolean found = false;
                for (long r : results) {
                    if (r == f) { found = true; break; }
                }
                if (found) foundAll++;
                else System.out.println("  ✗ frame=" + f + " が逆算で見つからない");
            }
            assertEquals(tested, foundAll, "8個の調合列で全100フレーム復元可能");
        }

        // --- Test 5: 短い個数列では候補が多数出る ---
        System.out.println("\n[Test 5] 短い個数列の候補数");
        {
            // 3個1つだけだと候補がたくさん出るはず
            int[] shortSeq = {3};
            var results = MHXXCharmApp.reverseSearchCombo(shortSeq, 1000, null);
            if (results.size() > 100) {
                passed++;
                System.out.println("  ✓ 長さ1の列 → 多数候補 (" + results.size() + "件/1000F)");
            } else {
                failed++;
                System.out.println("  ✗ 候補が少なすぎる (" + results.size() + "件)");
            }
        }

        // --- Test 6: 長い個数列では候補が絞られる ---
        System.out.println("\n[Test 6] 長い個数列の候補絞り込み");
        {
            int[] longSeq = MHXXCharmApp.simulateCombo(500, 15);
            var results = MHXXCharmApp.reverseSearchCombo(longSeq, 10000, null);
            if (results.size() <= 10) {
                passed++;
                System.out.println("  ✓ 長さ15の列 → 候補10個以下 (" + results.size() + "件/10000F)");
            } else {
                failed++;
                System.out.println("  ✗ 絞り込みが弱い (" + results.size() + "件)");
            }
            // 必ず元のフレーム500が含まれる
            boolean found = false;
            for (long r : results) if (r == 500) { found = true; break; }
            assertEquals(true, found, "frame=500 が候補に含まれる");
        }

        // --- Test 7: バリデーション ---
        System.out.println("\n[Test 7] バリデーション");
        {
            // 空配列
            var r1 = MHXXCharmApp.reverseSearchCombo(new int[]{}, 100, null);
            assertEquals(0, r1.size(), "空配列 → 候補なし");

            // 不正な個数（5）
            var r2 = MHXXCharmApp.reverseSearchCombo(new int[]{5}, 100, null);
            assertEquals(0, r2.size(), "不正な個数 → 候補なし");

            // 1個（範囲外）
            var r3 = MHXXCharmApp.reverseSearchCombo(new int[]{1}, 100, null);
            assertEquals(0, r3.size(), "個数1（範囲外） → 候補なし");
        }

        // --- Test 8: generateComboCode1 (フレーム消費+調合+録画) ---
        System.out.println("\n[Test 8] generateComboCode1 の生成確認");
        {
            String code = MHXXCharmApp.generateComboCode1(410, 0);
            assertContains(code, "#include <NintendoSwitchControlLibrary.h>", "include文");
            assertContains(code, "void setup()", "setup関数");
            assertContains(code, "410", "Continue回数410");
            assertContains(code, "Button::A", "A連打");
            assertContains(code, "CAPTURE", "キャプチャーボタン（録画）");
            assertContains(code, "HOME", "HOMEボタン（ゲーム中断）");
            // A長押しで連続調合する命令が入っているか（10秒固定）
            assertContains(code, "holdButton(Button::A, 10000)", "A長押し10秒固定");
            // リスト画面に遷移するためのメニュー操作
            assertContains(code, "Button::PLUS", "+ボタンでメニューを開く");
            // 「リストから調合」を選んだ後の決定A
            assertContains(code, "→ 調合確認画面", "調合確認画面への遷移コメント");
        }

        // --- Test 8b: downKeysToLv2 引数の反映 ---
        System.out.println("\n[Test 8b] generateComboCode1 のdownKeysToLv2引数の反映");
        {
            String code0 = MHXXCharmApp.generateComboCode1(410, 0);
            assertContains(code0, "Lv2通常弾は先頭にあるためカーソル移動なし", "downKeys=0でカーソル移動なし");

            String code3 = MHXXCharmApp.generateComboCode1(410, 3);
            assertContains(code3, "pushHat(Hat::DOWN, 100, 3)", "downKeys=3で↓3回");
        }

        // --- Test 10: 検索範囲がint上限を超えるlong値で動作 ---
        System.out.println("\n[Test 10] reverseSearchCombo がlong範囲で動作");
        {
            // intの上限超えはランタイム時間がかかりすぎるので、
            // 「シグネチャがlongを受け付ける」ことの確認に留める
            long simFrame = 50_000_000L;  // 5000万F
            int[] expected = MHXXCharmApp.simulateCombo(simFrame, 15);
            java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
            // long型の範囲で渡す（intキャストなし）
            long maxF = 100_000_000L;
            var results = MHXXCharmApp.reverseSearchCombo(expected, maxF, cancel);
            boolean found = results.contains(simFrame);
            if (found) {
                passed++;
                System.out.println("  ✓ 5000万Fの想定F が long型検索範囲で見つかる");
            } else {
                failed++;
                System.out.println("  ✗ 5000万Fの想定F が見つからない (候補数=" + results.size() + ")");
            }
        }

        // --- Test 9: generateComboCode2 (ゲーム復帰+待機+マカ錬金) ---
        System.out.println("\n[Test 9] generateComboCode2 の生成確認");
        {
            String code = MHXXCharmApp.generateComboCode2(12345);
            assertContains(code, "#include <NintendoSwitchControlLibrary.h>", "include文");
            assertContains(code, "void setup()", "setup関数");
            assertContains(code, "12345", "待ち時間12345ms");
            assertContains(code, "Button::HOME", "HOME復帰");
            assertContains(code, "マカ錬金", "マカ錬金コメント");
        }

        System.out.printf("%n=== 結果: %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    static void assertContains(String text, String expected, String msg) {
        if (text.contains(expected)) {
            passed++;
            System.out.println("  ✓ " + msg);
        } else {
            failed++;
            System.out.println("  ✗ " + msg + " — expected to contain: " + expected);
        }
    }
}
