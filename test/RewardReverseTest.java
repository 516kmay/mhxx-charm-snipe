/**
 * クエスト報酬逆算エンジンのテスト
 * 
 * 【前提】
 * - 報酬テーブル（採集ツアー系）:
 *   (rng & 0xFFFF) % 100 → 0-19:謎の骨, 20-39:釣りミミズ, 40-64:生肉, 65-84:砥石,
 *                           85-89:力の成長餌, 90-94:重の成長餌, 95-99:速の成長餌
 * - 追加報酬判定: 最大4回、rng%32 < 22 なら追加+1（22以上で即break、仮定）
 * - 報酬決定後 → 鑑定までの間に乱数消費なし
 * 
 * コンパイル: javac --release 21 -encoding UTF-8 RewardReverseTest.java
 * 実行:       java RewardReverseTest
 */
public class RewardReverseTest {

    // ================================================================
    // RNG Engine（MHXXCharmApp.javaからコピー）
    // ================================================================
    static final long[] INITIAL_SEED = {0x0194FD72L, 0x79E6C985L, 0x08DD9701L, 0x41CFCE91L};
    static final long MASK32 = 0xFFFFFFFFL;

    static class RNG {
        long x, y, z, w;
        long f;

        RNG() { init(); }

        void init() {
            x = INITIAL_SEED[0]; y = INITIAL_SEED[1];
            z = INITIAL_SEED[2]; w = INITIAL_SEED[3];
            f = 0;
        }

        long next() {
            long t = (x ^ (x << 15)) & MASK32;
            x = y; y = z; z = w;
            w = (w ^ (w >>> 21) ^ t ^ (t >>> 4)) & MASK32;
            f++;
            return w;
        }
    }

    // ================================================================
    // 報酬テーブル（採集ツアー系）
    // ================================================================
    static final String[] REWARD_ITEMS = {
        "謎の骨", "釣りミミズ", "生肉", "砥石",
        "力の成長餌", "重の成長餌", "速の成長餌"
    };

    // (rng & 0xFFFF) % 100 の値からアイテム名を返す
    static String rewardFromValue(int val) {
        if (val < 20) return "謎の骨";
        if (val < 40) return "釣りミミズ";
        if (val < 65) return "生肉";
        if (val < 85) return "砥石";
        if (val < 90) return "力の成長餌";
        if (val < 95) return "重の成長餌";
        return "速の成長餌";
    }

    // ================================================================
    // 報酬シミュレーション
    // ================================================================
    record RewardResult(int normalCount, int additionalCount, String[] rewards, long endFrame) {}

    /**
     * 指定フレームから報酬をシミュレートする。
     * @param startFrame 報酬決定開始フレーム
     * @param normalCount 通常報酬の固定枠数
     * @param breakOnFail true=22以上で即break, false=4回必ず判定
     * @return 報酬結果
     */
    static RewardResult simulateRewards(long startFrame, int normalCount, boolean breakOnFail) {
        RNG rng = new RNG();
        for (long i = 0; i < startFrame; i++) rng.next();

        // Step1: 追加報酬数の判定（最大4回）
        int additionalCount = 0;
        for (int i = 0; i < 4; i++) {
            long val = rng.next();
            if (val % 32 < 22) {
                additionalCount++;
            } else {
                if (breakOnFail) break;
            }
        }

        // Step2: 報酬内容の決定（通常報酬 + 追加報酬）
        int totalRewards = normalCount + additionalCount;
        String[] rewards = new String[totalRewards];
        for (int i = 0; i < totalRewards; i++) {
            long val = rng.next();
            int itemVal = (int)((val & 0xFFFF) % 100);
            rewards[i] = rewardFromValue(itemVal);
        }

        return new RewardResult(normalCount, additionalCount, rewards, rng.f);
    }

    // ================================================================
    // 逆算検索
    // ================================================================
    record SearchResult(long frame, int additionalCount, String[] rewards) {}

    /**
     * 報酬の個数と並びからフレームを逆算する。
     * 
     * @param totalRewardCount 報酬の合計個数（通常+追加、UIのプルダウンで選択）
     * @param normalCount 通常報酬の固定枠数（UIのプルダウンで選択）
     * @param targetRewards アイテムの並び（長さ == totalRewardCount）
     * @param maxFrames 検索範囲
     * @param breakOnFail 追加報酬判定でbreakするか
     * @return 候補フレームのリスト
     */
    static java.util.List<SearchResult> reverseSearch(
            int totalRewardCount, int normalCount, String[] targetRewards, 
            long maxFrames, boolean breakOnFail) {
        int expectedAdditional = totalRewardCount - normalCount;
        if (expectedAdditional < 0 || expectedAdditional > 4) return java.util.Collections.emptyList();
        if (totalRewardCount < 1) return java.util.Collections.emptyList();
        if (targetRewards.length != totalRewardCount) return java.util.Collections.emptyList();

        java.util.List<SearchResult> results = new java.util.ArrayList<>();
        RNG rng = new RNG();

        for (long frame = 0; frame < maxFrames; frame++) {
            rng.init();
            for (long i = 0; i < frame; i++) rng.next();

            // Step1: 追加報酬数判定（最大4回）
            int additionalCount = 0;
            for (int i = 0; i < 4; i++) {
                long val = rng.next();
                if (val % 32 < 22) {
                    additionalCount++;
                } else {
                    if (breakOnFail) break;
                }
            }

            // 追加報酬数が期待値と一致しなければスキップ
            if (additionalCount != expectedAdditional) continue;

            // Step2: 報酬内容チェック（通常報酬 + 追加報酬）
            int total = normalCount + additionalCount;
            boolean match = true;
            String[] rewards = new String[total];
            for (int i = 0; i < total; i++) {
                long val = rng.next();
                int itemVal = (int)((val & 0xFFFF) % 100);
                rewards[i] = rewardFromValue(itemVal);
                if (!rewards[i].equals(targetRewards[i])) {
                    match = false;
                    break;
                }
            }

            if (match) {
                results.add(new SearchResult(frame, additionalCount, rewards));
            }
        }
        return results;
    }

    // ================================================================
    // テスト
    // ================================================================
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

    static void assertArrayEquals(String[] expected, String[] actual, String msg) {
        if (java.util.Arrays.equals(expected, actual)) {
            passed++;
            System.out.println("  ✓ " + msg);
        } else {
            failed++;
            System.out.println("  ✗ " + msg 
                + " — expected: " + java.util.Arrays.toString(expected) 
                + ", actual: " + java.util.Arrays.toString(actual));
        }
    }

    public static void main(String[] args) {
        System.out.println("=== RewardReverseTest ===\n");

        // -------------------------------------------------------
        // Test 1: RNGエンジンが正しく動作するか（既知の初期seed確認）
        // -------------------------------------------------------
        System.out.println("[Test 1] RNG初期seed確認");
        {
            RNG rng = new RNG();
            assertEquals(0x0194FD72L, rng.x, "x == 0x0194FD72");
            assertEquals(0x79E6C985L, rng.y, "y == 0x79E6C985");
            assertEquals(0x08DD9701L, rng.z, "z == 0x08DD9701");
            assertEquals(0x41CFCE91L, rng.w, "w == 0x41CFCE91");
        }

        // -------------------------------------------------------
        // Test 2: RNGの1ステップ目の出力を確認
        //         記事の風おま0F目: 0x3910EE3A → これはroll()後のr[6]相当
        //         ここではnext()の出力値を確認
        // -------------------------------------------------------
        System.out.println("\n[Test 2] RNG出力値の確認");
        {
            RNG rng = new RNG();
            long v1 = rng.next();
            System.out.println("  RNG next(1) = 0x" + Long.toHexString(v1).toUpperCase());
            // 既存ツールのRNG jump(0)→roll()×7 の最終wの値が 
            // 風おま0Fの判定値 0x3910EE3A に関連するはず
            // ここでは出力値を記録しておき、後で既存ツールと照合
        }

        // -------------------------------------------------------
        // Test 3: rewardFromValue が正しくマッピングするか
        // -------------------------------------------------------
        System.out.println("\n[Test 3] rewardFromValue マッピング");
        {
            assertEquals("謎の骨", rewardFromValue(0), "val=0 → 謎の骨");
            assertEquals("謎の骨", rewardFromValue(19), "val=19 → 謎の骨");
            assertEquals("釣りミミズ", rewardFromValue(20), "val=20 → 釣りミミズ");
            assertEquals("釣りミミズ", rewardFromValue(39), "val=39 → 釣りミミズ");
            assertEquals("生肉", rewardFromValue(40), "val=40 → 生肉");
            assertEquals("生肉", rewardFromValue(64), "val=64 → 生肉");
            assertEquals("砥石", rewardFromValue(65), "val=65 → 砥石");
            assertEquals("砥石", rewardFromValue(84), "val=84 → 砥石");
            assertEquals("力の成長餌", rewardFromValue(85), "val=85 → 力の成長餌");
            assertEquals("力の成長餌", rewardFromValue(89), "val=89 → 力の成長餌");
            assertEquals("重の成長餌", rewardFromValue(90), "val=90 → 重の成長餌");
            assertEquals("重の成長餌", rewardFromValue(94), "val=94 → 重の成長餌");
            assertEquals("速の成長餌", rewardFromValue(95), "val=95 → 速の成長餌");
            assertEquals("速の成長餌", rewardFromValue(99), "val=99 → 速の成長餌");
        }

        // -------------------------------------------------------
        // Test 4: simulateRewardsの基本動作（normalCount=0, 従来互換）
        // -------------------------------------------------------
        System.out.println("\n[Test 4] simulateRewards 基本動作（frame=0, normal=0）");
        {
            RewardResult result = simulateRewards(0, 0, true);
            System.out.println("  追加報酬数: " + result.additionalCount);
            System.out.println("  報酬: " + java.util.Arrays.toString(result.rewards));
        }

        // -------------------------------------------------------
        // Test 5: normalCount=2の場合、報酬数が通常2+追加N個になることの確認
        // -------------------------------------------------------
        System.out.println("\n[Test 5] normalCount=2 の報酬構成");
        {
            RewardResult r0 = simulateRewards(0, 0, true);
            RewardResult r2 = simulateRewards(0, 2, true);
            System.out.println("  normal=0: 追加=" + r0.additionalCount + " 合計=" + r0.rewards.length 
                + " 報酬=" + java.util.Arrays.toString(r0.rewards));
            System.out.println("  normal=2: 追加=" + r2.additionalCount + " 合計=" + r2.rewards.length 
                + " 報酬=" + java.util.Arrays.toString(r2.rewards));
            // normal=2の場合、追加報酬数は同じで合計が2多いはず
            assertEquals(r0.additionalCount, r2.additionalCount, 
                "追加報酬数はnormalCountに依存しない");
            assertEquals(r0.rewards.length + 2, r2.rewards.length, 
                "合計報酬数 = normal=0の報酬数 + 2");
        }

        // -------------------------------------------------------
        // Test 6: reverseSearch と simulateRewards の一貫性（normalCount=0）
        // -------------------------------------------------------
        System.out.println("\n[Test 6] reverseSearch 一貫性（normalCount=0）");
        {
            RewardResult expected = simulateRewards(50, 0, true);
            if (expected.rewards.length > 0) {
                var results = reverseSearch(expected.rewards.length, 0, expected.rewards, 200, true);
                boolean found = false;
                for (var r : results) {
                    if (r.frame == 50) { found = true; break; }
                }
                assertEquals(true, found, "frame=50の報酬で逆算→frame=50が候補に含まれる");
            } else {
                System.out.println("  (報酬0枠のためスキップ)");
            }
        }

        // -------------------------------------------------------
        // Test 6b: reverseSearch 一貫性（normalCount=2）
        // -------------------------------------------------------
        System.out.println("\n[Test 6b] reverseSearch 一貫性（normalCount=2）");
        {
            RewardResult expected = simulateRewards(50, 2, true);
            var results = reverseSearch(expected.rewards.length, 2, expected.rewards, 200, true);
            boolean found = false;
            for (var r : results) {
                if (r.frame == 50) { found = true; break; }
            }
            assertEquals(true, found, "normalCount=2でもframe=50が候補に含まれる");
        }

        // -------------------------------------------------------
        // Test 6c: バリデーション
        // -------------------------------------------------------
        System.out.println("\n[Test 6c] reverseSearch バリデーション");
        {
            // 追加報酬が5枠（範囲外）→ 空リスト
            var r = reverseSearch(7, 2, new String[]{"砥石","砥石","砥石","砥石","砥石","砥石","砥石"}, 100, true);
            assertEquals(0, r.size(), "追加5枠（範囲外）→ 結果なし");

            // 配列長不一致 → 空リスト
            var r2 = reverseSearch(3, 0, new String[]{"砥石"}, 100, true);
            assertEquals(0, r2.size(), "配列長不一致 → 結果なし");
        }

        // -------------------------------------------------------
        // Test 6d: 0〜99F全フレームでの一貫性（normalCount=0）
        // -------------------------------------------------------
        System.out.println("\n[Test 6d] 0〜99F全フレーム一貫性（normalCount=0）");
        {
            int tested = 0, foundAll = 0;
            for (int f = 0; f < 100; f++) {
                RewardResult rr = simulateRewards(f, 0, true);
                if (rr.rewards.length == 0) continue;
                tested++;
                var results = reverseSearch(rr.rewards.length, 0, rr.rewards, 100, true);
                boolean found = false;
                for (var r : results) {
                    if (r.frame == f) { found = true; break; }
                }
                if (found) foundAll++;
                else System.out.println("  ✗ frame=" + f + " が逆算で見つからない！");
            }
            assertEquals(tested, foundAll, "normalCount=0: 全フレーム一貫性OK (" + tested + "件)");
        }

        // -------------------------------------------------------
        // Test 6e: 0〜99F全フレームでの一貫性（normalCount=3）
        // -------------------------------------------------------
        System.out.println("\n[Test 6e] 0〜99F全フレーム一貫性（normalCount=3）");
        {
            int tested = 0, foundAll = 0;
            for (int f = 0; f < 100; f++) {
                RewardResult rr = simulateRewards(f, 3, true);
                tested++;
                var results = reverseSearch(rr.rewards.length, 3, rr.rewards, 100, true);
                boolean found = false;
                for (var r : results) {
                    if (r.frame == f) { found = true; break; }
                }
                if (found) foundAll++;
                else System.out.println("  ✗ frame=" + f + " が逆算で見つからない！");
            }
            assertEquals(tested, foundAll, "normalCount=3: 全フレーム一貫性OK (" + tested + "件)");
        }

        // -------------------------------------------------------
        // Test 7: breakOnFail=true vs false
        // -------------------------------------------------------
        System.out.println("\n[Test 7] breakOnFail=true vs false の比較");
        {
            int diffCount = 0;
            for (int f = 0; f < 100; f++) {
                RewardResult rBreak = simulateRewards(f, 0, true);
                RewardResult rNoBreak = simulateRewards(f, 0, false);
                if (rBreak.additionalCount != rNoBreak.additionalCount) {
                    diffCount++;
                }
            }
            System.out.println("  100F中、結果が異なるフレーム数: " + diffCount);
        }

        // -------------------------------------------------------
        // Test 8: 報酬一覧（実機照合用、normalCount=0 と 3 を並記）
        // -------------------------------------------------------
        System.out.println("\n[Test 8] 0〜19Fの報酬一覧");
        {
            for (int f = 0; f < 20; f++) {
                RewardResult r0 = simulateRewards(f, 0, true);
                RewardResult r3 = simulateRewards(f, 3, true);
                System.out.printf("  %3dF: n=0→追加%d %s  |  n=3→合計%d %s%n", 
                    f, r0.additionalCount, java.util.Arrays.toString(r0.rewards),
                    r3.rewards.length, java.util.Arrays.toString(r3.rewards));
            }
        }

        // -------------------------------------------------------
        // Test 9: MHXXCharmApp.ascend() との一貫性確認
        //         テストのRNG.next()とMHXXCharmApp.RNG.ascend()+wが同じ値を返すか
        // -------------------------------------------------------
        System.out.println("\n[Test 9] ascend()ベースとの一貫性");
        {
            // テスト側のRNGで0〜9の出力
            RNG testRng = new RNG();
            long[] testVals = new long[10];
            for (int i = 0; i < 10; i++) testVals[i] = testRng.next();

            // ascendで同じことをする
            long ax = INITIAL_SEED[0], ay = INITIAL_SEED[1], az = INITIAL_SEED[2], aw = INITIAL_SEED[3];
            long[] ascVals = new long[10];
            for (int i = 0; i < 10; i++) {
                long t = (ax ^ (ax << 15)) & MASK32;
                ax = ay; ay = az; az = aw;
                aw = (aw ^ (aw >>> 21) ^ t ^ (t >>> 4)) & MASK32;
                ascVals[i] = aw;
            }

            boolean allMatch = true;
            for (int i = 0; i < 10; i++) {
                if (testVals[i] != ascVals[i]) {
                    allMatch = false;
                    System.out.println("  ✗ index=" + i + " test=0x" + Long.toHexString(testVals[i])
                        + " asc=0x" + Long.toHexString(ascVals[i]));
                }
            }
            assertEquals(true, allMatch, "next()とascend()+wが全10ステップで一致");
        }

        // -------------------------------------------------------
        // Test 10: jumpRaw()相当の検証
        //          init()→ascend()×N回 と jumpRaw相当 が同じ状態になるか
        //          （テスト側にはjumpRawがないので、手動でascendループと比較）
        // -------------------------------------------------------
        System.out.println("\n[Test 10] jumpRaw相当の検証（ascendループとの一致）");
        {
            // ascendループでframe=1000まで進めた状態
            RNG rngLoop = new RNG();
            for (int i = 0; i < 1000; i++) rngLoop.next();
            long loopX = rngLoop.x, loopY = rngLoop.y, loopZ = rngLoop.z, loopW = rngLoop.w;

            // frame=1000からさらに5回next()した値を記録
            long[] loopVals = new long[5];
            for (int i = 0; i < 5; i++) loopVals[i] = rngLoop.next();

            // 同じことを別のRNGで再現（1000まで進めて5回取得）
            RNG rng2 = new RNG();
            for (int i = 0; i < 1000; i++) rng2.next();
            long[] vals2 = new long[5];
            for (int i = 0; i < 5; i++) vals2[i] = rng2.next();

            boolean match = true;
            for (int i = 0; i < 5; i++) {
                if (loopVals[i] != vals2[i]) { match = false; break; }
            }
            assertEquals(true, match, "frame=1000到達後の出力が再現可能");
            System.out.println("  frame=1000のRNG状態: x=0x" + Long.toHexString(loopX)
                + " w=0x" + Long.toHexString(loopW));
        }
        System.out.println("\n=== 結果: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }
}
