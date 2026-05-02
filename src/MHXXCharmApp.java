import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * MHXX Charm Snipe Tool - Java Swing版 (改良版)
 * Monster Hunter XX お守りスナイプ支援ツール
 *
 * 元プロジェクト: https://github.com/apmnnn/mhxx-rng
 *
 * コンパイル: javac --release 21 -encoding UTF-8 *.java
 * 実行:       java MHXXCharmApp
 */
public class MHXXCharmApp extends JFrame {

    // ================================================================
    // Theme定数へのエイリアス（Theme.javaで定義）
    // ================================================================
    static final Color BG       = Theme.BG;
    static final Color BG2      = Theme.BG2;
    static final Color FG       = Theme.FG;
    static final Color ACCENT   = Theme.ACCENT;
    static final Color ACCENT_T = Theme.ACCENT_T;
    static final Color ACCENT2  = Theme.ACCENT2;
    static final Color SUCCESS  = Theme.SUCCESS;
    static final Color WARN     = Theme.WARN;
    static final Color BTN_BG   = Theme.BTN_BG;
    static final Color GREEN    = Theme.GREEN;
    static final Color DIM      = Theme.DIM;

    static final Font FONT_UI         = Theme.FONT_UI;
    static final Font FONT_UI_BOLD    = Theme.FONT_UI_BOLD;
    static final Font FONT_MONO       = Theme.FONT_MONO;
    static final Font FONT_MONO_SMALL = Theme.FONT_MONO_SMALL;
    static final Font FONT_LARGE      = Theme.FONT_LARGE;
    static final Font FONT_TIMER      = Theme.FONT_TIMER;
    static final Font FONT_SMALL      = Theme.FONT_SMALL;
    static final Font FONT_HEADER     = Theme.FONT_HEADER;

    // ================================================================
    // RNG Engine (MHXXRng.javaに分離、ここでは旧名RNGをエイリアスとして参照)
    // ================================================================
    // 注: 既存コードが `new RNG()` や `RNG rng = ...` と書いているため、
    //     MHXXRngをRNGという名前でインポート相当にできない。
    //     代わりにMHXXRngを継承した空のサブクラスを用意する。
    static class RNG extends MHXXRng {}

    // ================================================================
    // Charm Data Tables (CharmData.javaに分離)
    // ================================================================
    // CharmDataクラスはtop-level公開クラス (CharmData.java) へ移動した。


    // ================================================================
    // Skill Names (SkillNames.javaに分離、ここではエイリアス)
    // ================================================================
    static final String[] SKILL_NAMES       = SkillNames.SKILL_NAMES;
    static final String[] KIND_NAMES        = SkillNames.KIND_NAMES;
    static final String[] SKILL_CATEGORIES  = SkillNames.SKILL_CATEGORIES;
    static final int[][]  SKILL_CATEGORY_IDS= SkillNames.SKILL_CATEGORY_IDS;

    /** お守り種別のスキルリストとカテゴリの積集合を返す（SkillNamesへの転送） */
    static String[] getSkillsByCategoryFiltered(int[] skillIds, int categoryIndex) {
        return SkillNames.getSkillsByCategoryFiltered(skillIds, categoryIndex);
    }

    // ================================================================
    // Charm Result
    // ================================================================
    record Charm(String s1Name, int sp1, String s2Name, int sp2, int slot, int fill, int rare) {
        String s2Display() { return s2Name != null ? s2Name : "---"; }
        String sp2Display() { return s2Name != null ? String.valueOf(sp2) : "---"; }
    }

    // ================================================================
    // Charm Generation
    // ================================================================
    static Charm getCharm(RNG rng, CharmData data, int origin) {
        long[] r = rng.r;
        int id1 = (int)(r[0] % data.skill1.length);
        int id2 = (int)(r[3] % data.skill2.length);
        int s1max = data.sp1[id1][1];
        int s2max = data.sp2[id2][1];
        int skill1Id = data.skill1[id1];
        int tmp1 = (int)(r[1] % (data.sp1[id1][1] - data.sp1[id1][0] + 1)) + data.sp1[id1][0];
        boolean hasSk2 = (r[2] % 100) >= data.th;
        int skill2Id = -1, tmp2 = 0, effTmp2 = 0;
        long q5;
        if (hasSk2) {
            skill2Id = data.skill2[id2];
            if (origin == 1 && r[4] % 2 == 0) {
                long q4 = r[5]; q5 = r[6];
                tmp2 = (int)(q4 % (data.sp2[id2][0] + 1)) - data.sp2[id2][0];
            } else {
                long q4;
                if (origin == 1) { q4 = r[5]; q5 = r[6]; }
                else             { q4 = r[4]; q5 = r[5]; }
                tmp2 = (int)(q4 % data.sp2[id2][1]) + 1;
            }
            effTmp2 = tmp2;
            if (skill1Id == skill2Id || tmp2 < 0) effTmp2 = 0;
        } else {
            q5 = r[3];
        }
        int fill = (int)((long)tmp1 * s2max + (long)effTmp2 * s1max) * 10 / (s1max * s2max);
        int slotVal = data.getSlot(fill, (int)(q5 % 100));
        int rareVal = data.getRare(slotVal, fill);
        String s1Name = SKILL_NAMES[skill1Id];
        String s2Name = hasSk2 ? SKILL_NAMES[skill2Id] : null;
        return new Charm(s1Name, tmp1, s2Name, tmp2, slotVal, fill, rareVal);
    }

    // ================================================================
    // Search
    // ================================================================
    static int indexOf(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
        return -1;
    }

    static int skillNameToId(String name) {
        for (int i = 0; i < SKILL_NAMES.length; i++) if (SKILL_NAMES[i].equals(name)) return i;
        return -1;
    }

    interface SearchCallback { void onFound(long frame, Charm charm); }
    interface ProgressCallback { void onProgress(int done, int total); }

    static List<Object[]> searchCharm(CharmData data, String s1, int sp1v, String s2, int sp2v,
                                       int slotv, int origin, int maxFrames, boolean greaterMode,
                                       SearchCallback cb, ProgressCallback pcb,
                                       java.util.concurrent.atomic.AtomicBoolean cancel) {
        int sid1 = skillNameToId(s1);
        if (sid1 < 0) return Collections.emptyList();
        int id1 = indexOf(data.skill1, sid1);
        if (id1 < 0) return Collections.emptyList();

        boolean s2Any  = S2_ANY.equals(s2);
        boolean s2None = S2_NONE.equals(s2);
        int sid2 = -1, id2 = -1;
        if (!s2Any && !s2None) {
            sid2 = skillNameToId(s2);
            if (sid2 < 0) return Collections.emptyList();
            id2 = indexOf(data.skill2, sid2);
            if (id2 < 0) return Collections.emptyList();
        }

        int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (maxFrames < nThreads * 1000) nThreads = 1; // 小さい範囲はシングルスレッド

        int chunkSize = maxFrames / nThreads;
        java.util.concurrent.atomic.AtomicInteger globalDone = new java.util.concurrent.atomic.AtomicInteger(0);
        List<Object[]> allResults = Collections.synchronizedList(new ArrayList<>());
        ExecutorService exec = Executors.newFixedThreadPool(nThreads);
        List<Future<?>> futures = new ArrayList<>();

        final int fId1 = id1, fId2 = id2;
        final boolean fS2Any = s2Any, fS2None = s2None;

        for (int t = 0; t < nThreads; t++) {
            final int startFrame = t * chunkSize;
            final int endFrame = (t == nThreads - 1) ? maxFrames : (t + 1) * chunkSize;
            futures.add(exec.submit(() -> {
                RNG rng = new RNG();
                rng.jump(startFrame);
                int len1 = data.skill1.length;
                int len2 = data.skill2.length;
                int localCount = endFrame - startFrame;
                int reportInterval = Math.max(1, maxFrames / 100);

                for (int i = 0; i < localCount; i++) {
                    if (cancel != null && cancel.get()) break;
                    rng.roll();

                    if (rng.r[0] % len1 != fId1) {
                        int done = globalDone.incrementAndGet();
                        if (pcb != null && done % reportInterval == 0) pcb.onProgress(done, maxFrames);
                        continue;
                    }

                    boolean hasSk2 = (rng.r[2] % 100) >= data.th;
                    if (fS2None && hasSk2) {
                        int done = globalDone.incrementAndGet();
                        if (pcb != null && done % reportInterval == 0) pcb.onProgress(done, maxFrames);
                        continue;
                    }
                    if (!fS2Any && !fS2None) {
                        if (!hasSk2 || rng.r[3] % len2 != fId2) {
                            int done = globalDone.incrementAndGet();
                            if (pcb != null && done % reportInterval == 0) pcb.onProgress(done, maxFrames);
                            continue;
                        }
                    }

                    Charm c = getCharm(rng, data, origin);
                    boolean match;
                    if (fS2Any) {
                        match = greaterMode
                                ? (c.sp1() >= sp1v && c.slot() >= slotv)
                                : (c.sp1() == sp1v && c.slot() == slotv);
                    } else if (fS2None) {
                        match = greaterMode
                                ? (c.sp1() >= sp1v && c.slot() >= slotv && c.s2Name() == null)
                                : (c.sp1() == sp1v && c.slot() == slotv && c.s2Name() == null);
                    } else {
                        match = greaterMode
                                ? (c.sp1() >= sp1v && c.sp2() >= sp2v && c.slot() >= slotv)
                                : (c.sp1() == sp1v && c.sp2() == sp2v && c.slot() == slotv);
                    }
                    if (match) {
                        long frame = rng.f - 7;
                        allResults.add(new Object[]{frame, c});
                        if (cb != null) cb.onFound(frame, c);
                    }
                    int done = globalDone.incrementAndGet();
                    if (pcb != null && done % reportInterval == 0) pcb.onProgress(done, maxFrames);
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        exec.shutdown();
        if (pcb != null) pcb.onProgress(maxFrames, maxFrames);
        allResults.sort((a, b) -> Long.compare((Long)a[0], (Long)b[0]));
        return allResults;
    }

    static List<Object[]> getAround(CharmData data, long frame, int radius, int origin) {
        RNG rng = new RNG();
        rng.jump(frame - radius);
        List<Object[]> results = new ArrayList<>();
        for (int i = -radius; i <= radius; i++) {
            Charm c = getCharm(rng, data, origin);
            results.add(new Object[]{rng.f - 7, c});
            rng.roll();
        }
        return results;
    }

    // ================================================================
    // Melding
    // ================================================================
    static int randMelding(long num1, int num2) {
        num1 &= 0xFFFF;
        for (int i = 0; i < num2; i++) {
            if (num1 == 0) num1 = 1;
            num1 = num1 * 176 % 65363;
        }
        return (int)num1;
    }

    static int[] halcyonColors(long seed, int rank) {
        int[][] vals = {{3,1},{4,1},{4,2},{5,2}};
        int width = vals[rank-1][0], mn = vals[rank-1][1];
        int a = 1;
        int num = randMelding(seed, a) % width + mn; a++;
        int[] result = new int[num];
        for (int i = 0; i < num; i++) {
            while (randMelding(seed, a) % 100 >= 85) a++;
            int cv = randMelding(seed, a) % 100; a++;
            result[i] = cv < 10 ? 3 : cv < 55 ? 2 : 1;
        }
        return result;
    }

    static int[] jujuColors(long seed, int rank) {
        int[][] vals = {{2,0,1,1},{2,0,2,1},{2,1,2,1},{1,2,2,1}};
        int w1 = vals[rank-1][0], m1 = vals[rank-1][1], w2 = vals[rank-1][2], m2 = vals[rank-1][3];
        int n1 = randMelding(seed, 1) % w1 + m1;
        int n2 = randMelding(seed, 2) % w2 + m2;
        int[] result = new int[n1 + n2];
        Arrays.fill(result, 0, n1, 0);
        Arrays.fill(result, n1, n1 + n2, 1);
        return result;
    }

    static List<Object[]> simulateMelding(CharmData data, long frame, boolean isHalcyon, int rank) {
        RNG rng = new RNG();
        rng.jump(frame - 1);
        long seed = rng.r[0];
        int savedKind = data.kind;
        int[] colors = isHalcyon ? halcyonColors(seed, rank) : jujuColors(seed, rank);
        rng.roll();
        List<Object[]> results = new ArrayList<>();
        for (int ci : colors) {
            if (ci == 0) data.setBlue(); else if (ci == 1) data.setRed(); else if (ci == 2) data.setYellow();
            Charm c = getCharm(rng, data, 0);
            results.add(new Object[]{KIND_NAMES[ci], c});
            int advance = (rng.r[2] % 100 < data.th) ? 4 : 6;
            for (int j = 0; j < advance; j++) rng.roll();
        }
        if (savedKind == 0) data.setBlue(); else if (savedKind == 1) data.setRed(); else if (savedKind == 2) data.setYellow();
        return results;
    }

    // ================================================================
    // Utility
    // ================================================================
    static String framesToTime(long frames) {
        long d = frames / 2592000;
        long h = (frames % 2592000) / 108000;
        long m = (frames % 108000) / 1800;
        long s = (frames % 1800) / 30;
        long f = frames % 30;
        if (d > 0) return String.format("%d日 %d時間 %d分 %d秒 %dF", d, h, m, s, f);
        if (h > 0) return String.format("%d時間 %d分 %d秒 %dF", h, m, s, f);
        if (m > 0) return String.format("%d分 %d秒 %dF", m, s, f);
        return String.format("%d秒 %dF", s, f);
    }

    // ================================================================
    // Reward Reverse (報酬逆算)
    // ================================================================
    static final String[] REWARD_ITEMS = {
        "謎の骨", "釣りミミズ", "生肉", "砥石",
        "力の成長餌", "重の成長餌", "速の成長餌"
    };

    static String rewardFromValue(int val) {
        if (val < 20) return "謎の骨";
        if (val < 40) return "釣りミミズ";
        if (val < 65) return "生肉";
        if (val < 85) return "砥石";
        if (val < 90) return "力の成長餌";
        if (val < 95) return "重の成長餌";
        return "速の成長餌";
    }

    record RewardSearchResult(long frame, String[] rewards) {}

    /**
     * 報酬の個数と並びからフレームを逆算する。
     *
     * @param totalCount   報酬合計個数（通常＋追加）
     * @param normalCount  通常報酬の固定枠数
     * @param targetItems  各枠のアイテム名（長さ == totalCount）
     * @param maxFrames    検索範囲
     * @param cb           ヒット時コールバック（未使用、null可）
     * @param pcb          進捗コールバック
     * @param cancel       キャンセルフラグ
     * @return 候補フレームのリスト
     */
    static List<RewardSearchResult> reverseSearchRewards(
            int totalCount, int normalCount, String[] targetItems, int maxFrames,
            int addRewardThreshold,
            SearchCallback cb, ProgressCallback pcb,
            java.util.concurrent.atomic.AtomicBoolean cancel) {

        int expectedAdditional = totalCount - normalCount;
        if (expectedAdditional < 0 || expectedAdditional > 4) return Collections.emptyList();
        if (totalCount < 1) return Collections.emptyList();
        if (targetItems.length != totalCount) return Collections.emptyList();

        int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (maxFrames < nThreads * 1000) nThreads = 1;

        int chunkSize = maxFrames / nThreads;
        java.util.concurrent.atomic.AtomicInteger globalDone = new java.util.concurrent.atomic.AtomicInteger(0);
        List<RewardSearchResult> allResults = Collections.synchronizedList(new ArrayList<>());
        ExecutorService exec = Executors.newFixedThreadPool(nThreads);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < nThreads; t++) {
            final int startFrame = t * chunkSize;
            final int endFrame = (t == nThreads - 1) ? maxFrames : (t + 1) * chunkSize;
            futures.add(exec.submit(() -> {
                RNG rng = new RNG();
                rng.jumpRaw(startFrame);

                int localCount = endFrame - startFrame;
                int reportInterval = Math.max(1, maxFrames / 100);

                for (int i = 0; i < localCount; i++) {
                    if (cancel != null && cancel.get()) break;
                    long currentFrame = startFrame + i;

                    // RNG状態を退避
                    long sx = rng.x, sy = rng.y, sz = rng.z, sw = rng.w;

                    // Step1: 追加報酬数の判定
                    int addCount = 0;
                    for (int j = 0; j < 4; j++) {
                        rng.ascend();
                        if (rng.w % 32 < addRewardThreshold) {
                            addCount++;
                        } else {
                            break; // breakOnFail=true（仮定）
                        }
                    }

                    boolean match = false;
                    String[] rewards = null;
                    if (addCount == expectedAdditional) {
                        // RNG状態を復元して再実行
                        rng.x = sx; rng.y = sy; rng.z = sz; rng.w = sw;
                        // 追加報酬判定を再消費
                        for (int j = 0; j < 4; j++) {
                            rng.ascend();
                            if (rng.w % 32 < addRewardThreshold) {
                                // continue
                            } else {
                                break;
                            }
                        }
                        // Step2: 報酬内容チェック（通常報酬 + 追加報酬）
                        match = true;
                        rewards = new String[totalCount];
                        for (int j = 0; j < totalCount; j++) {
                            rng.ascend();
                            int itemVal = (int)((rng.w & 0xFFFF) % 100);
                            rewards[j] = rewardFromValue(itemVal);
                            if (!rewards[j].equals(targetItems[j])) {
                                match = false;
                                break;
                            }
                        }
                    }

                    if (match) {
                        allResults.add(new RewardSearchResult(currentFrame, rewards));
                    }

                    // RNG状態を復元して1フレーム分だけ進める
                    rng.x = sx; rng.y = sy; rng.z = sz; rng.w = sw;
                    rng.ascend();

                    int done = globalDone.incrementAndGet();
                    if (pcb != null && done % reportInterval == 0) pcb.onProgress(done, maxFrames);
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        exec.shutdown();
        if (pcb != null) pcb.onProgress(maxFrames, maxFrames);
        allResults.sort((a, b) -> Long.compare(a.frame(), b.frame()));
        return allResults;
    }

    // ================================================================
    // Famous Charms
    // ================================================================
    static final Object[][] FAMOUS_CHARMS = {
        // {フレーム, 第1スキル, SP1, 第2スキル, SP2, スロット, 待ち時間, 当たりF範囲, 種類}
        // ===== 超短時間(~5分) =====
        {1716L,"斬れ味",5,"痛撃",4,3,"0m 57s 6F",2,"風化"},
        {4723L,"連撃",4,"痛撃",5,3,"2m 37s 13F",1,"風化"},
        {5401L,"溜め短縮",6,"納刀",6,3,"3m 0s 1F",2,"風化"},
        {7934L,"貫通弾強化",3,"会心強化",5,3,"4m 24s 14F",3,"風化"},
        // ===== 短時間(5~30分) =====
        {10818L,"散弾強化",5,"達人",7,2,"6m 0s 18F",2,"風化"},
        {17041L,"通常弾強化",5,"達人",7,3,"9m 28s 1F",2,"風化"},
        {21357L,"抜刀会心",4,"達人",10,3,"11m 51s 27F",1,"風化"},
        {30502L,"痛撃",5,"溜め短縮",4,3,"16m 56s 22F",2,"風化"},
        {38925L,"砲術",10,"攻撃",7,3,"21m 37s 15F",2,"風化"},
        {44170L,"鈍器",5,"攻撃",10,3,"24m 31s 20F",2,"風化"},
        {62669L,"痛撃",6,"達人",10,0,"34m 48s 29F",2,"風化"},
        // ===== 中時間(30分~2時間) =====
        {101840L,"痛撃",6,"達人",10,2,"56m 34s 20F",2,"風化"},
        {108840L,"連撃",5,"龍属性攻撃",13,3,"1h 0m 28s",2,"風化"},
        {124895L,"溜め短縮",6,"攻撃",10,3,"1h 9m 23s",3,"風化"},
        {157283L,"属性会心",4,"属性攻撃",4,3,"1h 27m 22s",2,"風化"},
        {182715L,"痛撃",3,"会心強化",5,3,"1h 41m 30s",1,"風化"},
        // ===== 長時間(2時間~) =====
        {258586L,"連撃",5,"反動",6,3,"2h 23m 39s",2,"風化"},
        {260008L,"闘魂",5,"斬れ味",7,3,"2h 24m 26s",2,"風化"},
        {296260L,"痛撃",6,"達人",10,3,"2h 44m 35s",2,"風化"},
        {310605L,"属性攻撃",4,"火属性攻撃",12,2,"2h 52m 33s",2,"風化"},
        {435105L,"連撃",4,"射法",4,3,"4h 1m 43s",2,"風化"},
        {635712L,"裏会心",4,"達人",10,3,"5h 53m 12s",2,"風化"},
        {1115738L,"連撃",5,"射法",4,3,"10h 19m 51s",2,"風化"},
        {1333720L,"斬れ味",7,"会心強化",5,3,"12h 20m 57s",3,"風化"},
        {1728985L,"重撃弾強化",6,"達人",10,3,"16h 0m 32s",2,"風化"},
        {1838208L,"痛撃",4,"攻撃",10,3,"17h 1m 13s",2,"風化"},
        {2359514L,"貫通弾強化",5,"達人",10,3,"21h 50m 50s",1,"風化"},
        {2768432L,"痛撃",5,"砲術",10,3,"1d 1h 38m",2,"風化"},
        {4351902L,"痛撃",6,"会心強化",5,3,"1d 16h 17m",2,"風化"},
        {7016153L,"痛撃",6,"斬れ味",7,3,"2d 16h 57m",2,"風化"},
        {15069320L,"連撃",5,"達人",10,3,"5d 19h 31m",1,"風化"},
        {58161487L,"貫通弾強化",6,"達人",10,3,"22d 10h 31m",2,"風化"},
    };

    // ================================================================
    // GUI Fields
    // ================================================================
    CharmData data = new CharmData();
    java.util.concurrent.atomic.AtomicBoolean cancelFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
    JLabel statusLabel;
    JTabbedPane tabs;
    JProgressBar progressBar;

    // Search tab
    JComboBox<String> searchKind, searchOrigin, searchMode, searchS1, searchS2, searchSlot;
    JComboBox<String> searchSp1, searchSp2, searchCat;
    JTextField searchRange;
    DefaultTableModel searchModel;
    JTable searchTable;
    JButton searchBtn;

    // Around tab
    JTextField aroundFrame, aroundRadius;
    JComboBox<String> aroundOrigin;
    DefaultTableModel aroundModel;
    JTable aroundTable;

    // Melding tab
    JTextField meldFrame, calcTarget, calcBase;
    JComboBox<String> meldType, meldRank;
    DefaultTableModel meldModel;
    JLabel calcResult;

    // Timer tab - ラップタイマー + カウントダウン
    JLabel timerMainLabel, timerMainFramesLabel;
    JLabel timerCountdownLabel, timerCountdownFramesLabel;
    JPanel timerMainBox, timerCountdownBox;
    JButton timerActionBtn, timerLapBtn, timerResetBtn;
    JLabel timerTargetLabel, timerGuideLabel;
    DefaultTableModel lapModel;
    int timerState = 0; // 0=停止, 1=計測中
    long timerStartMs = 0;
    long lastLapMs = 0;
    int lapCount = 0;
    double targetSec = 0; // 目標時間（秒）。0=未設定
    javax.swing.Timer swingTimer;

    // Famous tab
    DefaultTableModel famousModel;
    JTable famousTable;

    // Arduino tab
    JTextField arduinoTarget, arduinoC, arduinoFc;
    JButton arduinoCalcBtn;

    // Reward Reverse tab (報酬逆算)
    JComboBox<String> rewardTotalCount, rewardNormalCount;
    JComboBox<String>[] rewardItems;
    JLabel[] rewardLabels;
    JTextField rewardSearchRange;
    JTextField rewardThreshold;  // 追加報酬閾値（通常22、激運/幸運で変動）

    // 調合スナイプ系（保存・復元のためインスタンスフィールド化）
    JTextField comboCountsField;       // 累積弾数 or 個数列
    JTextField comboTargetFField;      // 目標F（調合スナイプタブ）
    JTextField comboNcField;           // Continue回数（調合Arduinoタブ）
    JTextField comboNumField;          // 調合回数（目安）
    JTextField comboDownKeysField;     // Lv2弾までの↓数
    DefaultTableModel rewardModel;
    JTable rewardTable;
    JLabel rewardCalcResult;

    // Appraise Timer (鑑定タイマー、報酬逆算タブ内)
    JTextField appraiseTargetFrame, appraiseBaseFrame;
    JLabel appraiseRewardEndLabel, appraiseTargetTimeLabel;
    JLabel appraiseElapsedLabel, appraiseRemainLabel, appraiseStatusLabel;
    JButton appraiseStartBtn, appraiseStopBtn, appraiseResetBtn;
    javax.swing.Timer appraiseSwingTimer;
    long appraiseStartMs = 0;
    boolean appraiseRunning = false;
    double appraiseTargetSec = 0; // 目標時刻（秒）
    boolean appraiseBlink = false; // 目標到達後の点滅フラグ

    // ================================================================
    // Constructor
    // ================================================================
    public MHXXCharmApp() {
        super("MHXX Charm Snipe Tool");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1120, 850);
        setMinimumSize(new Dimension(960, 700));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());

        data.setBlue();

        // Header
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ACCENT2);
        header.setPreferredSize(new Dimension(0, 52));
        JLabel title = new JLabel("  MHXX Charm Snipe Tool", SwingConstants.CENTER);
        title.setFont(FONT_HEADER);
        title.setForeground(Color.WHITE);
        header.add(title, BorderLayout.CENTER);
        // ACCENTカラーのアクセントラインを下部に
        JPanel accentLine = new JPanel();
        accentLine.setBackground(ACCENT);
        accentLine.setPreferredSize(new Dimension(0, 3));
        header.add(accentLine, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        // Tabs
        tabs = new JTabbedPane();
        tabs.setBackground(BG);
        tabs.setForeground(FG);
        tabs.setFont(FONT_UI_BOLD);
        tabs.addTab(" お守り検索", buildSearchTab());
        tabs.addTab(" 周辺表示", buildAroundTab());
        tabs.addTab(" 報酬逆算", buildRewardReverseTab());
        tabs.addTab(" 調合スナイプ", buildComboSnipeTab());
        tabs.addTab(" 調合Arduino", buildComboArduinoTab());
        tabs.addTab(" 錬金シミュ", buildMeldingTab());
        tabs.addTab(" タイマー", buildTimerTab());
        tabs.addTab(" Arduino", buildArduinoTab());
        tabs.addTab(" クエスト周回", buildQuestLoopTab());
        tabs.addTab(" キャリブレーション", buildCalibrationTab());
        tabs.addTab(" 有名お守り", buildFamousTab());
        add(tabs, BorderLayout.CENTER);

        // Status bar with progress
        JPanel statusBar = new JPanel(new BorderLayout(8, 0));
        statusBar.setBackground(BG.darker());
        statusBar.setPreferredSize(new Dimension(0, 28));
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        statusLabel = new JLabel("Ready");
        statusLabel.setFont(FONT_SMALL);
        statusLabel.setForeground(new Color(0x88, 0x88, 0x88));
        statusBar.add(statusLabel, BorderLayout.CENTER);
        progressBar = new JProgressBar(0, 100);
        progressBar.setPreferredSize(new Dimension(200, 18));
        progressBar.setVisible(false);
        progressBar.setStringPainted(true);
        progressBar.setFont(FONT_SMALL);
        statusBar.add(progressBar, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);

        // Global key bindings: Escape to cancel search
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelSearch");
        getRootPane().getActionMap().put("cancelSearch", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                cancelFlag.set(true);
                statusLabel.setText("検索を中止しました");
            }
        });

        // 設定の復元
        loadSettings();

        // クローズ時に設定保存
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { saveSettings(); }
        });
    }

    // ================================================================
    // Settings Persistence
    // ================================================================
    static final String SETTINGS_FILE = "mhxx_charm_settings.properties";

    void saveSettings() {
        java.util.Properties props = new java.util.Properties();
        try {
            props.setProperty("search.kind", String.valueOf(searchKind.getSelectedIndex()));
            props.setProperty("search.origin", String.valueOf(searchOrigin.getSelectedIndex()));
            props.setProperty("search.mode", String.valueOf(searchMode.getSelectedIndex()));
            props.setProperty("search.s1", getComboText(searchS1));
            props.setProperty("search.s2", getComboText(searchS2));
            props.setProperty("search.sp1", Objects.toString(searchSp1.getSelectedItem(), ""));
            props.setProperty("search.sp2", Objects.toString(searchSp2.getSelectedItem(), ""));
            props.setProperty("search.slot", String.valueOf(searchSlot.getSelectedIndex()));
            props.setProperty("search.range", searchRange.getText());
            props.setProperty("around.origin", String.valueOf(aroundOrigin.getSelectedIndex()));
            props.setProperty("around.radius", aroundRadius.getText());
            props.setProperty("meld.type", String.valueOf(meldType.getSelectedIndex()));
            props.setProperty("meld.rank", String.valueOf(meldRank.getSelectedIndex()));
            props.setProperty("tab.selected", String.valueOf(tabs.getSelectedIndex()));
            props.setProperty("arduino.c", arduinoC.getText());
            props.setProperty("arduino.fc", arduinoFc.getText());
            props.setProperty("arduino.target", arduinoTarget.getText());

            // 周辺表示・報酬逆算・調合スナイプ系
            if (aroundFrame != null) props.setProperty("around.frame", aroundFrame.getText());
            if (rewardThreshold != null) props.setProperty("reward.threshold", rewardThreshold.getText());
            if (comboCountsField != null) props.setProperty("combo.counts", comboCountsField.getText());
            if (comboTargetFField != null) props.setProperty("combo.targetF", comboTargetFField.getText());
            if (comboNcField != null) props.setProperty("combo.nc", comboNcField.getText());
            if (comboNumField != null) props.setProperty("combo.num", comboNumField.getText());
            if (comboDownKeysField != null) props.setProperty("combo.downKeys", comboDownKeysField.getText());

            props.setProperty("window.width", String.valueOf(getWidth()));
            props.setProperty("window.height", String.valueOf(getHeight()));
            props.setProperty("window.x", String.valueOf(getX()));
            props.setProperty("window.y", String.valueOf(getY()));

            File f = new File(System.getProperty("user.dir"), SETTINGS_FILE);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                props.store(fos, "MHXX Charm Snipe Tool Settings");
            }
        } catch (Exception ignored) {}
    }

    void loadSettings() {
        File f = new File(System.getProperty("user.dir"), SETTINGS_FILE);
        if (!f.exists()) return;
        java.util.Properties props = new java.util.Properties();
        try (FileInputStream fis = new FileInputStream(f)) {
            props.load(fis);
        } catch (Exception e) { return; }
        try {
            int kindIdx = Integer.parseInt(props.getProperty("search.kind", "0"));
            searchKind.setSelectedIndex(kindIdx);
            onKindChange(); // スキルリスト更新

            safeSetIndex(searchOrigin, props.getProperty("search.origin", "0"));
            safeSetIndex(searchMode, props.getProperty("search.mode", "0"));
            safeSetItem(searchS1, props.getProperty("search.s1", ""));
            updateSp1Range(); // SP1の範囲を更新してから値を復元
            safeSetItem(searchS1, props.getProperty("search.s1", "")); // 再設定（updateSp1Rangeで変わりうる）
            safeSetItem(searchSp1, props.getProperty("search.sp1", ""));
            safeSetItem(searchS2, props.getProperty("search.s2", ""));
            updateSp2State(); // SP2の範囲を更新してから値を復元
            safeSetItem(searchSp2, props.getProperty("search.sp2", ""));
            safeSetIndex(searchSlot, props.getProperty("search.slot", "3"));
            searchRange.setText(props.getProperty("search.range", "100000"));
            safeSetIndex(aroundOrigin, props.getProperty("around.origin", "0"));
            aroundRadius.setText(props.getProperty("around.radius", "10"));
            safeSetIndex(meldType, props.getProperty("meld.type", "0"));
            safeSetIndex(meldRank, props.getProperty("meld.rank", "0"));
            safeSetIndex(tabs, props.getProperty("tab.selected", "0"));
            updateSp2State();

            // Arduino設定の復元
            String savedC = props.getProperty("arduino.c", "");
            if (!savedC.isEmpty()) arduinoC.setText(savedC);
            String savedFc = props.getProperty("arduino.fc", "");
            if (!savedFc.isEmpty()) arduinoFc.setText(savedFc);
            String savedTarget = props.getProperty("arduino.target", "");
            if (!savedTarget.isEmpty()) arduinoTarget.setText(savedTarget);

            // 周辺表示・報酬逆算・調合スナイプ系の復元
            String savedAroundFrame = props.getProperty("around.frame", "");
            if (aroundFrame != null && !savedAroundFrame.isEmpty()) aroundFrame.setText(savedAroundFrame);
            String savedRewardThreshold = props.getProperty("reward.threshold", "");
            if (rewardThreshold != null && !savedRewardThreshold.isEmpty()) rewardThreshold.setText(savedRewardThreshold);
            String savedComboCounts = props.getProperty("combo.counts", "");
            if (comboCountsField != null && !savedComboCounts.isEmpty()) comboCountsField.setText(savedComboCounts);
            String savedComboTargetF = props.getProperty("combo.targetF", "");
            if (comboTargetFField != null && !savedComboTargetF.isEmpty()) comboTargetFField.setText(savedComboTargetF);
            String savedComboNc = props.getProperty("combo.nc", "");
            if (comboNcField != null && !savedComboNc.isEmpty()) comboNcField.setText(savedComboNc);
            String savedComboNum = props.getProperty("combo.num", "");
            if (comboNumField != null && !savedComboNum.isEmpty()) comboNumField.setText(savedComboNum);
            String savedComboDownKeys = props.getProperty("combo.downKeys", "");
            if (comboDownKeysField != null && !savedComboDownKeys.isEmpty()) comboDownKeysField.setText(savedComboDownKeys);

            int w = Integer.parseInt(props.getProperty("window.width", "1080"));
            int h = Integer.parseInt(props.getProperty("window.height", "800"));
            int x = Integer.parseInt(props.getProperty("window.x", "-1"));
            int y = Integer.parseInt(props.getProperty("window.y", "-1"));
            setSize(w, h);
            if (x >= 0 && y >= 0) setLocation(x, y);
        } catch (Exception ignored) {}
    }

    void safeSetIndex(JComboBox<?> cb, String val) {
        try { int idx = Integer.parseInt(val); if (idx >= 0 && idx < cb.getItemCount()) cb.setSelectedIndex(idx); }
        catch (Exception ignored) {}
    }

    void safeSetIndex(JTabbedPane tp, String val) {
        try { int idx = Integer.parseInt(val); if (idx >= 0 && idx < tp.getTabCount()) tp.setSelectedIndex(idx); }
        catch (Exception ignored) {}
    }

    void safeSetItem(JComboBox<String> cb, String val) {
        if (val == null || val.isEmpty()) return;
        for (int i = 0; i < cb.getItemCount(); i++) {
            if (val.equals(cb.getItemAt(i))) {
                cb.setSelectedItem(val);
                if (cb.isEditable()) {
                    ((JTextField) cb.getEditor().getEditorComponent()).setText(val);
                }
                return;
            }
        }
    }

    /** editableなComboBoxから確実にテキスト値を取得する */
    String getComboText(JComboBox<String> cb) {
        if (cb.isEditable()) {
            JTextField editor = (JTextField) cb.getEditor().getEditorComponent();
            String text = editor.getText();
            if (text != null && !text.isEmpty()) return text;
        }
        Object sel = cb.getSelectedItem();
        return sel != null ? sel.toString() : "";
    }

    // ================================================================
    // Helper Methods
    // ================================================================
    JTextField makeField(String text, int cols) {
        JTextField f = new JTextField(text, cols);
        f.setBackground(BG2);
        f.setForeground(FG);
        f.setCaretColor(FG);
        f.setFont(FONT_UI);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x33,0x33,0x55)),
                BorderFactory.createEmptyBorder(6,8,6,8)));
        f.setPreferredSize(new Dimension(f.getPreferredSize().width, 38));
        f.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                f.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(SUCCESS),
                        BorderFactory.createEmptyBorder(6,8,6,8)));
            }
            @Override public void focusLost(FocusEvent e) {
                f.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(0x33,0x33,0x55)),
                        BorderFactory.createEmptyBorder(6,8,6,8)));
            }
        });
        return f;
    }

    JComboBox<String> makeCombo(String[] items) {
        JComboBox<String> cb = new JComboBox<>(items);
        cb.setFont(FONT_UI);
        cb.setBackground(BG2);
        cb.setForeground(FG);
        // 内容に応じた幅を計算（最低100px）
        int maxWidth = 80;
        FontMetrics fm = cb.getFontMetrics(FONT_UI);
        for (String s : items) {
            int w = fm.stringWidth(s);
            if (w > maxWidth) maxWidth = w;
        }
        cb.setPreferredSize(new Dimension(maxWidth + 50, 38));
        cb.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (!cb.isEnabled()) {
                    setBackground(BG2.darker());
                    setForeground(DIM);
                } else if (isSelected) {
                    setBackground(ACCENT2);
                    setForeground(Color.WHITE);
                } else {
                    setBackground(BG2);
                    setForeground(FG);
                }
                setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                return this;
            }
        });
        return cb;
    }

    /**
     * インクリメンタル検索付きComboBox（IME対応版）。
     * Enterキー確定時にフィルタ実行。ポップアップ表示時にも適用。
     * IME変換中はモデルに触らないので日本語入力が安全。
     */
    JComboBox<String> makeFilterCombo(String[] allItems) {
        // allItemsを保持するためにラッパーを使う
        final String[][] itemsHolder = {allItems};

        JComboBox<String> cb = new JComboBox<>(allItems);
        cb.setEditable(true);
        cb.setFont(FONT_UI);
        cb.setBackground(BG2);
        cb.setForeground(FG);
        cb.putClientProperty("allItems", itemsHolder);

        // 幅計算
        int maxWidth = 80;
        FontMetrics fm = cb.getFontMetrics(FONT_UI);
        for (String s : allItems) { int w = fm.stringWidth(s); if (w > maxWidth) maxWidth = w; }
        cb.setPreferredSize(new Dimension(maxWidth + 60, 38));
        cb.setMaximumRowCount(12);

        // レンダラー（ダークテーマ・disabled対応）
        cb.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (!cb.isEnabled()) {
                    setBackground(BG2.darker());
                    setForeground(DIM);
                } else if (isSelected) {
                    setBackground(ACCENT2);
                    setForeground(Color.WHITE);
                } else {
                    setBackground(BG2);
                    setForeground(FG);
                }
                setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                return this;
            }
        });

        // エディタ（テキスト入力欄）のスタイル
        JTextField editor = (JTextField) cb.getEditor().getEditorComponent();
        editor.setBackground(BG2);
        editor.setForeground(FG);
        editor.setCaretColor(FG);
        editor.setFont(FONT_UI);

        final boolean[] isFiltering = {false};

        // ドロップダウンから選択された時: フルリストに復元＋エディタ同期
        cb.addActionListener(e -> {
            if (isFiltering[0]) return;
            if (!"comboBoxChanged".equals(e.getActionCommand())) return;
            Object sel = cb.getSelectedItem();
            if (sel == null) return;
            String selectedText = sel.toString();
            // フルリストに含まれるか確認（フィルタ結果からの選択なら復元）
            String[][] holder = (String[][]) cb.getClientProperty("allItems");
            if (holder == null) return;
            String[] items = holder[0];
            boolean valid = false;
            for (String s : items) { if (s.equals(selectedText)) { valid = true; break; } }
            if (valid) {
                isFiltering[0] = true;
                try {
                    cb.setModel(new DefaultComboBoxModel<>(items));
                    cb.setSelectedItem(selectedText);
                    editor.setText(selectedText);
                } finally {
                    isFiltering[0] = false;
                }
            }
        });

        // Enterキーでフィルタ実行
        editor.addActionListener(e -> {
            if (isFiltering[0]) return;
            isFiltering[0] = true;
            try {
                String input = editor.getText();
                String[][] holder = (String[][]) cb.getClientProperty("allItems");
                String[] items = holder[0];
                applyFilter(cb, items, input);
            } finally {
                isFiltering[0] = false;
            }
        });

        // フォーカスアウト時: テキストのバリデーションのみ（モデルはフルリストに復元）
        editor.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                if (isFiltering[0]) return;
                isFiltering[0] = true;
                try {
                    String[][] holder = (String[][]) cb.getClientProperty("allItems");
                    String[] items = holder[0];

                    // 現在のエディタテキストを取得
                    String text = editor.getText();
                    if (text == null) text = "";

                    // 完全一致チェック
                    boolean found = false;
                    String resolvedText = text;
                    for (String s : items) {
                        if (s.equals(text)) { found = true; break; }
                    }
                    // 完全一致しない場合、部分一致で補正（空文字なら先頭にしない）
                    if (!found && !text.isEmpty() && items.length > 0) {
                        String lower = text.toLowerCase();
                        for (String s : items) {
                            if (s.toLowerCase().contains(lower)) {
                                resolvedText = s;
                                break;
                            }
                        }
                    }
                    // テキストを確定してから、フルリストでモデルを復元
                    String finalText = resolvedText;
                    cb.setModel(new DefaultComboBoxModel<>(items));
                    cb.setSelectedItem(finalText);
                    editor.setText(finalText);
                } finally {
                    isFiltering[0] = false;
                }
            }
        });

        return cb;
    }

    /** フィルタを適用してポップアップを開く */
    void applyFilter(JComboBox<String> cb, String[] allItems, String input) {
        JTextField editor = (JTextField) cb.getEditor().getEditorComponent();
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        if (input == null || input.isEmpty()) {
            for (String s : allItems) model.addElement(s);
        } else {
            String lower = input.toLowerCase();
            for (String s : allItems) {
                if (s.toLowerCase().contains(lower)) model.addElement(s);
            }
        }
        cb.setModel(model);
        editor.setText(input);
        if (model.getSize() > 0) {
            cb.showPopup();
        }
        // 候補が1つだけなら自動選択
        if (model.getSize() == 1) {
            cb.setSelectedIndex(0);
            editor.setText(model.getElementAt(0));
        }
    }

    /** makeFilterComboの全アイテムを差し替える（種別変更時など） */
    void updateFilterCombo(JComboBox<String> cb, String[] newItems) {
        String[][] holder = (String[][]) cb.getClientProperty("allItems");
        if (holder != null) holder[0] = newItems;
        // 以前の選択値を保持
        String prev = Objects.toString(cb.getSelectedItem(), "");
        cb.setModel(new DefaultComboBoxModel<>(newItems));
        // 以前の値が新リストに存在すれば選択を維持
        boolean restored = false;
        for (String s : newItems) {
            if (s.equals(prev)) { cb.setSelectedItem(prev); restored = true; break; }
        }
        if (!restored && newItems.length > 0) {
            cb.setSelectedIndex(0);
        }
        // エディタのテキストも同期
        if (cb.isEditable()) {
            JTextField editor = (JTextField) cb.getEditor().getEditorComponent();
            editor.setText(Objects.toString(cb.getSelectedItem(), ""));
        }
        // 幅再計算
        FontMetrics fm = cb.getFontMetrics(FONT_UI);
        int maxWidth = 80;
        for (String s : newItems) { int w = fm.stringWidth(s); if (w > maxWidth) maxWidth = w; }
        cb.setPreferredSize(new Dimension(maxWidth + 60, 38));
        cb.revalidate();
    }

    /** JScrollPaneのスクロール速度を快適にする */
    void setupScrollSpeed(JScrollPane sp) {
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.getHorizontalScrollBar().setUnitIncrement(16);
    }

    JButton makeButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setFont(FONT_UI_BOLD);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMargin(new Insets(8, 18, 8, 18));
        b.setPreferredSize(new Dimension(b.getPreferredSize().width + 28, 40));
        b.addMouseListener(new MouseAdapter() {
            Color savedBg;
            @Override public void mouseEntered(MouseEvent e) {
                if (b.isEnabled()) {
                    savedBg = b.getBackground();
                    b.setBackground(savedBg.brighter());
                }
            }
            @Override public void mouseExited(MouseEvent e) {
                if (savedBg != null) b.setBackground(savedBg);
            }
        });
        return b;
    }

    JTable makeTable(DefaultTableModel model) {
        JTable t = new JTable(model) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        t.setBackground(BG2);
        t.setForeground(FG);
        t.setGridColor(new Color(0x33,0x33,0x55));
        t.setFont(FONT_UI);
        t.setRowHeight(34);
        t.setSelectionBackground(ACCENT2);
        t.setSelectionForeground(Color.WHITE);
        t.getTableHeader().setBackground(ACCENT2);
        t.getTableHeader().setForeground(Color.WHITE);
        t.getTableHeader().setFont(FONT_UI_BOLD);
        t.setAutoCreateRowSorter(false);
        javax.swing.table.TableRowSorter<DefaultTableModel> sorter = new javax.swing.table.TableRowSorter<>(model) {
            @Override public java.util.Comparator<?> getComparator(int column) {
                return (java.util.Comparator<Object>) (o1, o2) -> {
                    if (o1 instanceof Number && o2 instanceof Number) {
                        return Double.compare(((Number) o1).doubleValue(), ((Number) o2).doubleValue());
                    }
                    return String.valueOf(o1).compareTo(String.valueOf(o2));
                };
            }
        };
        t.setRowSorter(sorter);
        DefaultTableCellRenderer ctr = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                if (!isSelected) {
                    setBackground(row % 2 == 0 ? BG2 : new Color(0x1c, 0x28, 0x45));
                    setForeground(FG);
                }
                return this;
            }
        };
        for (int i = 0; i < t.getColumnCount(); i++) t.getColumnModel().getColumn(i).setCellRenderer(ctr);
        return t;
    }

    JPanel titled(String titleText) {
        JPanel p = new JPanel();
        p.setBackground(BG);
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ACCENT2),
                titleText, TitledBorder.LEFT, TitledBorder.TOP, FONT_UI_BOLD, ACCENT_T));
        return p;
    }

    JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_UI);
        l.setForeground(FG);
        return l;
    }

    // ================================================================
    // Search Tab
    // ================================================================
    JPanel buildSearchTab() {
        JPanel tab = new JPanel(new BorderLayout(8,8));
        tab.setBackground(BG);
        tab.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        JPanel settings = titled("検索設定");
        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));

        // Row 1
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r1.setOpaque(false);
        r1.add(label("お守り種別:"));
        searchKind = makeCombo(new String[]{"風化したお守り","古びたお守り","光るお守り"});
        searchKind.setToolTipText("お守りの種類を選択");
        searchKind.addActionListener(e -> onKindChange());
        r1.add(searchKind);
        r1.add(label("原産地:"));
        searchOrigin = makeCombo(new String[]{"マカ錬金","クエスト（炭鉱）"});
        r1.add(searchOrigin);
        r1.add(label("検索モード:"));
        searchMode = makeCombo(new String[]{"完全一致","以上検索"});
        searchMode.setToolTipText("完全一致=値が一致, 以上検索=指定値以上");
        r1.add(searchMode);
        settings.add(r1);

        // Row 2: カテゴリ + スキル選択
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r2.setOpaque(false);
        r2.add(label("カテゴリ:"));
        searchCat = makeCombo(SKILL_CATEGORIES);
        searchCat.setToolTipText("スキルカテゴリで絞り込み");
        searchCat.addActionListener(e -> onSearchCategoryChange());
        r2.add(searchCat);
        r2.add(label("第1スキル:"));
        searchS1 = makeFilterCombo(data.getSkill1Names());
        searchS1.addActionListener(e -> {
            // ドロップダウン選択時にエディタテキストを同期
            Object sel = searchS1.getSelectedItem();
            if (sel != null) {
                ((JTextField) searchS1.getEditor().getEditorComponent()).setText(sel.toString());
            }
            updateSp1Range();
        });
        r2.add(searchS1);
        r2.add(label("SP:"));
        searchSp1 = makeCombo(new String[]{"1"});
        searchSp1.setToolTipText("スキルポイント値（スキルに応じた範囲）");
        r2.add(searchSp1);
        r2.add(label("第2スキル:"));
        searchS2 = makeFilterCombo(getSkill2NamesWithSpecial());
        searchS2.addActionListener(e -> {
            Object sel = searchS2.getSelectedItem();
            if (sel != null) {
                ((JTextField) searchS2.getEditor().getEditorComponent()).setText(sel.toString());
            }
            updateSp2State();
        });
        r2.add(searchS2);
        r2.add(label("SP:"));
        searchSp2 = makeCombo(new String[]{"1"});
        searchSp2.setToolTipText("スキルポイント値（スキルに応じた範囲）");
        r2.add(searchSp2);
        settings.add(r2);

        // Row 3: スロット
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r3.setOpaque(false);
        r3.add(label("スロット:"));
        searchSlot = makeCombo(new String[]{"0","1","2","3"});
        searchSlot.setSelectedItem("3");
        r3.add(searchSlot);
        settings.add(r3);

        // 初期SP範囲を設定
        updateSp1Range();
        updateSp2State();

        // Row 4: 検索範囲・ボタン
        JPanel r4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r4.setOpaque(false);
        r4.add(label("検索範囲:"));
        searchRange = makeField("100000", 10);
        searchRange.setToolTipText("検索するフレーム数 (大きいほど時間がかかる)");
        r4.add(searchRange);
        r4.add(label("フレーム"));
        searchBtn = makeButton("▶ 検索開始", ACCENT);
        searchBtn.setToolTipText("Enterキーでも実行可能");
        searchBtn.addActionListener(e -> startSearch());
        r4.add(searchBtn);
        JButton cancelBtn = makeButton("■ 中止", BTN_BG);
        cancelBtn.setToolTipText("Escキーでも中止可能");
        cancelBtn.addActionListener(e -> { cancelFlag.set(true); statusLabel.setText("検索を中止しました"); });
        r4.add(cancelBtn);
        // ★ CSVエクスポートボタン
        JButton exportBtn = makeButton("CSV保存", BTN_BG);
        exportBtn.setToolTipText("検索結果をCSVファイルに保存");
        exportBtn.addActionListener(e -> exportCSV(searchModel,
                new String[]{"フレーム","第1スキル","SP1","第2スキル","SP2","スロット","待ち時間","レア度"}));
        r4.add(exportBtn);
        settings.add(r4);

        // Enter key to search (テキストフィールドのみ)
        searchRange.addActionListener(e -> startSearch());

        tab.add(settings, BorderLayout.NORTH);

        // Results
        searchModel = new DefaultTableModel(
                new String[]{"フレーム","第1スキル","SP1","第2スキル","SP2","スロット","待ち時間","レア度"}, 0);
        searchTable = makeTable(searchModel);
        // ★ ダブルクリックで周辺表示へジャンプ
        searchTable.setToolTipText("ダブルクリックで周辺表示 / 右クリックでArduinoコード生成");
        searchTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = searchTable.getSelectedRow();
                    if (row >= 0) {
                        int modelRow = searchTable.convertRowIndexToModel(row);
                        Object val = searchModel.getValueAt(modelRow, 0);
                        aroundFrame.setText(val.toString());
                        tabs.setSelectedIndex(1);
                        showAround();
                    }
                }
            }
        });
        addArduinoContextMenu(searchTable, searchModel, 0);
        JScrollPane sp = new JScrollPane(searchTable);
        setupScrollSpeed(sp);
        sp.getViewport().setBackground(BG2);
        JPanel resultPanel = titled("検索結果 (ダブルクリック=周辺表示 / 右クリック=Arduino・周辺表示)");
        resultPanel.setLayout(new BorderLayout());
        resultPanel.add(sp, BorderLayout.CENTER);
        tab.add(resultPanel, BorderLayout.CENTER);

        return tab;
    }

    static final String S2_NONE = "（なし）";
    static final String S2_ANY  = "（任意）";

    String[] getSkill2NamesWithSpecial() {
        String[] base = data.getSkill2Names();
        String[] result = new String[base.length + 2];
        result[0] = S2_NONE;
        result[1] = S2_ANY;
        System.arraycopy(base, 0, result, 2, base.length);
        return result;
    }

    void onKindChange() {
        String k = (String)searchKind.getSelectedItem();
        if (k.contains("風化")) data.setBlue();
        else if (k.contains("古びた")) data.setRed();
        else data.setYellow();
        onSearchCategoryChange(); // カテゴリフィルタも再適用
    }

    void onSearchCategoryChange() {
        int catIdx = searchCat.getSelectedIndex();
        if (catIdx < 0) catIdx = 0;
        // 第1スキル: お守り種別のスキルリスト × カテゴリの積集合
        String[] s1Items = getSkillsByCategoryFiltered(data.skill1, catIdx);
        updateFilterCombo(searchS1, s1Items.length > 0 ? s1Items : new String[]{"(該当なし)"});
        // 第2スキル: 同様 + なし/任意
        String[] s2Base = getSkillsByCategoryFiltered(data.skill2, catIdx);
        String[] s2Items = new String[s2Base.length + 2];
        s2Items[0] = S2_NONE;
        s2Items[1] = S2_ANY;
        System.arraycopy(s2Base, 0, s2Items, 2, s2Base.length);
        updateFilterCombo(searchS2, s2Items);
        updateSp1Range();
        updateSp2State();
    }

    void updateSp2State() {
        String sel = getComboText(searchS2);
        boolean needSp2 = !sel.isEmpty() && !S2_NONE.equals(sel) && !S2_ANY.equals(sel);
        searchSp2.setEnabled(needSp2);
        if (!needSp2) {
            searchSp2.setModel(new DefaultComboBoxModel<>(new String[]{"0"}));
            searchSp2.setSelectedIndex(0);
            searchSp2.setBackground(BG2.darker());
            searchSp2.setForeground(DIM);
        } else {
            searchSp2.setBackground(BG2);
            searchSp2.setForeground(FG);
            updateSp2Range();
        }
    }

    /** 第1スキル選択に応じてSP1コンボの選択肢を更新 */
    void updateSp1Range() {
        String s1Name = getComboText(searchS1);
        int sid = skillNameToId(s1Name);
        if (sid < 0) { searchSp1.setModel(new DefaultComboBoxModel<>(new String[]{"1"})); return; }
        int idx = indexOf(data.skill1, sid);
        if (idx < 0) { searchSp1.setModel(new DefaultComboBoxModel<>(new String[]{"1"})); return; }
        int min = data.sp1[idx][0];
        int max = data.sp1[idx][1];
        String prev = Objects.toString(searchSp1.getSelectedItem(), "");
        String[] vals = new String[max - min + 1];
        for (int i = 0; i < vals.length; i++) vals[i] = String.valueOf(min + i);
        searchSp1.setModel(new DefaultComboBoxModel<>(vals));
        // 以前の値を保持（範囲内なら）
        safeSetItem(searchSp1, prev);
        if (searchSp1.getSelectedIndex() < 0 && vals.length > 0)
            searchSp1.setSelectedIndex(vals.length - 1); // デフォルトは最大値
    }

    /** 第2スキル選択に応じてSP2コンボの選択肢を更新 */
    void updateSp2Range() {
        String s2Name = getComboText(searchS2);
        if (S2_NONE.equals(s2Name) || S2_ANY.equals(s2Name)) return;
        int sid = skillNameToId(s2Name);
        if (sid < 0) { searchSp2.setModel(new DefaultComboBoxModel<>(new String[]{"1"})); return; }
        int idx = indexOf(data.skill2, sid);
        if (idx < 0) { searchSp2.setModel(new DefaultComboBoxModel<>(new String[]{"1"})); return; }
        // SP2は正の値の場合: 1〜sp2[idx][1]、負の場合もあるが検索は正の範囲で十分
        int max = data.sp2[idx][1];
        int min = 1;
        String prev = Objects.toString(searchSp2.getSelectedItem(), "");
        String[] vals = new String[max - min + 1];
        for (int i = 0; i < vals.length; i++) vals[i] = String.valueOf(min + i);
        searchSp2.setModel(new DefaultComboBoxModel<>(vals));
        safeSetItem(searchSp2, prev);
        if (searchSp2.getSelectedIndex() < 0 && vals.length > 0)
            searchSp2.setSelectedIndex(vals.length - 1); // デフォルトは最大値
    }

    void resizeCombo(JComboBox<String> cb) {
        FontMetrics fm = cb.getFontMetrics(FONT_UI);
        int maxWidth = 80;
        for (int i = 0; i < cb.getItemCount(); i++) {
            int w = fm.stringWidth(cb.getItemAt(i));
            if (w > maxWidth) maxWidth = w;
        }
        cb.setPreferredSize(new Dimension(maxWidth + 50, 38));
        cb.revalidate();
    }

    void startSearch() {
        cancelFlag.set(false);
        searchModel.setRowCount(0);
        int sp1v, sp2v, slotv, maxF;
        String s2 = getComboText(searchS2);
        boolean s2Special = S2_ANY.equals(s2) || S2_NONE.equals(s2);
        try {
            sp1v = Integer.parseInt(Objects.toString(searchSp1.getSelectedItem(), "1"));
            sp2v = s2Special ? 0 : Integer.parseInt(Objects.toString(searchSp2.getSelectedItem(), "1"));
            slotv = Integer.parseInt((String)searchSlot.getSelectedItem());
            maxF = Integer.parseInt(searchRange.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "数値を正しく入力してください", "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String s1 = getComboText(searchS1);
        // スキル名バリデーション
        if (skillNameToId(s1) < 0) {
            JOptionPane.showMessageDialog(this, "第1スキル名が不正です: " + s1, "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!s2Special && skillNameToId(s2) < 0) {
            JOptionPane.showMessageDialog(this, "第2スキル名が不正です: " + s2, "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }
        int origin = ((String)searchOrigin.getSelectedItem()).contains("マカ") ? 0 : 1;
        boolean greater = "以上検索".equals(searchMode.getSelectedItem());

        CharmData d = new CharmData();
        if (data.kind == 0) d.setBlue(); else if (data.kind == 1) d.setRed(); else if (data.kind == 2) d.setYellow();

        searchBtn.setEnabled(false);
        statusLabel.setText("検索中...");
        progressBar.setValue(0);
        progressBar.setVisible(true);

        Thread.ofVirtual().start(() -> {
            long t0 = System.currentTimeMillis();
            searchCharm(d, s1, sp1v, s2, sp2v, slotv, origin, maxF, greater,
                    (frame, charm) -> SwingUtilities.invokeLater(() ->
                            searchModel.addRow(new Object[]{frame, charm.s1Name(), charm.sp1(),
                                    charm.s2Display(), charm.sp2Display(), charm.slot(),
                                    framesToTime(frame), "R" + charm.rare()})
                    ),
                    (done, total) -> SwingUtilities.invokeLater(() -> {
                        int pct = (int)((long)done * 100 / total);
                        progressBar.setValue(pct);
                        statusLabel.setText(String.format("検索中... %d%% (%d件ヒット)", pct, searchModel.getRowCount()));
                    }),
                    cancelFlag);
            double elapsed = (System.currentTimeMillis() - t0) / 1000.0;
            SwingUtilities.invokeLater(() -> {
                searchBtn.setEnabled(true);
                progressBar.setVisible(false);
                statusLabel.setText(String.format("検索完了: %d件 (%.2f秒)", searchModel.getRowCount(), elapsed));
            });
        });
    }

    // ★ CSVエクスポート
    void exportCSV(DefaultTableModel model, String[] headers) {
        if (model.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this, "エクスポートするデータがありません", "情報", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("mhxx_search_result.csv"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(fc.getSelectedFile()), "UTF-8"))) {
                pw.println("\uFEFF" + String.join(",", headers)); // BOM for Excel
                for (int r = 0; r < model.getRowCount(); r++) {
                    StringBuilder sb = new StringBuilder();
                    for (int c = 0; c < model.getColumnCount(); c++) {
                        if (c > 0) sb.append(",");
                        sb.append(model.getValueAt(r, c));
                    }
                    pw.println(sb.toString());
                }
                statusLabel.setText("CSV保存完了: " + fc.getSelectedFile().getName());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "保存に失敗: " + ex.getMessage(), "エラー", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ================================================================
    // Around Tab
    // ================================================================
    JPanel buildAroundTab() {
        JPanel tab = new JPanel(new BorderLayout(8,8));
        tab.setBackground(BG);
        tab.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        JPanel settings = titled("周辺お守り表示");
        settings.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 8));
        settings.add(label("フレーム位置:"));
        aroundFrame = makeField("", 10);
        aroundFrame.addActionListener(e -> showAround());
        settings.add(aroundFrame);
        settings.add(label("前後範囲:"));
        aroundRadius = makeField("10", 6);
        aroundRadius.addActionListener(e -> showAround());
        settings.add(aroundRadius);
        settings.add(label("原産地:"));
        aroundOrigin = makeCombo(new String[]{"マカ錬金","クエスト（炭鉱）"});
        settings.add(aroundOrigin);
        JButton btn = makeButton("▶ 表示", ACCENT);
        btn.addActionListener(e -> showAround());
        settings.add(btn);
        tab.add(settings, BorderLayout.NORTH);

        aroundModel = new DefaultTableModel(
                new String[]{"差分","フレーム","第1スキル","SP1","第2スキル","SP2","スロット","レア度"}, 0);
        aroundTable = makeTable(aroundModel);

        // ロードブレ±30Fハイライト用レンダラー
        aroundTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                if (!isSelected) {
                    String diff = (String) table.getModel().getValueAt(
                            table.convertRowIndexToModel(row), 0);
                    boolean isCenter = diff.contains("★");
                    boolean isInBreRange = false;
                    if (!isCenter) {
                        try {
                            int d = Integer.parseInt(diff.replace("+","").trim());
                            isInBreRange = Math.abs(d) <= 30;
                        } catch (NumberFormatException ignored) {}
                    }
                    if (isCenter) {
                        setBackground(new Color(0xe9, 0x45, 0x60, 0x40));
                        setForeground(new Color(0xff, 0xcc, 0x00));
                    } else if (isInBreRange) {
                        setBackground(new Color(0x00, 0xd2, 0xff, 0x18));
                        setForeground(SUCCESS);
                    } else {
                        setBackground(row % 2 == 0 ? BG2 : new Color(0x1c, 0x28, 0x45));
                        setForeground(FG);
                    }
                }
                return this;
            }
        });

        JScrollPane sp = new JScrollPane(aroundTable);
        setupScrollSpeed(sp);
        sp.getViewport().setBackground(BG2);
        JPanel rp = titled("結果 (水色=ロードブレ±30F圏内 / 黄色=中心フレーム)");
        rp.setLayout(new BorderLayout());
        rp.add(sp, BorderLayout.CENTER);
        tab.add(rp, BorderLayout.CENTER);

        return tab;
    }

    void showAround() {
        aroundModel.setRowCount(0);
        long frame; int radius;
        try {
            frame = Long.parseLong(aroundFrame.getText().trim());
            radius = Integer.parseInt(aroundRadius.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "数値を正しく入力してください", "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }
        int origin = ((String)aroundOrigin.getSelectedItem()).contains("マカ") ? 0 : 1;
        CharmData d = new CharmData();
        if (data.kind == 0) d.setBlue(); else if (data.kind == 1) d.setRed(); else if (data.kind == 2) d.setYellow();

        statusLabel.setText("周辺お守りを計算中...");
        Thread.ofVirtual().start(() -> {
            List<Object[]> results = getAround(d, frame, radius, origin);
            SwingUtilities.invokeLater(() -> {
                for (Object[] ro : results) {
                    long f = (Long) ro[0];
                    Charm c = (Charm) ro[1];
                    long offset = f - frame;
                    String offs = offset == 0 ? "★ 0" : String.format("%+d", offset);
                    aroundModel.addRow(new Object[]{offs, f, c.s1Name(), c.sp1(),
                            c.s2Display(), c.sp2Display(), c.slot(), "R" + c.rare()});
                }
                statusLabel.setText("周辺表示完了: " + results.size() + "件");
            });
        });
    }

    // ================================================================
    // Reward Reverse Tab (報酬逆算)
    // ================================================================
    @SuppressWarnings("unchecked")
    JPanel buildRewardReverseTab() {
        JPanel tab = new JPanel(new BorderLayout(8,8));
        tab.setBackground(BG);
        tab.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        JPanel settings = titled("クエスト報酬からフレーム逆算");
        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));

        // 説明
        JPanel descRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        descRow.setOpaque(false);
        JLabel desc = new JLabel(
            "<html>クエスト報酬画面に表示されたアイテムの合計個数と並びを入力し、現在フレームを特定します。<br>" +
            "通常報酬枠数は実機で確認して設定してください（不明なら0で試行）。</html>");
        desc.setFont(FONT_SMALL);
        desc.setForeground(DIM);
        descRow.add(desc);
        settings.add(descRow);

        // Row 1: 通常報酬枠数 + 合計報酬個数
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r1.setOpaque(false);
        r1.add(label("通常報酬枠数:"));
        rewardNormalCount = makeCombo(new String[]{"0","1","2","3","4","5","6"});
        rewardNormalCount.setToolTipText("通常報酬の固定枠数（実機確認で確定、不明なら0）");
        r1.add(rewardNormalCount);
        r1.add(label("   報酬合計個数:"));
        rewardTotalCount = makeCombo(new String[]{"1","2","3","4","5","6","7","8","9","10"});
        rewardTotalCount.setToolTipText("報酬画面に表示されたアイテムの合計個数（通常＋追加）");
        r1.add(rewardTotalCount);
        settings.add(r1);

        // Row 2: アイテム選択（最大10枠分）
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r2.setOpaque(false);
        rewardItems = new JComboBox[10];
        rewardLabels = new JLabel[10];
        for (int i = 0; i < 10; i++) {
            rewardLabels[i] = label((i + 1) + ":");
            r2.add(rewardLabels[i]);
            rewardItems[i] = makeCombo(REWARD_ITEMS);
            r2.add(rewardItems[i]);
        }
        settings.add(r2);

        // 個数変更時にコンボボックスの表示/非表示を切り替え
        Runnable updateRewardVisibility = () -> {
            int total = Integer.parseInt((String)rewardTotalCount.getSelectedItem());
            for (int i = 0; i < 10; i++) {
                boolean visible = i < total;
                rewardLabels[i].setVisible(visible);
                rewardItems[i].setVisible(visible);
            }
            r2.revalidate();
            r2.repaint();
        };
        rewardTotalCount.addActionListener(e -> updateRewardVisibility.run());
        // 初期状態（1個）
        updateRewardVisibility.run();

        // Row 3: 検索範囲・ボタン
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r3.setOpaque(false);
        r3.add(label("検索範囲:"));
        rewardSearchRange = makeField("10000000", 12);
        rewardSearchRange.setToolTipText("検索するフレーム数");
        r3.add(rewardSearchRange);
        r3.add(label("フレーム"));
        r3.add(label("  追加報酬閾値:"));
        rewardThreshold = makeField("22", 4);
        rewardThreshold.setToolTipText(
            "<html>追加報酬判定: rng%32 &lt; 閾値 で追加報酬+1。<br>" +
            "通常=22 / 激運・幸運時は変動（具体値は実機検証が必要）</html>");
        r3.add(rewardThreshold);
        JButton searchBtn = makeButton("▶ 逆算開始", ACCENT);
        searchBtn.addActionListener(e -> startRewardSearch());
        r3.add(searchBtn);
        JButton cancelBtn = makeButton("■ 中止", BTN_BG);
        cancelBtn.addActionListener(e -> { cancelFlag.set(true); statusLabel.setText("逆算を中止しました"); });
        r3.add(cancelBtn);
        settings.add(r3);

        // Row 4: 結果サマリ
        JPanel r4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r4.setOpaque(false);
        rewardCalcResult = new JLabel("");
        rewardCalcResult.setFont(FONT_LARGE);
        rewardCalcResult.setForeground(SUCCESS);
        r4.add(rewardCalcResult);
        settings.add(r4);

        tab.add(settings, BorderLayout.NORTH);

        // 結果テーブル
        rewardModel = new DefaultTableModel(
                new String[]{"フレーム","報酬(通常+追加)","追加枠数","待ち時間","→鑑定お守り(クエスト産)"}, 0);
        rewardTable = makeTable(rewardModel);
        rewardTable.setToolTipText("ダブルクリックで周辺表示 / 右クリックでArduinoコード生成");
        rewardTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = rewardTable.getSelectedRow();
                    if (row >= 0) {
                        int modelRow = rewardTable.convertRowIndexToModel(row);
                        Object val = rewardModel.getValueAt(modelRow, 0);
                        aroundFrame.setText(val.toString());
                        aroundOrigin.setSelectedIndex(1); // クエスト産
                        tabs.setSelectedIndex(1);
                        showAround();
                    }
                }
            }
        });
        addArduinoContextMenu(rewardTable, rewardModel, 0);

        // 行選択時に基準フレームを自動セット
        rewardTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = rewardTable.getSelectedRow();
            if (row < 0) return;
            int modelRow = rewardTable.convertRowIndexToModel(row);
            Object val = rewardModel.getValueAt(modelRow, 0);
            if (val != null) {
                appraiseBaseFrame.setText(val.toString());
                updateAppraiseCalc();
            }
        });

        JScrollPane sp = new JScrollPane(rewardTable);
        setupScrollSpeed(sp);
        sp.getViewport().setBackground(BG2);
        JPanel rp = titled("候補フレーム（報酬パターンが一致するフレーム）");
        rp.setLayout(new BorderLayout());
        rp.add(sp, BorderLayout.CENTER);

        // 鑑定タイマー（結果テーブル下に配置）
        JPanel appraisePanel = buildAppraiseTimerPanel();

        // CENTERに結果テーブル、SOUTHに鑑定タイマー
        JPanel centerSouth = new JPanel(new BorderLayout(0, 8));
        centerSouth.setOpaque(false);
        centerSouth.add(rp, BorderLayout.CENTER);
        centerSouth.add(appraisePanel, BorderLayout.SOUTH);
        tab.add(centerSouth, BorderLayout.CENTER);

        return tab;
    }

    /** 鑑定タイマーパネル（報酬逆算タブ内のSOUTHに配置） */
    JPanel buildAppraiseTimerPanel() {
        JPanel panel = titled("鑑定タイマー");
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        // Row 1: フレーム入力
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r1.setOpaque(false);
        r1.add(label("目標フレーム:"));
        appraiseTargetFrame = makeField("", 12);
        appraiseTargetFrame.setToolTipText("欲しいお守りのフレーム番号");
        r1.add(appraiseTargetFrame);
        r1.add(label("基準フレーム:"));
        appraiseBaseFrame = makeField("", 12);
        appraiseBaseFrame.setToolTipText("報酬逆算結果から選択した候補フレーム（行選択で自動セット）");
        r1.add(appraiseBaseFrame);
        JButton calcBtn = makeButton("計算", BTN_BG);
        calcBtn.addActionListener(e -> updateAppraiseCalc());
        r1.add(calcBtn);
        panel.add(r1);

        appraiseTargetFrame.addActionListener(e -> updateAppraiseCalc());
        appraiseBaseFrame.addActionListener(e -> updateAppraiseCalc());

        // Row 2: 計算結果
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r2.setOpaque(false);
        r2.add(label("報酬生成終了:"));
        appraiseRewardEndLabel = new JLabel("-");
        appraiseRewardEndLabel.setFont(FONT_LARGE);
        appraiseRewardEndLabel.setForeground(FG);
        r2.add(appraiseRewardEndLabel);
        r2.add(Box.createHorizontalStrut(20));
        r2.add(label("目標時刻:"));
        appraiseTargetTimeLabel = new JLabel("-");
        appraiseTargetTimeLabel.setFont(FONT_LARGE);
        appraiseTargetTimeLabel.setForeground(WARN);
        r2.add(appraiseTargetTimeLabel);
        panel.add(r2);

        // Row 3: 大きな時刻表示
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 4));
        r3.setOpaque(false);
        appraiseElapsedLabel = new JLabel("0:00.00");
        appraiseElapsedLabel.setFont(new Font(FONT_MONO.getFamily(), Font.BOLD, 56));
        appraiseElapsedLabel.setForeground(FG);
        r3.add(appraiseElapsedLabel);
        panel.add(r3);

        // Row 4: 状態ラベル + 残り時間
        JPanel r4 = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 4));
        r4.setOpaque(false);
        appraiseStatusLabel = new JLabel("停止中");
        appraiseStatusLabel.setFont(FONT_LARGE);
        appraiseStatusLabel.setForeground(DIM);
        r4.add(appraiseStatusLabel);
        appraiseRemainLabel = new JLabel("");
        appraiseRemainLabel.setFont(FONT_LARGE);
        appraiseRemainLabel.setForeground(DIM);
        r4.add(appraiseRemainLabel);
        panel.add(r4);

        // Row 5: ボタン
        JPanel r5 = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 4));
        r5.setOpaque(false);
        appraiseStartBtn = makeButton("▶ 起動 [Space]", GREEN);
        appraiseStartBtn.setToolTipText("報酬画面が表示された瞬間に押す");
        appraiseStartBtn.addActionListener(e -> appraiseStart());
        r5.add(appraiseStartBtn);
        appraiseStopBtn = makeButton("■ 停止", ACCENT);
        appraiseStopBtn.setEnabled(false);
        appraiseStopBtn.addActionListener(e -> appraiseStop());
        r5.add(appraiseStopBtn);
        appraiseResetBtn = makeButton("↻ リセット", BTN_BG);
        appraiseResetBtn.addActionListener(e -> appraiseReset());
        r5.add(appraiseResetBtn);
        panel.add(r5);

        // Swing Timer（33ms間隔で更新）
        appraiseSwingTimer = new javax.swing.Timer(33, e -> updateAppraiseDisplay());

        return panel;
    }

    /** 目標フレーム・基準フレームから計算を更新 */
    void updateAppraiseCalc() {
        try {
            long target = Long.parseLong(appraiseTargetFrame.getText().trim());
            long base = Long.parseLong(appraiseBaseFrame.getText().trim());

            // 報酬生成終了フレーム = base + addJudge + totalCount
            int totalCount = Integer.parseInt((String)rewardTotalCount.getSelectedItem());
            int normalCount = Integer.parseInt((String)rewardNormalCount.getSelectedItem());
            int additionalExpected = totalCount - normalCount;
            int addJudge = (additionalExpected < 4) ? additionalExpected + 1 : 4;
            long rewardEnd = base + addJudge + totalCount;

            appraiseRewardEndLabel.setText(String.valueOf(rewardEnd));

            long waitFrames = target - rewardEnd;
            if (waitFrames < 0) {
                appraiseTargetTimeLabel.setText("目標が過去のフレーム（設定NG）");
                appraiseTargetTimeLabel.setForeground(ACCENT_T);
                appraiseTargetSec = -1;
                return;
            }
            appraiseTargetSec = waitFrames / 30.0;
            appraiseTargetTimeLabel.setText(String.format("%s  (%.2f秒 / %d F)",
                formatElapsed(appraiseTargetSec), appraiseTargetSec, waitFrames));
            appraiseTargetTimeLabel.setForeground(WARN);
        } catch (NumberFormatException ex) {
            appraiseRewardEndLabel.setText("-");
            appraiseTargetTimeLabel.setText("数値を入力してください");
            appraiseTargetTimeLabel.setForeground(DIM);
            appraiseTargetSec = 0;
        }
    }

    /** 鑑定タイマー開始 */
    void appraiseStart() {
        if (appraiseTargetSec <= 0) {
            JOptionPane.showMessageDialog(this,
                "先に目標フレーム・基準フレームを正しく設定してください。",
                "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }
        appraiseStartMs = System.currentTimeMillis();
        appraiseRunning = true;
        appraiseBlink = false;
        appraiseSwingTimer.start();
        appraiseStartBtn.setEnabled(false);
        appraiseStopBtn.setEnabled(true);
        appraiseStatusLabel.setText("カウントアップ中…");
        appraiseStatusLabel.setForeground(SUCCESS);
    }

    void appraiseStop() {
        appraiseRunning = false;
        appraiseSwingTimer.stop();
        appraiseStartBtn.setEnabled(true);
        appraiseStopBtn.setEnabled(false);
        appraiseStatusLabel.setText("停止");
        appraiseStatusLabel.setForeground(DIM);
    }

    void appraiseReset() {
        appraiseStop();
        appraiseElapsedLabel.setText("0:00.00");
        appraiseElapsedLabel.setForeground(FG);
        appraiseRemainLabel.setText("");
        appraiseStatusLabel.setText("停止中");
        appraiseStatusLabel.setForeground(DIM);
    }

    /** 33ms毎に呼ばれて表示を更新 */
    void updateAppraiseDisplay() {
        if (!appraiseRunning) return;
        double elapsed = (System.currentTimeMillis() - appraiseStartMs) / 1000.0;
        appraiseElapsedLabel.setText(formatElapsed(elapsed));

        double remain = appraiseTargetSec - elapsed;
        if (remain > 0) {
            appraiseRemainLabel.setText(String.format("残り %.2f秒", remain));
            appraiseRemainLabel.setForeground(DIM);

            // 目標時刻3秒前から色を警告色に
            if (remain < 3.0) {
                appraiseElapsedLabel.setForeground(WARN);
            } else {
                appraiseElapsedLabel.setForeground(FG);
            }
        } else {
            // 目標時刻到達・超過
            appraiseRemainLabel.setText(String.format("+%.2f秒 超過", -remain));
            appraiseRemainLabel.setForeground(ACCENT_T);
            appraiseStatusLabel.setText("▶▶▶ 今鑑定！ ◀◀◀");
            appraiseStatusLabel.setForeground(ACCENT_T);

            // 点滅表示（500ms周期）
            appraiseBlink = ((System.currentTimeMillis() / 250) % 2 == 0);
            appraiseElapsedLabel.setForeground(appraiseBlink ? ACCENT_T : WARN);
        }
    }

    /** 秒数を "M:SS.ss" 形式にフォーマット */
    static String formatElapsed(double seconds) {
        int m = (int)(seconds / 60);
        double s = seconds - m * 60;
        return String.format("%d:%05.2f", m, s);
    }

    void startRewardSearch() {
        cancelFlag.set(false);
        rewardModel.setRowCount(0);

        int totalCount, normalCount, maxF;
        int threshold;
        try {
            totalCount = Integer.parseInt((String)rewardTotalCount.getSelectedItem());
            normalCount = Integer.parseInt((String)rewardNormalCount.getSelectedItem());
            maxF = Integer.parseInt(rewardSearchRange.getText().trim());
            threshold = Integer.parseInt(rewardThreshold.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "数値を正しく入力してください", "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int additionalExpected = totalCount - normalCount;
        if (additionalExpected < 0 || additionalExpected > 4) {
            JOptionPane.showMessageDialog(this,
                "追加報酬枠数（合計 - 通常）が0〜4の範囲になるように設定してください。\n" +
                "現在: 合計" + totalCount + " - 通常" + normalCount + " = " + additionalExpected,
                "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String[] targetItems = new String[totalCount];
        for (int i = 0; i < totalCount; i++) {
            targetItems[i] = (String)rewardItems[i].getSelectedItem();
        }

        CharmData d = new CharmData();
        d.setBlue(); // 風化したお守り

        final int fNormalCount = normalCount;
        final int fThreshold = threshold;

        statusLabel.setText("報酬逆算中...");
        progressBar.setValue(0);
        progressBar.setVisible(true);

        Thread.ofVirtual().start(() -> {
            long t0 = System.currentTimeMillis();
            List<RewardSearchResult> results = reverseSearchRewards(
                    totalCount, fNormalCount, targetItems, maxF,
                    fThreshold, null,
                    (done, total) -> SwingUtilities.invokeLater(() -> {
                        int pct = (int)((long)done * 100 / total);
                        progressBar.setValue(pct);
                        statusLabel.setText(String.format("報酬逆算中... %d%%", pct));
                    }),
                    cancelFlag);

            SwingUtilities.invokeLater(() -> {
                for (RewardSearchResult rsr : results) {
                    // 報酬消費後のフレーム:
                    // 追加報酬判定消費 + 通常報酬内容消費 + 追加報酬内容消費
                    int addJudge = (additionalExpected < 4) ? additionalExpected + 1 : 4;
                    long charmFrame = rsr.frame + addJudge + totalCount;

                    RNG charmRng = new RNG();
                    charmRng.jump(charmFrame);
                    for (int j = 0; j < 7; j++) charmRng.roll();
                    Charm charm = getCharm(charmRng, d, 1);

                    String rewardStr = String.join(", ", rsr.rewards);
                    String charmStr = charm.s1Name() + charm.sp1()
                        + (charm.s2Name() != null ? " " + charm.s2Name() + charm.sp2() : "")
                        + " s" + charm.slot();

                    rewardModel.addRow(new Object[]{
                        rsr.frame, rewardStr, additionalExpected,
                        framesToTime(rsr.frame), charmStr
                    });
                }

                double elapsed = (System.currentTimeMillis() - t0) / 1000.0;
                progressBar.setVisible(false);
                statusLabel.setText(String.format("報酬逆算完了: %d件 (%.2f秒)", results.size(), elapsed));
                if (results.isEmpty()) {
                    rewardCalcResult.setText("候補なし — 報酬テーブルや個数が正しいか確認してください");
                    rewardCalcResult.setForeground(WARN);
                } else {
                    rewardCalcResult.setText(results.size() + "件の候補フレームが見つかりました");
                    rewardCalcResult.setForeground(SUCCESS);
                }
            });
        });
    }

    // ================================================================
    // Melding Tab
    // ================================================================
    JPanel buildMeldingTab() {
        JPanel tab = new JPanel(new BorderLayout(8,8));
        tab.setBackground(BG);
        tab.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        JPanel top = new JPanel();
        top.setBackground(BG);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel settings = titled("錬金シミュレーション");
        settings.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 8));
        settings.add(label("フレーム位置:"));
        meldFrame = makeField("1730", 10);
        meldFrame.addActionListener(e -> simulateMeld());
        settings.add(meldFrame);
        settings.add(label("錬金種類:"));
        meldType = makeCombo(new String[]{"天運の錬金","マカフシギ錬金"});
        meldType.setSelectedItem("マカフシギ錬金");
        settings.add(meldType);
        settings.add(label("ランク:"));
        meldRank = makeCombo(new String[]{"4 (最高)","3","2","1"});
        settings.add(meldRank);
        JButton simBtn = makeButton("▶ シミュレート", ACCENT);
        simBtn.addActionListener(e -> simulateMeld());
        settings.add(simBtn);
        top.add(settings);

        JPanel calcPanel = titled("待ち時間計算");
        calcPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 8));
        calcPanel.add(label("目標フレーム:"));
        calcTarget = makeField("", 10);
        calcPanel.add(calcTarget);
        calcPanel.add(label("基準フレーム:"));
        calcBase = makeField("0", 10);
        calcPanel.add(calcBase);
        JButton calcBtn = makeButton("計算", BTN_BG);
        calcBtn.addActionListener(e -> calcWait());
        calcPanel.add(calcBtn);
        calcTarget.addActionListener(e -> calcWait());
        calcResult = new JLabel("");
        calcResult.setFont(FONT_LARGE);
        calcResult.setForeground(SUCCESS);
        calcPanel.add(calcResult);
        top.add(calcPanel);

        tab.add(top, BorderLayout.NORTH);

        meldModel = new DefaultTableModel(
                new String[]{"#","お守り種別","第1スキル","SP1","第2スキル","SP2","スロット","レア度"}, 0);
        JTable table = makeTable(meldModel);
        JScrollPane sp = new JScrollPane(table);
        setupScrollSpeed(sp);
        sp.getViewport().setBackground(BG2);
        JPanel rp = titled("錬金結果");
        rp.setLayout(new BorderLayout());
        rp.add(sp, BorderLayout.CENTER);
        tab.add(rp, BorderLayout.CENTER);

        return tab;
    }

    void simulateMeld() {
        meldModel.setRowCount(0);
        long frame;
        try { frame = Long.parseLong(meldFrame.getText().trim()); }
        catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "フレームを正しく入力してください", "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean isHalcyon = ((String)meldType.getSelectedItem()).contains("天運");
        int rank = Character.getNumericValue(((String)meldRank.getSelectedItem()).charAt(0));
        CharmData d = new CharmData(); d.setBlue();
        List<Object[]> results = simulateMelding(d, frame, isHalcyon, rank);
        int i = 1;
        for (Object[] ro : results) {
            String kind = (String) ro[0];
            Charm c = (Charm) ro[1];
            meldModel.addRow(new Object[]{i++, kind, c.s1Name(), c.sp1(),
                    c.s2Display(), c.sp2Display(), c.slot(), "R" + c.rare()});
        }
        statusLabel.setText("錬金シミュ完了: " + results.size() + "個排出");
    }

    void calcWait() {
        try {
            long target = Long.parseLong(calcTarget.getText().trim());
            long base = Long.parseLong(calcBase.getText().trim());
            long diff = Math.abs(target - base);
            calcResult.setText(String.format("%s  (%.2f秒)", framesToTime(diff), diff / 30.0));
        } catch (NumberFormatException ex) {
            calcResult.setText("数値を入力してください");
        }
    }

    // ================================================================
    // Timer Tab - ラップタイマー
    // ================================================================
    JPanel buildTimerTab() {
        JPanel tab = new JPanel();
        tab.setBackground(BG);
        tab.setLayout(new BoxLayout(tab, BoxLayout.Y_AXIS));
        tab.setBorder(BorderFactory.createEmptyBorder(15,20,10,20));

        // 2つの表示ボックスを横並び: 経過時間 + カウントダウン
        JPanel timersRow = new JPanel(new GridLayout(1, 2, 16, 0));
        timersRow.setOpaque(false);
        timersRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));

        timerMainBox = buildTimerBox("経過時間");
        timerMainLabel = (JLabel)((JPanel)timerMainBox.getComponent(1)).getComponent(0);
        timerMainFramesLabel = (JLabel)((JPanel)timerMainBox.getComponent(1)).getComponent(1);
        timersRow.add(timerMainBox);

        timerCountdownBox = buildTimerBox("ラップ経過");
        timerCountdownLabel = (JLabel)((JPanel)timerCountdownBox.getComponent(1)).getComponent(0);
        timerCountdownFramesLabel = (JLabel)((JPanel)timerCountdownBox.getComponent(1)).getComponent(1);
        timerCountdownLabel.setText("00:00.00");
        timerCountdownFramesLabel.setText("0 フレーム");
        timersRow.add(timerCountdownBox);

        tab.add(timersRow);
        tab.add(Box.createVerticalStrut(8));

        // ショートカットガイド
        timerGuideLabel = new JLabel(
            "<html><center style='color:#8888aa;'>" +
            "[Space] 開始 / ラップ記録　　[Enter] 停止＆記録　　[R] リセット" +
            "</center></html>", SwingConstants.CENTER);
        timerGuideLabel.setFont(FONT_UI);
        timerGuideLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        tab.add(timerGuideLabel);
        tab.add(Box.createVerticalStrut(8));

        // ボタン
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        btnPanel.setOpaque(false);
        timerActionBtn = makeButton("▶ 開始 [Space]", GREEN);
        timerActionBtn.addActionListener(e -> timerStartOrLap());
        btnPanel.add(timerActionBtn);
        timerLapBtn = makeButton("■ 停止＆記録 [Enter]", ACCENT);
        timerLapBtn.setEnabled(false);
        timerLapBtn.addActionListener(e -> timerStop());
        btnPanel.add(timerLapBtn);
        timerResetBtn = makeButton("\u21BB リセット [R]", BTN_BG);
        timerResetBtn.addActionListener(e -> timerReset());
        btnPanel.add(timerResetBtn);
        tab.add(btnPanel);
        tab.add(Box.createVerticalStrut(12));

        // Target time
        JPanel targetPanel = titled("目標時間設定");
        targetPanel.setLayout(new BoxLayout(targetPanel, BoxLayout.Y_AXIS));

        JPanel targetRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        targetRow1.setOpaque(false);
        JTextField ttf = makeField("", 10);
        JTextField tbf = makeField("0", 10);
        targetRow1.add(label("目標フレーム:"));
        targetRow1.add(ttf);
        targetRow1.add(label("基準フレーム:"));
        targetRow1.add(tbf);
        JButton tcb = makeButton("目標時間を設定", BTN_BG);
        targetRow1.add(tcb);
        targetPanel.add(targetRow1);

        JPanel targetRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        targetRow2.setOpaque(false);
        timerTargetLabel = new JLabel("");
        timerTargetLabel.setFont(FONT_LARGE);
        timerTargetLabel.setForeground(WARN);
        targetRow2.add(timerTargetLabel);
        targetPanel.add(targetRow2);

        tcb.addActionListener(e -> {
            try {
                long diff = Math.abs(Long.parseLong(ttf.getText().trim()) - Long.parseLong(tbf.getText().trim()));
                targetSec = diff / 30.0;
                timerTargetLabel.setText("目標: %s (%.2f秒) → ラップ経過の色変化に反映".formatted(framesToTime(diff), targetSec));
            } catch (Exception ex) { timerTargetLabel.setText("数値を入力してください"); }
        });
        ttf.addActionListener(tcb.getActionListeners()[0]);
        tab.add(targetPanel);
        tab.add(Box.createVerticalStrut(8));

        // ラップ記録テーブル
        JPanel recPanel = titled("ラップ記録");
        recPanel.setLayout(new BorderLayout(4,4));
        JPanel recBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        recBtns.setOpaque(false);
        JButton clrBtn = makeButton("記録をクリア", BTN_BG);
        clrBtn.addActionListener(e -> { lapModel.setRowCount(0); lapCount = 0; });
        recBtns.add(clrBtn);
        JButton exportLapBtn = makeButton("CSV保存", BTN_BG);
        exportLapBtn.addActionListener(e -> exportCSV(lapModel,
                new String[]{"#","ラップタイム","ラップF","累計時間","累計F"}));
        recBtns.add(exportLapBtn);
        recPanel.add(recBtns, BorderLayout.NORTH);

        lapModel = new DefaultTableModel(
                new String[]{"#","ラップタイム","ラップF","累計時間","累計F"}, 0);
        JTable lapTable = makeTable(lapModel);
        JScrollPane lapSp = new JScrollPane(lapTable);
        setupScrollSpeed(lapSp);
        lapSp.getViewport().setBackground(BG2);
        recPanel.add(lapSp, BorderLayout.CENTER);
        tab.add(recPanel);

        // Swing timer (表示更新用)
        swingTimer = new javax.swing.Timer(33, e -> updateTimerDisplay());

        // タイマータブがアクティブな時だけキーバインドを有効にする
        tabs.addChangeListener(e -> updateTimerKeyBindings());

        return tab;
    }

    void updateTimerKeyBindings() {
        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();
        int tabIdx = tabs.getSelectedIndex();
        boolean isTimerTab = tabIdx == 4; // タイマータブ（新インデックス）
        boolean isRewardTab = tabIdx == 2; // 報酬逆算タブ

        // まず全部クリア
        im.remove(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0));
        im.remove(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        im.remove(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0));
        am.remove("timerSpace");
        am.remove("timerEnter");
        am.remove("timerR");
        am.remove("appraiseSpace");
        am.remove("appraiseR");

        if (isTimerTab) {
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "timerSpace");
            am.put("timerSpace", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    if (focused instanceof JTextField || focused instanceof JTextArea) return;
                    timerStartOrLap();
                }
            });
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "timerEnter");
            am.put("timerEnter", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    if (focused instanceof JTextField || focused instanceof JTextArea) return;
                    timerStop();
                }
            });
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "timerR");
            am.put("timerR", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    if (focused instanceof JTextField || focused instanceof JTextArea) return;
                    timerReset();
                }
            });
        } else if (isRewardTab) {
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "appraiseSpace");
            am.put("appraiseSpace", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    if (focused instanceof JTextField || focused instanceof JTextArea) return;
                    // 実行中なら停止、停止中なら起動
                    if (appraiseRunning) appraiseStop();
                    else appraiseStart();
                }
            });
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "appraiseR");
            am.put("appraiseR", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    if (focused instanceof JTextField || focused instanceof JTextArea) return;
                    appraiseReset();
                }
            });
        }
    }

    /** Space: 停止中なら開始、計測中ならラップ記録して継続 */
    void timerStartOrLap() {
        if (timerState == 0) {
            // 開始
            timerState = 1;
            timerStartMs = System.currentTimeMillis();
            lastLapMs = timerStartMs;
            timerActionBtn.setText("◉ ラップ [Space]");
            timerActionBtn.setBackground(ACCENT2);
            timerLapBtn.setEnabled(true);
            timerMainBox.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ACCENT_T, 2),
                    BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            timerMainLabel.setForeground(SUCCESS);
            timerMainFramesLabel.setForeground(FG);
            swingTimer.start();
        } else {
            // ラップ記録
            recordLap();
        }
    }

    /** Enter: 停止＆最終ラップ記録 */
    void timerStop() {
        if (timerState != 1) return;
        recordLap();
        timerState = 0;
        swingTimer.stop();
        timerActionBtn.setText("▶ 開始 [Space]");
        timerActionBtn.setBackground(GREEN);
        timerLapBtn.setEnabled(false);
        timerMainBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DIM, 2),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        updateTimerDisplay();
    }

    void timerReset() {
        timerState = 0;
        swingTimer.stop();
        timerMainLabel.setText("00:00.00"); timerMainLabel.setForeground(DIM);
        timerMainFramesLabel.setText("0 フレーム"); timerMainFramesLabel.setForeground(DIM);
        timerActionBtn.setText("▶ 開始 [Space]");
        timerActionBtn.setBackground(GREEN);
        timerLapBtn.setEnabled(false);
        timerMainBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DIM, 2),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        timerCountdownLabel.setText("00:00.00"); timerCountdownLabel.setForeground(DIM);
        timerCountdownFramesLabel.setText("0 フレーム"); timerCountdownFramesLabel.setForeground(DIM);
        timerCountdownBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DIM, 2),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
    }

    void updateTimerDisplay() {
        if (timerState == 1) {
            long now = System.currentTimeMillis();
            // 左: 累計経過時間
            double totalElapsed = (now - timerStartMs) / 1000.0;
            int tm = (int)(totalElapsed / 60); double ts = totalElapsed % 60; int tf = (int)(totalElapsed * 30);
            timerMainLabel.setText(String.format("%02d:%05.2f", tm, ts));
            timerMainFramesLabel.setText(String.format("%,d フレーム", tf));

            // 右: 直前のラップからの経過時間（カウントアップ）
            double lapElapsed = (now - lastLapMs) / 1000.0;
            int lm = (int)(lapElapsed / 60); double ls = lapElapsed % 60; int lf = (int)(lapElapsed * 30);
            timerCountdownLabel.setText(String.format("%02d:%05.2f", lm, ls));
            timerCountdownFramesLabel.setText(String.format("%,d フレーム", lf));

            // 目標時間に対する色変化 + テキスト状態表示（色覚多様性対応）
            if (targetSec > 0) {
                double remain = targetSec - lapElapsed;
                Color borderColor;
                String statusText;
                if (remain > 10) {
                    timerCountdownLabel.setForeground(SUCCESS);
                    borderColor = SUCCESS;
                    statusText = String.format("%,d F （余裕あり）", lf);
                } else if (remain > 3) {
                    timerCountdownLabel.setForeground(WARN);
                    borderColor = WARN;
                    statusText = String.format("%,d F （あと%.0f秒）", lf, remain);
                } else if (remain > 0) {
                    boolean blink = ((int)(remain * 2)) % 2 == 0;
                    timerCountdownLabel.setForeground(blink ? ACCENT_T : WARN);
                    borderColor = ACCENT_T;
                    statusText = String.format("%,d F ★ 今！ ★", lf);
                } else {
                    timerCountdownLabel.setForeground(ACCENT_T);
                    borderColor = ACCENT_T;
                    statusText = String.format("%,d F （+%.1f秒 超過）", lf, -remain);
                }
                timerCountdownFramesLabel.setText(statusText);
                timerCountdownFramesLabel.setForeground(FG);
                timerCountdownBox.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderColor, 3),
                        BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            } else {
                timerCountdownLabel.setForeground(SUCCESS);
                timerCountdownFramesLabel.setForeground(FG);
                timerCountdownBox.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(SUCCESS, 2),
                        BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            }
        }
    }

    void recordLap() {
        long now = System.currentTimeMillis();
        double lapSec = (now - lastLapMs) / 1000.0;
        double totalSec = (now - timerStartMs) / 1000.0;
        lastLapMs = now; // ← ラップ基準時刻をリセット（右ボックスが0から再スタート）
        lapCount++;

        int lapFrames = (int)(lapSec * 30);
        int totalFrames = (int)(totalSec * 30);

        // テーブルに追加
        int lm = (int)(lapSec / 60); double ls = lapSec % 60;
        lapModel.addRow(new Object[]{
            lapCount,
            String.format("%02d:%05.2f", lm, ls),
            String.format("%,d", lapFrames),
            String.format("%02d:%05.2f", (int)(totalSec / 60), totalSec % 60),
            String.format("%,d", totalFrames)
        });
    }

    JPanel buildTimerBox(String title) {
        JPanel box = new JPanel(new BorderLayout(0, 4));
        box.setBackground(BG2);
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DIM, 2),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
        titleLabel.setFont(FONT_UI_BOLD);
        titleLabel.setForeground(DIM);
        box.add(titleLabel, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        JLabel timeLabel = new JLabel("00:00.00", SwingConstants.CENTER);
        timeLabel.setFont(FONT_MONO);
        timeLabel.setForeground(DIM);
        timeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        center.add(timeLabel);

        JLabel framesLabel = new JLabel("0 フレーム", SwingConstants.CENTER);
        framesLabel.setFont(FONT_LARGE);
        framesLabel.setForeground(DIM);
        framesLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        center.add(framesLabel);

        box.add(center, BorderLayout.CENTER);
        return box;
    }

    // ================================================================
    // Arduino Tab - コード自動生成
    // ================================================================
    JPanel buildArduinoTab() {
        JPanel tab = new JPanel(new BorderLayout(8,8));
        tab.setBackground(BG);
        tab.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        JPanel top = new JPanel();
        top.setBackground(BG);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        // 入力パネル
        JPanel inputPanel = titled("Arduino Leonardo コード生成 (Continue連打法)");
        inputPanel.setLayout(new BoxLayout(inputPanel, BoxLayout.Y_AXIS));

        // 説明テキスト
        JPanel descPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        descPanel.setOpaque(false);
        JLabel descLabel = new JLabel(
            "<html><body style='width:800px; color:#8888aa;'>" +
            "起動→Continue A→Bキャンセル(N回)→待機時間→Continue決定→ロード→マカ錬金の一連操作を自動化するコードを生成します。<br>" +
            "計算式: <b style='color:#00d2ff;'>目標F = 基本消費(F) + Continue1回消費(F) × 回数 + 0.030(F/ms) × 待機時間(ms)</b><br>" +
            "Continue回数(整数)で大まかに合わせ、待機時間(ms)で端数を調整します。" +
            "</body></html>");
        descLabel.setFont(FONT_SMALL);
        descPanel.add(descLabel);
        inputPanel.add(descPanel);

        // Row 1: 目標フレーム + 基準消費
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r1.setOpaque(false);
        r1.add(label("目標フレーム:"));
        arduinoTarget = makeField("", 12);
        arduinoTarget.setToolTipText("検索結果で見つけたお守りのフレーム番号（右クリックから自動入力も可）");
        r1.add(arduinoTarget);
        r1.add(label("基本消費フレーム:"));
        arduinoC = makeField("1675", 8);
        arduinoC.setToolTipText(
            "<html>ゲーム起動からマカ錬金確定までに固定で消費されるフレーム数。<br>" +
            "環境(3DS/Switch)やロード速度で変わるため、<br>" +
            "下のキャリブレーション機能で実測するのがおすすめ。<br>" +
            "デフォルト 1675 は実測値（Anemos環境）です。</html>");
        r1.add(arduinoC);
        inputPanel.add(r1);

        // Row 2: continue消費 + 事後待機範囲
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r2.setOpaque(false);
        r2.add(label("Continue1回の消費フレーム:"));
        arduinoFc = makeField("716", 6);
        arduinoFc.setToolTipText(
            "<html>タイトル画面でContinue→キャンセルを1回行った時の消費フレーム数。<br>" +
            "環境により異なるため、キャリブレーションで実測するのがおすすめ。<br>" +
            "デフォルト 716 は実測値（Anemos環境）です。</html>");
        r2.add(arduinoFc);
        r2.add(label("  待機時間の範囲:"));
        JTextField arduinoT2min = makeField("5000", 8);
        arduinoT2min.setToolTipText("待機時間の最小値(ms)。短すぎるとタイミング精度が落ちます");
        r2.add(arduinoT2min);
        r2.add(label("～"));
        JTextField arduinoT2max = makeField("20000", 8);
        arduinoT2max.setToolTipText("待機時間の最大値(ms)。長すぎると無駄な待ち時間が増えます");
        r2.add(arduinoT2max);
        r2.add(label("ms"));
        inputPanel.add(r2);

        // Row 3: ボタン
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r3.setOpaque(false);
        arduinoCalcBtn = makeButton("▶ 計算 & コード生成", ACCENT);
        arduinoCalcBtn.setToolTipText("Continue回数と待機時間(ms)を自動決定し、Arduinoコードを生成");
        r3.add(arduinoCalcBtn);
        JButton arduinoExportBtn = makeButton("コードを保存 (.ino)", BTN_BG);
        arduinoExportBtn.setToolTipText("生成したコードを .ino ファイルとして保存");
        r3.add(arduinoExportBtn);
        inputPanel.add(r3);

        // 計算結果
        JPanel resultPanel = titled("計算結果");
        resultPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JLabel arduinoResultLabel = new JLabel(
            "<html><span style='color:#8888aa;'>目標フレームを入力して「計算 &amp; コード生成」を押してください</span></html>");
        arduinoResultLabel.setFont(FONT_LARGE);
        arduinoResultLabel.setForeground(SUCCESS);
        resultPanel.add(arduinoResultLabel);

        top.add(inputPanel);
        top.add(resultPanel);

        // コードプレビュー（先に宣言、パネル構築は後）
        JTextArea codeArea = new JTextArea(20, 60);
        codeArea.setBackground(BG2);
        codeArea.setForeground(new Color(0x80, 0xff, 0x80));
        codeArea.setFont(FONT_MONO_SMALL);
        codeArea.setCaretColor(FG);
        codeArea.setEditable(false);
        codeArea.setTabSize(4);

        // 待機時間補正パネル
        JPanel adjustPanel = titled("待機時間の補正（実測結果から待機時間を調整）");
        adjustPanel.setLayout(new BoxLayout(adjustPanel, BoxLayout.Y_AXIS));

        JPanel adjR1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        adjR1.setOpaque(false);
        adjR1.add(label("目標フレーム:"));
        JTextField adjTarget = makeField("", 10);
        adjR1.add(adjTarget);
        adjR1.add(label("実測フレーム:"));
        JTextField adjActual = makeField("", 10);
        adjR1.add(adjActual);
        adjustPanel.add(adjR1);

        JPanel adjR1b = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        adjR1b.setOpaque(false);
        adjR1b.add(label("Continue回数:"));
        JTextField adjNc = makeField("", 8);
        adjR1b.add(adjNc);
        adjR1b.add(label("前回の待機時間:"));
        JTextField adjPrevT2 = makeField("", 10);
        adjR1b.add(adjPrevT2);
        adjR1b.add(label("ms"));
        adjustPanel.add(adjR1b);

        JPanel adjR2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        adjR2.setOpaque(false);
        JButton adjBtn = makeButton("補正計算", BTN_BG);
        adjR2.add(adjBtn);
        adjustPanel.add(adjR2);

        JPanel adjR3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        adjR3.setOpaque(false);
        JLabel adjResult = new JLabel("");
        adjResult.setFont(FONT_LARGE);
        adjResult.setForeground(SUCCESS);
        adjR3.add(adjResult);
        adjustPanel.add(adjR3);

        // 補正ログテーブル
        String[] adjLogCols = {"#", "目標F", "実測F", "ズレ", "Continue", "待機時間(ms)"};
        javax.swing.table.DefaultTableModel adjLogModel = new javax.swing.table.DefaultTableModel(adjLogCols, 0);
        JTable adjLogTable = makeTable(adjLogModel);
        adjLogTable.setFont(FONT_MONO_SMALL);
        adjLogTable.setRowHeight(28);
        JScrollPane adjLogSp = new JScrollPane(adjLogTable);
        setupScrollSpeed(adjLogSp);
        adjLogSp.setPreferredSize(new java.awt.Dimension(0, 120));
        adjLogSp.getViewport().setBackground(BG2);

        JPanel adjLogBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        adjLogBtnPanel.setOpaque(false);
        JButton adjLogClear = makeButton("ログクリア", BTN_BG);
        adjLogClear.addActionListener(e -> adjLogModel.setRowCount(0));
        adjLogBtnPanel.add(adjLogClear);
        JButton adjLogCsv = makeButton("CSV保存", BTN_BG);
        adjLogCsv.addActionListener(e -> {
            if (adjLogModel.getRowCount() == 0) {
                statusLabel.setText("ログが空です");
                return;
            }
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new java.io.File("correction_log.csv"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try (java.io.PrintWriter pw = new java.io.PrintWriter(
                        new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(fc.getSelectedFile()), "UTF-8"))) {
                    // BOM + ヘッダー
                    pw.print("\uFEFF");
                    for (int c = 0; c < adjLogModel.getColumnCount(); c++) {
                        if (c > 0) pw.print(",");
                        pw.print(adjLogModel.getColumnName(c));
                    }
                    pw.println();
                    // データ
                    for (int r = 0; r < adjLogModel.getRowCount(); r++) {
                        for (int c = 0; c < adjLogModel.getColumnCount(); c++) {
                            if (c > 0) pw.print(",");
                            pw.print(adjLogModel.getValueAt(r, c));
                        }
                        pw.println();
                    }
                    statusLabel.setText("補正ログをCSV保存しました: " + fc.getSelectedFile().getName());
                } catch (Exception ex) {
                    statusLabel.setText("CSV保存に失敗: " + ex.getMessage());
                }
            }
        });
        adjLogBtnPanel.add(adjLogCsv);

        JPanel adjLogPanel = titled("補正ログ");
        adjLogPanel.setLayout(new BorderLayout(4, 4));
        adjLogPanel.add(adjLogBtnPanel, BorderLayout.NORTH);
        adjLogPanel.add(adjLogSp, BorderLayout.CENTER);
        adjustPanel.add(adjLogPanel);

        adjBtn.addActionListener(e -> {
            try {
                long target = Long.parseLong(adjTarget.getText().trim());
                long actual = Long.parseLong(adjActual.getText().trim());
                long prevT2 = Long.parseLong(adjPrevT2.getText().trim());
                long diffF = actual - target;
                String dir = diffF > 0 ? "オーバー" : "不足";

                double t2min = Double.parseDouble(arduinoT2min.getText().trim());
                double t2max = Double.parseDouble(arduinoT2max.getText().trim());

                // Continue回数が入力されていれば前回Ncとして使用
                String ncText = adjNc.getText().trim();
                int prevNc = 0;
                double fc = Double.parseDouble(arduinoFc.getText().trim());
                if (!ncText.isEmpty()) {
                    prevNc = Integer.parseInt(ncText);
                }

                int bestNc = -1;
                long bestT2 = -1;

                // まず前回と同じNcのまま、ズレ分だけ待機時間を調整
                long diffMs = Math.round(diffF / 0.030);
                long adjustedT2 = prevT2 - diffMs;
                if (prevNc > 0 && adjustedT2 >= t2min && adjustedT2 <= t2max) {
                    bestNc = prevNc;
                    bestT2 = adjustedT2;
                }

                // 待機時間の範囲に収まらない場合、目標フレームから再計算
                // ただし実測ズレを反映: 補正目標 = target + (target - actual) = 2*target - actual
                if (bestNc < 0) {
                    double cVal = Double.parseDouble(arduinoC.getText().trim());
                    long correctedTarget = 2 * target - actual; // ズレ分を逆方向にオフセット
                    for (int nc = 0; nc < 10000; nc++) {
                        double t2 = (correctedTarget - cVal - fc * nc) / 0.030;
                        if (t2 >= t2min && t2 <= t2max) {
                            bestNc = nc;
                            bestT2 = Math.round(t2);
                            break;
                        }
                    }
                }

                // それでも見つからない場合、元の目標フレームで再計算
                if (bestNc < 0) {
                    double cVal = Double.parseDouble(arduinoC.getText().trim());
                    for (int nc = 0; nc < 10000; nc++) {
                        double t2 = (target - cVal - fc * nc) / 0.030;
                        if (t2 >= t2min && t2 <= t2max) {
                            bestNc = nc;
                            bestT2 = Math.round(t2);
                            break;
                        }
                    }
                }

                if (bestNc < 0) {
                    adjResult.setText(("<html>ズレ: %+dF (%s)" +
                        "<br><span style='color:#ffc107;'>⚠ 待機時間の範囲内で解が見つかりません</span></html>").formatted(
                        diffF, dir));
                    adjResult.setForeground(WARN);
                } else {
                    int ncDiff = bestNc - prevNc;
                    long t2Diff = bestT2 - prevT2;
                    String ncChange = ncDiff == 0 ? "変更なし" : "%+d回".formatted(ncDiff);

                    adjResult.setText(("<html>ズレ: %+dF (%s)<br>" +
                        "補正後: Continue <b>%d回</b> (%s) / 待機時間 <b>%d ms</b> (%+d ms)" +
                        "</html>").formatted(
                        diffF, dir, bestNc, ncChange, bestT2, t2Diff));
                    adjResult.setForeground(SUCCESS);

                    // 補正後のパラメータでコードを再生成
                    codeArea.setText(generateArduinoCode(bestNc, bestT2));
                    codeArea.setCaretPosition(0);
                    statusLabel.setText("補正: Continue %d回, 待機時間 %d ms でコードを再生成しました".formatted(bestNc, bestT2));

                    // ログに追記
                    adjLogModel.addRow(new Object[]{
                        adjLogModel.getRowCount() + 1,
                        target, actual,
                        diffF,
                        bestNc,
                        bestT2
                    });
                    adjLogTable.scrollRectToVisible(adjLogTable.getCellRect(adjLogModel.getRowCount() - 1, 0, true));
                }
            } catch (NumberFormatException ex) {
                adjResult.setText("数値を入力してください");
            }
        });
        adjActual.addActionListener(adjBtn.getActionListeners()[0]);

        top.add(adjustPanel);

        JScrollPane topSp = new JScrollPane(top);
        topSp.setBorder(null);
        topSp.getViewport().setBackground(BG);
        setupScrollSpeed(topSp);

        // コードプレビューのパネル構築
        JScrollPane codeSp = new JScrollPane(codeArea);
        setupScrollSpeed(codeSp);
        codeSp.getViewport().setBackground(BG2);
        JPanel codePanel = titled("生成コード プレビュー");
        codePanel.setLayout(new BorderLayout());
        JButton codeCopyBtn = makeButton("クリップボードにコピー", BTN_BG);
        codeCopyBtn.addActionListener(e -> {
            String code = codeArea.getText();
            if (!code.isEmpty()) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(code), null);
                statusLabel.setText("コードをクリップボードにコピーしました");
            }
        });
        JPanel codeBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        codeBtnPanel.setOpaque(false);
        codeBtnPanel.add(codeCopyBtn);
        codePanel.add(codeBtnPanel, BorderLayout.NORTH);
        codePanel.add(codeSp, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topSp, codePanel);
        split.setBackground(BG);
        split.setDividerLocation(400);
        split.setResizeWeight(0.4);
        split.setContinuousLayout(true);
        tab.add(split, BorderLayout.CENTER);

        // 計算ロジック
        arduinoCalcBtn.addActionListener(e -> {
            try {
                long targetF = Long.parseLong(arduinoTarget.getText().trim());
                double fc = Double.parseDouble(arduinoFc.getText().trim());
                double C = Double.parseDouble(arduinoC.getText().trim());
                double t2min = Double.parseDouble(arduinoT2min.getText().trim());
                double t2max = Double.parseDouble(arduinoT2max.getText().trim());
                double f2 = 0.030; // 30fps: 1msあたり0.030フレーム

                // Nc を探索: t2min <= T2 <= t2max を満たすNcを見つける
                // F = fc*Nc + f2*T2 + C → T2 = (F - fc*Nc - C) / f2
                int bestNc = -1;
                double bestT2 = -1;
                for (int nc = 0; nc < 10000; nc++) {
                    double t2 = (targetF - fc * nc - C) / f2;
                    if (t2 >= t2min && t2 <= t2max) {
                        bestNc = nc;
                        bestT2 = t2;
                        break;
                    }
                }

                if (bestNc < 0) {
                    // t2maxでも足りない場合、Ncを大きくする
                    for (int nc = 10000; nc >= 0; nc--) {
                        double t2 = (targetF - fc * nc - C) / f2;
                        if (t2 >= t2min && t2 <= t2max) {
                            bestNc = nc;
                            bestT2 = t2;
                            break;
                        }
                    }
                }

                if (bestNc < 0) {
                    arduinoResultLabel.setText(
                        "<html><span style='color:#f5a623;'>解が見つかりません。待機時間の範囲を広げるか、基本消費フレームを調整してください。</span></html>");
                    codeArea.setText("");
                    return;
                }

                long waitMs = Math.round(bestT2);
                double totalTimeSec = (bestNc * 400 + waitMs) / 1000.0;
                double estFrame = fc * bestNc + f2 * waitMs + C;

                arduinoResultLabel.setText(String.format(
                    "<html><table cellpadding='2'>" +
                    "<tr><td style='color:#aaa;'>Continue連打:</td><td style='color:#00d2ff;'><b>%d 回</b></td>" +
                    "<td style='color:#aaa;'>　端数待機:</td><td style='color:#00d2ff;'><b>%,d ms</b> (%.1f秒)</td></tr>" +
                    "<tr><td style='color:#aaa;'>推定到達F:</td><td style='color:#ffcc00;'><b>%,.0f F</b></td>" +
                    "<td style='color:#aaa;'>　総所要時間:</td><td style='color:#ffcc00;'><b>%.1f秒</b> (%.1f分)</td></tr>" +
                    "</table></html>",
                    bestNc, waitMs, waitMs / 1000.0,
                    estFrame, totalTimeSec, totalTimeSec / 60));

                // コード生成
                String code = generateArduinoCode(bestNc, waitMs);
                codeArea.setText(code);
                codeArea.setCaretPosition(0);

            } catch (NumberFormatException ex) {
                arduinoResultLabel.setText("数値を正しく入力してください");
            }
        });

        // エクスポート
        arduinoExportBtn.addActionListener(e -> {
            String code = codeArea.getText();
            if (code.isEmpty()) {
                JOptionPane.showMessageDialog(this, "先にコードを生成してください", "情報", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("mhxx_snipe.ino"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                        new FileOutputStream(fc.getSelectedFile()), "UTF-8"))) {
                    pw.print(code);
                    statusLabel.setText("Arduino コード保存完了: " + fc.getSelectedFile().getName());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "保存に失敗: " + ex.getMessage(), "エラー", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        return tab;
    }

    String generateArduinoCode(int numContinue, long waitMs) {
        var header = """
                // MHXX Charm Snipe Tool - Auto Generated Arduino Code
                // Continue連打法対応
                #include <NintendoSwitchControlLibrary.h>

                unsigned long num_continue = %d;
                unsigned long wait_ms = %d;

                void waitWithKeepAlive(unsigned long total_ms,
                                       uint16_t button = Button::X,
                                       unsigned long interval_ms = 5000,
                                       unsigned long press_ms = 100)
                {
                    if (total_ms <= 10000) { delay(total_ms); return; }
                    unsigned long cycle = interval_ms;
                    unsigned long n = total_ms / cycle;
                    unsigned long rem = total_ms %% cycle;
                    for (unsigned long i = 0; i < n; i++) {
                        SwitchControlLibrary().pressButton(button);
                        SwitchControlLibrary().sendReport();
                        delay(press_ms);
                        SwitchControlLibrary().releaseButton(button);
                        SwitchControlLibrary().sendReport();
                        delay(interval_ms - press_ms);
                    }
                    if (rem > 0) { delay(rem); }
                }

                void setup(){
                    delay(50);

                    // MHXX起動 → ゲームモード選択画面までA連打
                    pushButton(Button::A, 255, 32);

                """.formatted(numContinue, waitMs);

        var continueBlock = numContinue > 0 ? """
                    // 事前待機 (10秒)
                    waitWithKeepAlive(10000);

                    // Continue A→Bキャンセル (%d回)
                    for (unsigned long i = 0; i < %d; i++){
                        SwitchControlLibrary().pressButton(Button::A);
                        SwitchControlLibrary().sendReport();
                        delay(100);
                        SwitchControlLibrary().releaseButton(Button::A);
                        SwitchControlLibrary().sendReport();
                        delay(100);
                        SwitchControlLibrary().pressButton(Button::B);
                        SwitchControlLibrary().sendReport();
                        delay(100);
                        SwitchControlLibrary().releaseButton(Button::B);
                        SwitchControlLibrary().sendReport();
                        delay(100);
                    }

                """.formatted(numContinue, numContinue) : "";

        var tail = """
                    // Continue決定 → ロード
                    pushButton(Button::A, 250, 4);
                    delay(9500);

                    // ココット村 → マカ錬金屋へダッシュ
                    SwitchControlLibrary().pressButton(Button::R);
                    SwitchControlLibrary().sendReport();
                    tiltLeftStick(100, Stick::MIN, 3200);
                    SwitchControlLibrary().releaseButton(Button::R);
                    SwitchControlLibrary().sendReport();

                    // マカ錬金メニュー → 護石3個選択（投入確認ダイアログまで進める）
                    pushButton(Button::A, 100);
                    pushButton(Button::B, 250, 6);
                    pushButton(Button::A, 100);
                    pushHat(Hat::UP);
                    pushButton(Button::A, 10);
                    pushButton(Button::A, 10);    // 1番目の護石
                    pushHat(Hat::DOWN, 10);
                    pushButton(Button::A, 10);    // 2番目の護石
                    pushHat(Hat::DOWN, 10);
                    pushButton(Button::A, 10);    // 3番目の護石（→ 投入確認ダイアログ）
                    delay(1000);                    // ダイアログ表示の安定待ち

                    // 投入確認ダイアログで目標フレームまで待機 (%d ms)
                    waitWithKeepAlive(%d);

                    // A連打で投入確定（このタイミングで乱数決定）
                    pushButton(Button::A, 100, 2);
                    pushButton(Button::B, 100, 5);

                    // ケルビ納品クエスト
                    SwitchControlLibrary().pressButton(Button::R);
                    SwitchControlLibrary().sendReport();
                    tiltLeftStick(Stick::MAX, Stick::MIN, 1500);
                    SwitchControlLibrary().releaseButton(Button::R);
                    SwitchControlLibrary().sendReport();
                    pushButton(Button::A, 250, 3);
                    pushButton(Button::B, 250, 4);
                    delay(100);
                    pushHat(Hat::UP);
                    pushButton(Button::A, 100);
                    pushHat(Hat::DOWN);
                    pushButton(Button::A, 100);
                    pushHat(Hat::DOWN, 50, 3);
                    pushButton(Button::A, 50, 5);
                    pushButton(Button::B, 250, 4);

                    // クエスト出発
                    SwitchControlLibrary().pressButton(Button::R);
                    SwitchControlLibrary().sendReport();
                    tiltLeftStick(Stick::MIN, Stick::NEUTRAL, 800);
                    tiltLeftStick(Stick::NEUTRAL, Stick::MIN, 2300);
                    SwitchControlLibrary().releaseButton(Button::R);
                    SwitchControlLibrary().sendReport();
                    pushButton(Button::A, 50, 5);
                    delay(8700);

                    // ケルビの角を納品
                    pushButton(Button::PLUS, 250);
                    pushButton(Button::A, 250, 2);
                    pushHat(Hat::DOWN, 50, 2);
                    pushButton(Button::A, 250);
                    pushHat(Hat::RIGHT);
                    pushButton(Button::A, 250, 4);
                    delay(36000);

                    // 報酬売却 → セーブせず終了
                    pushHat(Hat::UP);
                    pushButton(Button::A, 250);
                    pushHat(Hat::LEFT);
                    pushButton(Button::A, 250, 5);
                    pushButton(Button::B, 250);
                    pushButton(Button::A, 250);
                    delay(7900);

                    // 自宅で鑑定
                    pushButton(Button::X, 250);
                    pushButton(Button::A, 250, 2);
                    delay(2000);
                    tiltLeftStick(Stick::MAX, Stick::NEUTRAL, 700);
                    pushButton(Button::A, 250, 2);
                    pushButton(Button::B, 250, 2);
                    delay(100);
                    pushButton(Button::A, 250);
                    pushHat(Hat::DOWN, 50, 3);
                    pushButton(Button::A, 250);
                    pushHat(Hat::DOWN, 50);
                    pushButton(Button::A, 250, 2);
                }

                void loop(){}
                """.formatted(waitMs, waitMs);

        return header + continueBlock + tail;
    }

    // ================================================================
    // 調合スナイプ用2コード方式のArduinoスケッチ生成
    //
    // コード1: Continue連打→ゲーム開始→自宅で調合→30秒録画→HOME
    // コード2: HOME復帰→待機→ルームサービス経由マカ錬金→ケルビ→鑑定
    // ================================================================

    /**
     * 調合スナイプ用コード1を生成。
     * Continue連打でフレームを大量消費した後、ゲーム開始→自宅で調合→録画→HOME。
     * @param numContinue Continue連打回数
     * @param numCombo 調合回数（Lv2通常弾を何個作るか。50個程度推奨）
     */
    static String generateComboCode1(int numContinue, int downKeysToLv2) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ================================================================\n");
        sb.append("// MHXX 調合スナイプ - コード1 (フレーム消費 + 調合 + 録画)\n");
        sb.append("// ================================================================\n");
        sb.append("// 実行前提:\n");
        sb.append("//   ★ ココット村でセーブ済み（ロード後は村スタート → 自宅へ自動移動します）★\n");
        sb.append("//   - ハリの実×15以上, カラの実×15以上, 調合書①入門編を所持\n");
        sb.append("//   - オトモなし / ペットなし\n");
        sb.append("//   - ルームサービス = モガの村の看板娘\n");
        sb.append("//   - Switchのアルバムに空きがあること（30秒録画用）\n");
        sb.append("//\n");
        sb.append("// 動作:\n");
        sb.append("//   1. ゲーム起動 → A連打 → ゲームモード選択画面\n");
        sb.append("//   2. Continue連打で乱数を大量消費\n");
        sb.append("//   3. Continue決定 → ロード（村スタート）\n");
        sb.append("//   4. ワールドマップ → 自宅へ移動（X→A×2）\n");
        sb.append("//   5. +ボタンでメニュー → 上から3番目「リストから調合」 → アイテムリスト画面\n");
        sb.append("//   6. ↓キーでLv2通常弾までカーソル移動 → A決定で調合確認画面\n");
        sb.append("//   7. A長押しで連続調合\n");
        sb.append("//   8. 30秒録画（キャプチャーボタン長押し）\n");
        sb.append("//   9. HOMEボタンでゲーム中断\n");
        sb.append("//\n");
        sb.append("// ここでArduinoを外し、録画を確認して累積弾数をツールに入力。\n");
        sb.append("// 現在フレームを特定後、コード2のwait_msを設定して書き込む。\n");
        sb.append("// ================================================================\n");
        sb.append("#include <NintendoSwitchControlLibrary.h>\n\n");

        // waitWithKeepAlive関数
        sb.append("void waitWithKeepAlive(unsigned long total_ms,\n");
        sb.append("                       uint16_t button = Button::X,\n");
        sb.append("                       unsigned long interval_ms = 5000,\n");
        sb.append("                       unsigned long press_ms = 100)\n");
        sb.append("{\n");
        sb.append("    if (total_ms <= 10000) { delay(total_ms); return; }\n");
        sb.append("    unsigned long cycle = interval_ms;\n");
        sb.append("    unsigned long n = total_ms / cycle;\n");
        sb.append("    unsigned long rem = total_ms % cycle;\n");
        sb.append("    for (unsigned long i = 0; i < n; i++) {\n");
        sb.append("        SwitchControlLibrary().pressButton(button);\n");
        sb.append("        SwitchControlLibrary().sendReport();\n");
        sb.append("        delay(press_ms);\n");
        sb.append("        SwitchControlLibrary().releaseButton(button);\n");
        sb.append("        SwitchControlLibrary().sendReport();\n");
        sb.append("        delay(interval_ms - press_ms);\n");
        sb.append("    }\n");
        sb.append("    if (rem > 0) { delay(rem); }\n");
        sb.append("}\n\n");

        sb.append("void setup() {\n");
        sb.append("    delay(50);\n\n");

        // Step 1: ゲーム起動
        sb.append("    // Step 1: MHXX起動 → ゲームモード選択画面までA連打\n");
        sb.append("    pushButton(Button::A, 255, 32);\n\n");

        // Step 2: Continue連打
        if (numContinue > 0) {
            sb.append("    // Step 2: 事前待機 + Continue連打 (").append(numContinue).append("回)\n");
            sb.append("    waitWithKeepAlive(10000); // 事前待機10秒\n");
            sb.append("    for (unsigned long i = 0; i < ").append(numContinue).append("; i++) {\n");
            sb.append("        SwitchControlLibrary().pressButton(Button::A);\n");
            sb.append("        SwitchControlLibrary().sendReport();\n");
            sb.append("        delay(100);\n");
            sb.append("        SwitchControlLibrary().releaseButton(Button::A);\n");
            sb.append("        SwitchControlLibrary().sendReport();\n");
            sb.append("        delay(100);\n");
            sb.append("        SwitchControlLibrary().pressButton(Button::B);\n");
            sb.append("        SwitchControlLibrary().sendReport();\n");
            sb.append("        delay(100);\n");
            sb.append("        SwitchControlLibrary().releaseButton(Button::B);\n");
            sb.append("        SwitchControlLibrary().sendReport();\n");
            sb.append("        delay(100);\n");
            sb.append("    }\n\n");
        }

        // Step 3: Continue決定 → ロード
        sb.append("    // Step 3: Continue決定 → ロード（ココット村）\n");
        sb.append("    pushButton(Button::A, 250, 4);\n");
        sb.append("    delay(9500);\n\n");

        // Step 4: 自宅へ移動（X→A×2）
        sb.append("    // Step 4: 自宅へ移動（ワールドマップ → 自宅選択）\n");
        sb.append("    //   ロード直後は村スタートのため、自宅まで移動する必要がある\n");
        sb.append("    pushButton(Button::X, 250);\n");
        sb.append("    pushButton(Button::A, 250, 2);\n");
        sb.append("    delay(3000); // 自宅へのロード待ち\n\n");

        // Step 5: +ボタンメニューから「リストから調合」を選択 → アイテムリスト画面
        sb.append("    // Step 5: +ボタンでメニューを開く → 「リストから調合」を選択\n");
        sb.append("    //   メニュー上から3番目が「リストから調合」\n");
        sb.append("    //   メニュー認識失敗対策: 事前にBで残留入力クリア + 開いた後に安定待ち\n");
        sb.append("    pushButton(Button::B, 200, 3);    // 残留入力クリア\n");
        sb.append("    delay(500);\n");
        sb.append("    pushButton(Button::PLUS, 500);    // メニューを開く\n");
        sb.append("    delay(500);                       // メニュー表示の安定待ち\n");
        sb.append("    pushHat(Hat::DOWN, 100, 2);       // 上から3番目に移動\n");
        sb.append("    pushButton(Button::A, 500);       // 「リストから調合」を選択\n");
        sb.append("    delay(1500);                      // アイテムリスト画面のロード待ち\n\n");

        // Step 6: アイテムリストでLv2通常弾までカーソル移動 → 決定で調合確認画面
        sb.append("    // Step 6: アイテムリストでLv2通常弾を選択 → 調合確認画面\n");
        sb.append("    //   実機でLv2通常弾までの↓キー回数を調整すること（現在: ")
                .append(downKeysToLv2).append("回）\n");
        if (downKeysToLv2 > 0) {
            sb.append("    pushHat(Hat::DOWN, 100, ").append(downKeysToLv2)
                    .append(");  // Lv2通常弾までカーソル移動\n");
        } else {
            sb.append("    // Lv2通常弾は先頭にあるためカーソル移動なし\n");
        }
        sb.append("    pushButton(Button::A, 1000);      // Aで決定 → 調合確認画面\n\n");

        // Step 7: A長押しで連続調合（30秒間）
        sb.append("    // Step 7: A長押しで連続調合（30秒間）\n");
        sb.append("    // 重要: A長押しで連続調合する（A連打ではゲーム側で連続発動しないため）\n");
        sb.append("    // 30秒間で実機の調合速度に応じて15〜25回程度調合される想定\n");
        sb.append("    holdButton(Button::A, 30000);\n");
        sb.append("    pushButton(Button::B, 250, 3);   // 調合メニューを閉じる\n\n");

        // Step 8: 30秒録画
        sb.append("    // Step 8: 30秒録画（キャプチャーボタン長押し）\n");
        sb.append("    holdButton(Button::CAPTURE, 1500);\n");
        sb.append("    delay(3000); // 録画保存待ち\n\n");

        // Step 9: HOMEボタンでゲーム中断
        sb.append("    // Step 9: HOMEボタンでゲーム中断\n");
        sb.append("    pushButton(Button::HOME, 500);\n");
        sb.append("    // ここでArduinoを外す。録画確認→コード2へ。\n");
        sb.append("}\n\n");
        sb.append("void loop() {}\n");

        return sb.toString();
    }

    /**
     * 調合スナイプ用コード2を生成。
     * HOME復帰→待機→ルームサービス経由でマカ錬金→ケルビマラソン→鑑定。
     * @param waitMs 調合開始フレームから目標フレームまでの待機時間(ms)
     */
    static String generateComboCode2(long waitMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ================================================================\n");
        sb.append("// MHXX 調合スナイプ - コード2 (ゲーム復帰 + 待機 + マカ錬金)\n");
        sb.append("// ================================================================\n");
        sb.append("// 実行前提:\n");
        sb.append("//   - コード1実行後、HOMEでゲーム中断した状態\n");
        sb.append("//   - 録画から累積弾数を読み取り、ツールで現在フレームを特定済み\n");
        sb.append("//   - wait_ms に「(目標F - 現在F) / 30 * 1000」を設定\n");
        sb.append("//\n");
        sb.append("// 動作:\n");
        sb.append("//   1. HOMEボタンでゲーム復帰（自宅にいる状態）\n");
        sb.append("//   2. ルームサービス→マカ錬金→護石3個選択（投入確認ダイアログまで）\n");
        sb.append("//   3. 投入確認ダイアログで設定時間だけ待機\n");
        sb.append("//   4. A連打で投入確定（このタイミングで乱数決定）\n");
        sb.append("//   5. ケルビマラソン（鑑定用）\n");
        sb.append("//   6. 自宅で鑑定確認\n");
        sb.append("// ================================================================\n");
        sb.append("#include <NintendoSwitchControlLibrary.h>\n\n");

        sb.append("unsigned long wait_ms = ").append(waitMs).append(";\n\n");

        // waitWithKeepAlive関数
        sb.append("void waitWithKeepAlive(unsigned long total_ms,\n");
        sb.append("                       uint16_t button = Button::X,\n");
        sb.append("                       unsigned long interval_ms = 5000,\n");
        sb.append("                       unsigned long press_ms = 100)\n");
        sb.append("{\n");
        sb.append("    if (total_ms <= 10000) { delay(total_ms); return; }\n");
        sb.append("    unsigned long cycle = interval_ms;\n");
        sb.append("    unsigned long n = total_ms / cycle;\n");
        sb.append("    unsigned long rem = total_ms % cycle;\n");
        sb.append("    for (unsigned long i = 0; i < n; i++) {\n");
        sb.append("        SwitchControlLibrary().pressButton(button);\n");
        sb.append("        SwitchControlLibrary().sendReport();\n");
        sb.append("        delay(press_ms);\n");
        sb.append("        SwitchControlLibrary().releaseButton(button);\n");
        sb.append("        SwitchControlLibrary().sendReport();\n");
        sb.append("        delay(interval_ms - press_ms);\n");
        sb.append("    }\n");
        sb.append("    if (rem > 0) { delay(rem); }\n");
        sb.append("}\n\n");

        sb.append("void setup() {\n");
        sb.append("    delay(50);\n\n");

        // Step 1: HOMEでゲーム復帰
        sb.append("    // Step 1: HOMEでゲーム復帰\n");
        sb.append("    pushButton(Button::HOME, 1000);\n");
        sb.append("    delay(2000); // ゲーム復帰待ち\n\n");

        // Step 2: マカ錬金メニューを進めて護石3個選択（投入確認ダイアログまで）
        sb.append("    // Step 2: ルームサービス → マカ錬金 → 護石3個選択\n");
        sb.append("    // 投入確認ダイアログまで進めてからStep 3で待機する\n");
        sb.append("    tiltLeftStick(Stick::MAX, Stick::NEUTRAL, 700);\n");
        sb.append("    pushButton(Button::A, 100); // 話しかけ\n");
        sb.append("    pushButton(Button::B, 250, 6); // 会話スキップ\n");
        sb.append("    pushButton(Button::A, 100);    // メニュー\n");
        sb.append("    pushHat(Hat::UP);              // マカフシギ錬金術\n");
        sb.append("    pushButton(Button::A, 10);\n");
        sb.append("    pushButton(Button::A, 10);     // 1番目の護石\n");
        sb.append("    pushHat(Hat::DOWN, 10);\n");
        sb.append("    pushButton(Button::A, 10);     // 2番目の護石\n");
        sb.append("    pushHat(Hat::DOWN, 10);\n");
        sb.append("    pushButton(Button::A, 10);     // 3番目の護石（→ 投入確認ダイアログ）\n");
        sb.append("    delay(1000);                    // ダイアログ表示の安定待ち\n\n");

        // Step 3: 投入確認ダイアログで待機
        sb.append("    // Step 3: 投入確認ダイアログで目標フレームまで待機 (%d ms)\n".formatted(waitMs));
        sb.append("    waitWithKeepAlive(wait_ms);\n\n");

        // Step 4: A連打で投入確定
        sb.append("    // Step 4: A連打で投入確定（このタイミングで乱数決定）\n");
        sb.append("    pushButton(Button::A, 100, 2); // 投入確定\n");
        sb.append("    pushButton(Button::B, 100, 5); // 会話終了\n\n");

        // Step 5: ケルビマラソン
        sb.append("    // Step 5: ケルビマラソン（鑑定用クエスト）\n");
        sb.append("    // ココット村受付嬢へダッシュ\n");
        sb.append("    pushButton(Button::B, 200, 3); // メニュークリア\n");
        sb.append("    SwitchControlLibrary().pressButton(Button::R);\n");
        sb.append("    SwitchControlLibrary().sendReport();\n");
        sb.append("    tiltLeftStick(Stick::MAX, Stick::MIN, 1500); // 右上にダッシュ\n");
        sb.append("    SwitchControlLibrary().releaseButton(Button::R);\n");
        sb.append("    SwitchControlLibrary().sendReport();\n");
        sb.append("    pushButton(Button::A, 250, 3); // 受付嬢に話しかけ\n");
        sb.append("    pushButton(Button::B, 250, 4); // 会話スキップ\n");
        sb.append("    delay(100);\n");
        sb.append("    pushHat(Hat::UP);              // 下位クエスト\n");
        sb.append("    pushButton(Button::A, 100);\n");
        sb.append("    pushHat(Hat::DOWN);            // Lv1\n");
        sb.append("    pushButton(Button::A, 100);\n");
        sb.append("    pushHat(Hat::DOWN, 50, 3);    // 森の中のケルビ\n");
        sb.append("    pushButton(Button::A, 50, 5); // 受注\n");
        sb.append("    pushButton(Button::B, 250, 4);\n\n");

        sb.append("    // クエスト出発\n");
        sb.append("    SwitchControlLibrary().pressButton(Button::R);\n");
        sb.append("    SwitchControlLibrary().sendReport();\n");
        sb.append("    tiltLeftStick(Stick::MIN, Stick::NEUTRAL, 800);\n");
        sb.append("    tiltLeftStick(Stick::NEUTRAL, Stick::MIN, 2300);\n");
        sb.append("    SwitchControlLibrary().releaseButton(Button::R);\n");
        sb.append("    SwitchControlLibrary().sendReport();\n");
        sb.append("    pushButton(Button::A, 50, 5);\n");
        sb.append("    delay(8700);\n\n");

        sb.append("    // ケルビの角を納品\n");
        sb.append("    pushButton(Button::PLUS, 250);\n");
        sb.append("    pushButton(Button::A, 250, 2);\n");
        sb.append("    pushHat(Hat::DOWN, 50, 2);\n");
        sb.append("    pushButton(Button::A, 250);\n");
        sb.append("    pushHat(Hat::RIGHT);\n");
        sb.append("    pushButton(Button::A, 250, 4);\n");
        sb.append("    delay(36000);\n\n");

        sb.append("    // 報酬売却 → セーブせず終了\n");
        sb.append("    pushHat(Hat::UP);\n");
        sb.append("    pushButton(Button::A, 250);\n");
        sb.append("    pushHat(Hat::LEFT);\n");
        sb.append("    pushButton(Button::A, 250, 5);\n");
        sb.append("    pushButton(Button::B, 250);\n");
        sb.append("    pushButton(Button::A, 250);\n");
        sb.append("    delay(7900);\n\n");

        // Step 6: 自宅で鑑定
        sb.append("    // Step 6: 自宅で鑑定\n");
        sb.append("    pushButton(Button::X, 250);\n");
        sb.append("    pushButton(Button::A, 250, 2);\n");
        sb.append("    delay(2000);\n");
        sb.append("    tiltLeftStick(Stick::MAX, Stick::NEUTRAL, 700);\n");
        sb.append("    pushButton(Button::A, 250, 2);\n");
        sb.append("    pushButton(Button::B, 250, 2);\n");
        sb.append("    delay(100);\n");
        sb.append("    pushButton(Button::A, 250);\n");
        sb.append("    pushHat(Hat::DOWN, 50, 3);\n");
        sb.append("    pushButton(Button::A, 250); // マカ錬金\n");
        sb.append("    pushHat(Hat::DOWN, 50);\n");
        sb.append("    pushButton(Button::A, 250, 2); // 鑑定\n");
        sb.append("}\n\n");
        sb.append("void loop() {}\n");

        return sb.toString();
    }

    // ================================================================
    // Calibration Tab
    // ================================================================
    JPanel buildCalibrationTab() {
        JPanel tab = new JPanel(new BorderLayout(8, 8));
        tab.setBackground(BG);
        tab.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        // 計算式の説明
        JPanel formulaPanel = titled("計算式");
        formulaPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 8));
        formulaPanel.add(new JLabel(
            "<html><body style='color:#e8e8e8;'>" +
            "<b style='font-size:14px;'>目標フレーム = 基本消費(F) + Continue1回消費(F) × 回数 + 0.030(F/ms) × 待機時間(ms)</b><br><br>" +
            "<span style='color:#8888aa;'>「基本消費」と「Continue1回消費」は環境ごとに異なるため、以下の手順で実測します。<br>" +
            "測定した値はArduinoタブの入力欄に自動反映されます。</span><br><br>" +
            "<span style='color:#ffc107;'>⚠ 精度に関する注意:</span><span style='color:#8888aa;'> Continue消費の測定は<b>Continue回数を大きくするほど精度が上がります</b>。<br>" +
            "例: Continue回数=10で測定した値を本番でContinue回数=400に使うと、1回あたり2〜3Fの誤差が800〜1200Fのズレに拡大します。<br>" +
            "本番のContinue回数が大きい場合（100以上）は、<b>50〜100回で測定する</b>ことを強く推奨します。</span>" +
            "</body></html>"));
        top.add(formulaPanel);

        // ステップ1: 基本消費の測定
        JPanel step1 = titled("ステップ1: 基本消費の測定（Continue 0回で実行）");
        step1.setLayout(new BoxLayout(step1, BoxLayout.Y_AXIS));

        JPanel s1r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        s1r1.setOpaque(false);
        s1r1.add(label("待機時間:"));
        JTextField calT2forC = makeField("10000", 10);
        calT2forC.setToolTipText("Continue 0回で実行する待機時間の値 (ms)");
        s1r1.add(calT2forC);
        s1r1.add(label("ms"));
        step1.add(s1r1);

        JPanel s1r1b = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        s1r1b.setOpaque(false);
        JButton calGenC = makeButton("テスト用コード生成 (Continue 0回)", GREEN);
        calGenC.setToolTipText("Continue 0回, 指定した待機時間でArduinoコードを生成");
        s1r1b.add(calGenC);
        step1.add(s1r1b);

        JPanel s1r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        s1r2.setOpaque(false);
        s1r2.add(label("実測フレーム:"));
        JTextField calActualC = makeField("", 12);
        calActualC.setToolTipText("出たお守りを周辺表示で照合して特定したフレーム番号");
        s1r2.add(calActualC);
        JButton calCBtn = makeButton("基本消費を計算", ACCENT);
        s1r2.add(calCBtn);
        JLabel calCResult = new JLabel("");
        calCResult.setFont(FONT_LARGE);
        calCResult.setForeground(SUCCESS);
        s1r2.add(calCResult);
        step1.add(s1r2);
        top.add(step1);

        // ステップ2: Continue消費の測定
        JPanel step2 = titled("ステップ2: Continue消費の測定（基本消費が確定した後 ─ Continue回数は大きいほど精度UP）");
        step2.setLayout(new BoxLayout(step2, BoxLayout.Y_AXIS));

        JPanel s2r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        s2r1.setOpaque(false);
        s2r1.add(label("Continue回数:"));
        JTextField calNcForFc = makeField("50", 6);
        calNcForFc.setToolTipText(
            "<html>テストで実行するContinue連打の回数。<br>" +
            "<b>大きいほど1回あたりの精度が上がります。</b><br>" +
            "本番でContinue回数100以上を使う場合は、50〜100回で測定することを推奨。</html>");
        s2r1.add(calNcForFc);
        s2r1.add(label("   待機時間:"));
        JTextField calT2forFc = makeField("10000", 10);
        calT2forFc.setToolTipText("テストで設定する待機時間の値 (ms)");
        s2r1.add(calT2forFc);
        s2r1.add(label("ms"));
        step2.add(s2r1);

        JPanel s2r1b = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        s2r1b.setOpaque(false);
        JButton calGenFc = makeButton("テスト用コード生成", GREEN);
        calGenFc.setToolTipText("指定したContinue回数と待機時間でArduinoコードを生成");
        s2r1b.add(calGenFc);
        step2.add(s2r1b);

        JPanel s2r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        s2r2.setOpaque(false);
        s2r2.add(label("実測フレーム:"));
        JTextField calActualFc = makeField("", 12);
        calActualFc.setToolTipText("出たお守りを周辺表示で照合して特定したフレーム番号");
        s2r2.add(calActualFc);
        JButton calFcBtn = makeButton("Continue消費を計算", ACCENT);
        s2r2.add(calFcBtn);
        JLabel calFcResult = new JLabel("");
        calFcResult.setFont(FONT_LARGE);
        calFcResult.setForeground(SUCCESS);
        s2r2.add(calFcResult);
        step2.add(s2r2);
        top.add(step2);

        JScrollPane topSp = new JScrollPane(top);
        setupScrollSpeed(topSp);
        topSp.setBorder(null);
        topSp.getViewport().setBackground(BG);

        // コードプレビュー（キャリブレーション専用）
        JTextArea calCodeArea = new JTextArea(15, 60);
        calCodeArea.setBackground(BG2);
        calCodeArea.setForeground(new Color(0x80, 0xff, 0x80));
        calCodeArea.setFont(FONT_MONO_SMALL);
        calCodeArea.setCaretColor(FG);
        calCodeArea.setEditable(false);
        calCodeArea.setTabSize(4);
        JScrollPane calCodeSp = new JScrollPane(calCodeArea);
        setupScrollSpeed(calCodeSp);
        calCodeSp.getViewport().setBackground(BG2);
        JPanel calCodePanel = titled("テスト用コード プレビュー");
        calCodePanel.setLayout(new BorderLayout());
        JButton calCodeCopyBtn = makeButton("クリップボードにコピー", BTN_BG);
        calCodeCopyBtn.addActionListener(e -> {
            String code = calCodeArea.getText();
            if (!code.isEmpty()) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(code), null);
                statusLabel.setText("コードをクリップボードにコピーしました");
            }
        });
        JPanel calCodeBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        calCodeBtnPanel.setOpaque(false);
        calCodeBtnPanel.add(calCodeCopyBtn);
        calCodePanel.add(calCodeBtnPanel, BorderLayout.NORTH);
        calCodePanel.add(calCodeSp, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topSp, calCodePanel);
        split.setBackground(BG);
        split.setDividerLocation(380);
        split.setResizeWeight(0.4);
        split.setContinuousLayout(true);
        tab.add(split, BorderLayout.CENTER);

        // ロジック
        double f2const = 0.030;

        calGenC.addActionListener(e -> {
            try {
                long waitMs = Long.parseLong(calT2forC.getText().trim());
                calCodeArea.setText(generateArduinoCode(0, waitMs));
                calCodeArea.setCaretPosition(0);
                statusLabel.setText("キャリブレーション用コード生成: Continue 0回, 待機時間=%dms".formatted(waitMs));
            } catch (NumberFormatException ex) {
                statusLabel.setText("待機時間の値を正しく入力してください");
            }
        });

        calGenFc.addActionListener(e -> {
            try {
                int nc = Integer.parseInt(calNcForFc.getText().trim());
                long waitMs = Long.parseLong(calT2forFc.getText().trim());
                calCodeArea.setText(generateArduinoCode(nc, waitMs));
                calCodeArea.setCaretPosition(0);
                statusLabel.setText("キャリブレーション用コード生成: Continue %d回, 待機時間=%dms".formatted(nc, waitMs));
            } catch (NumberFormatException ex) {
                statusLabel.setText("数値を正しく入力してください");
            }
        });

        calCBtn.addActionListener(e -> {
            try {
                double t2 = Double.parseDouble(calT2forC.getText().trim());
                long actual = Long.parseLong(calActualC.getText().trim());
                double calcC = actual - f2const * t2;
                long roundedC = Math.round(calcC);
                calCResult.setText("基本消費 = %d  (%.1f)".formatted(roundedC, calcC));
                arduinoC.setText(String.valueOf(roundedC));
                statusLabel.setText("キャリブレーション: 基本消費フレーム = " + roundedC + " をArduinoタブに反映しました");
            } catch (NumberFormatException ex) {
                calCResult.setText("数値を入力してください");
            }
        });
        calActualC.addActionListener(calCBtn.getActionListeners()[0]);

        calFcBtn.addActionListener(e -> {
            try {
                int nc = Integer.parseInt(calNcForFc.getText().trim());
                double t2 = Double.parseDouble(calT2forFc.getText().trim());
                long actual = Long.parseLong(calActualFc.getText().trim());
                double C = Double.parseDouble(arduinoC.getText().trim());
                if (nc <= 0) { calFcResult.setText("Continue回数は1以上にしてください"); return; }
                double calcFc = (actual - C - f2const * t2) / nc;
                long roundedFc = (long) calcFc; // 切り捨て（Nc最小＝待機時間最大で精度確保）
                calFcResult.setText("Continue消費 = %d  (%.1f)".formatted(roundedFc, calcFc));
                arduinoFc.setText(String.valueOf(roundedFc));
                statusLabel.setText("キャリブレーション: Continue1回消費 = " + roundedFc + " をArduinoタブに反映しました");
            } catch (NumberFormatException ex) {
                calFcResult.setText("数値を入力してください");
            }
        });
        calActualFc.addActionListener(calFcBtn.getActionListeners()[0]);

        return tab;
    }

    /** 検索結果からArduinoタブへ連動: フレーム設定→自動計算→タブ切り替え */
    void generateArduinoForFrame(long frame) {
        arduinoTarget.setText(String.valueOf(frame));
        tabs.setSelectedIndex(5); // Arduinoタブ
        // 計算ボタンをプログラム的にクリック
        SwingUtilities.invokeLater(() -> arduinoCalcBtn.doClick());
    }

    /** テーブルに右クリックメニュー「Arduinoコード生成」を追加するヘルパー */
    void addArduinoContextMenu(JTable table, DefaultTableModel model, int frameColumnIndex) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem menuItem = new JMenuItem("▶ Arduinoコード生成");
        menuItem.setFont(FONT_UI);
        menuItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int modelRow = table.convertRowIndexToModel(row);
                Object val = model.getValueAt(modelRow, frameColumnIndex);
                try {
                    long frame = Long.parseLong(val.toString());
                    generateArduinoForFrame(frame);
                } catch (NumberFormatException ignored) {}
            }
        });
        popup.add(menuItem);

        // 周辺表示へジャンプも右クリックメニューに追加
        JMenuItem aroundItem = new JMenuItem("🔍 周辺表示へジャンプ");
        aroundItem.setFont(FONT_UI);
        aroundItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int modelRow = table.convertRowIndexToModel(row);
                Object val = model.getValueAt(modelRow, frameColumnIndex);
                aroundFrame.setText(val.toString());
                tabs.setSelectedIndex(1);
                showAround();
            }
        });
        popup.add(aroundItem);

        table.setComponentPopupMenu(popup);
        // 右クリックでも行を選択する
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        table.setRowSelectionInterval(row, row);
                    }
                }
            }
        });
    }

    // ================================================================
    // Famous Tab
    // ================================================================
    JPanel buildFamousTab() {
        JPanel tab = new JPanel(new BorderLayout(8,8));
        tab.setBackground(BG);
        tab.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        JLabel info = new JLabel("有名なお守りリスト（風化したお守り・マカ錬金）─ ダブルクリックで周辺表示タブへ");
        info.setFont(FONT_SMALL);
        info.setForeground(FG);
        tab.add(info, BorderLayout.NORTH);

        famousModel = new DefaultTableModel(
                new String[]{"フレーム","第1スキル","SP1","第2スキル","SP2","スロット","待ち時間","当たりF","種類"}, 0);
        for (Object[] fc : FAMOUS_CHARMS) {
            famousModel.addRow(new Object[]{fc[0], fc[1], fc[2], fc[3], fc[4], fc[5], fc[6], fc[7], fc[8]});
        }
        famousTable = makeTable(famousModel);
        famousTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = famousTable.getSelectedRow();
                    if (row >= 0) {
                        int modelRow = famousTable.convertRowIndexToModel(row);
                        Object val = famousModel.getValueAt(modelRow, 0);
                        data.setBlue();
                        searchKind.setSelectedItem("風化したお守り");
                        onKindChange();
                        aroundFrame.setText(val.toString());
                        tabs.setSelectedIndex(1);
                        showAround();
                    }
                }
            }
        });
        addArduinoContextMenu(famousTable, famousModel, 0);
        JScrollPane sp = new JScrollPane(famousTable);
        setupScrollSpeed(sp);
        sp.getViewport().setBackground(BG2);
        tab.add(sp, BorderLayout.CENTER);

        return tab;
    }

    // ================================================================
    // Main
    // ================================================================
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}

        // ダークテーマを UIManager レベルで徹底適用
        UIManager.put("Panel.background", BG);
        UIManager.put("Panel.foreground", FG);
        UIManager.put("Label.foreground", FG);
        UIManager.put("TextField.background", BG2);
        UIManager.put("TextField.foreground", FG);
        UIManager.put("TextField.caretForeground", FG);
        UIManager.put("TextArea.background", BG2);
        UIManager.put("TextArea.foreground", FG);
        UIManager.put("TextArea.caretForeground", FG);
        UIManager.put("ComboBox.background", BG2);
        UIManager.put("ComboBox.foreground", FG);
        UIManager.put("ComboBox.disabledBackground", BG2.darker());
        UIManager.put("ComboBox.disabledForeground", DIM);
        UIManager.put("ComboBox.selectionBackground", ACCENT2);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("List.background", BG2);
        UIManager.put("List.foreground", FG);
        UIManager.put("List.selectionBackground", ACCENT2);
        UIManager.put("List.selectionForeground", Color.WHITE);
        UIManager.put("Table.background", BG2);
        UIManager.put("Table.foreground", FG);
        UIManager.put("Table.gridColor", new Color(0x33,0x33,0x55));
        UIManager.put("Table.selectionBackground", ACCENT2);
        UIManager.put("Table.selectionForeground", Color.WHITE);
        UIManager.put("TableHeader.background", ACCENT2);
        UIManager.put("TableHeader.foreground", Color.WHITE);
        UIManager.put("ScrollPane.background", BG);
        UIManager.put("ScrollBar.background", BG2);
        UIManager.put("ScrollBar.thumb", ACCENT2);
        UIManager.put("Viewport.background", BG2);
        UIManager.put("TabbedPane.background", BG);
        UIManager.put("TabbedPane.foreground", FG);
        UIManager.put("TabbedPane.selected", ACCENT2);
        UIManager.put("TabbedPane.contentAreaColor", BG);
        UIManager.put("TabbedPane.light", ACCENT2);
        UIManager.put("TabbedPane.shadow", BG);
        UIManager.put("TabbedPane.darkShadow", BG);
        UIManager.put("TitledBorder.titleColor", ACCENT_T);
        UIManager.put("Button.background", BTN_BG);
        UIManager.put("Button.foreground", Color.WHITE);
        UIManager.put("OptionPane.background", BG);
        UIManager.put("OptionPane.foreground", FG);
        UIManager.put("OptionPane.messageForeground", FG);
        UIManager.put("ProgressBar.background", BG2);
        UIManager.put("ProgressBar.foreground", SUCCESS);
        UIManager.put("ProgressBar.selectionBackground", FG);
        UIManager.put("ProgressBar.selectionForeground", BG2);
        UIManager.put("FileChooser.background", BG);
        UIManager.put("FileChooser.foreground", FG);
        UIManager.put("ToolTip.background", new Color(0x2a, 0x2a, 0x4e));
        UIManager.put("ToolTip.foreground", FG);

        SwingUtilities.invokeLater(() -> {
            try {
                MHXXCharmApp app = new MHXXCharmApp();
                app.setVisible(true);
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null,
                        "起動エラー:\n" + e.getMessage() + "\n\nJava 8以上が必要です。",
                        "エラー", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    // ================================================================
    // Quest Loop Tab (Phase 1-B: クエスト自動周回スケッチ生成)
    // ================================================================
    JPanel buildQuestLoopTab() {
        JPanel tab = new JPanel(new BorderLayout(8,8));
        tab.setBackground(BG);
        tab.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        // 上部：パラメータ入力
        JPanel top = new JPanel();
        top.setBackground(BG);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel inputPanel = titled("クエスト自動周回スケッチ生成 (G★2 火山の採集ツアー)");
        inputPanel.setLayout(new BoxLayout(inputPanel, BoxLayout.Y_AXIS));

        // 説明
        JPanel descRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        descRow.setOpaque(false);
        JLabel desc = new JLabel(
            "<html><body style='width:800px; color:#8888aa;'>" +
            "G★2「火山の採集ツアー」を自動周回するArduinoスケッチを生成します。<br>" +
            "<b>想定フロー:</b> 結果画面→受注→BC→エリア4採掘→モドリ玉→納品→報酬→結果画面(ループ)<br>" +
            "<b>前提:</b> モドリ玉・ピッケル複数本・クーラードリンクを持参／オトモなし推奨／" +
            "1周目はユーザが手動クリア。<br>" +
            "<b>注意:</b> デフォルト値は机上推定。実機で少しずつ調整すること。" +
            "</body></html>");
        desc.setFont(FONT_SMALL);
        descRow.add(desc);
        inputPanel.add(descRow);

        // パラメータ入力フィールドたち
        QuestSketchParams defaults = new QuestSketchParams();

        JTextField fResultA = makeField(String.valueOf(defaults.resultScreenAPressCount), 6);
        JTextField fAInterval = makeField(String.valueOf(defaults.aPressIntervalMs), 6);
        JTextField fBCWait = makeField(String.valueOf(defaults.bcStartWaitMs), 6);
        JTextField fBC2A4 = makeField(String.valueOf(defaults.bcToArea4TravelMs), 6);
        JTextField fAreaLoad = makeField(String.valueOf(defaults.areaLoadWaitMs), 6);
        JTextField fA4Inner = makeField(String.valueOf(defaults.area4InnerMoveMs), 6);
        JTextField fDigCount = makeField(String.valueOf(defaults.diggingAPressCount), 6);
        JTextField fDigInterval = makeField(String.valueOf(defaults.diggingAPressIntervalMs), 6);
        JTextField fModoriDown = makeField(String.valueOf(defaults.modoriDaArrowDownCount), 6);
        JTextField fModoriWait = makeField(String.valueOf(defaults.modoriDaUseWaitMs), 6);
        JTextField fDeliveryMove = makeField(String.valueOf(defaults.deliveryBoxTravelMs), 6);
        JTextField fDeliveryA = makeField(String.valueOf(defaults.deliveryAPressCount), 6);
        JTextField fRewardA = makeField(String.valueOf(defaults.rewardScreenAPressCount), 6);

        // Row 1: 結果画面関連
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r1.setOpaque(false);
        r1.add(label("【結果画面】Aボタン連打回数:"));
        r1.add(fResultA);
        r1.add(label("  A連打間隔(ms):"));
        r1.add(fAInterval);
        inputPanel.add(r1);

        // Row 2: BC起動・移動
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r2.setOpaque(false);
        r2.add(label("【BC】起動待機(ms):"));
        r2.add(fBCWait);
        r2.add(label("  BC→エリア4走行(ms):"));
        r2.add(fBC2A4);
        r2.add(label("  エリア切替ロード(ms):"));
        r2.add(fAreaLoad);
        inputPanel.add(r2);

        // Row 3: 採掘関連
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r3.setOpaque(false);
        r3.add(label("【採掘】エリア4内移動(ms):"));
        r3.add(fA4Inner);
        r3.add(label("  A連打回数:"));
        r3.add(fDigCount);
        r3.add(label("  A連打間隔(ms):"));
        r3.add(fDigInterval);
        inputPanel.add(r3);

        // Row 4: モドリ玉
        JPanel r4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r4.setOpaque(false);
        r4.add(label("【モドリ玉】↓キー回数(実機調整):"));
        r4.add(fModoriDown);
        r4.add(label("  使用後BC帰還待機(ms):"));
        r4.add(fModoriWait);
        inputPanel.add(r4);

        // Row 5: 納品・ループ
        JPanel r5 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r5.setOpaque(false);
        r5.add(label("【納品】納品ボックス移動(ms):"));
        r5.add(fDeliveryMove);
        r5.add(label("  A連打回数:"));
        r5.add(fDeliveryA);
        r5.add(label("  【報酬画面】A連打:"));
        r5.add(fRewardA);
        inputPanel.add(r5);

        // 生成ボタン
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        btnRow.setOpaque(false);
        JButton genBtn = makeButton("▶ スケッチを生成", ACCENT);
        btnRow.add(genBtn);
        JButton copyBtn = makeButton("📋 クリップボードにコピー", BTN_BG);
        btnRow.add(copyBtn);
        JButton resetBtn = makeButton("↻ デフォルトに戻す", BTN_BG);
        btnRow.add(resetBtn);
        inputPanel.add(btnRow);

        top.add(inputPanel);
        tab.add(top, BorderLayout.NORTH);

        // 出力エリア
        JTextArea outputArea = new JTextArea();
        outputArea.setFont(FONT_MONO_SMALL);
        outputArea.setBackground(BG2);
        outputArea.setForeground(FG);
        outputArea.setCaretColor(FG);
        outputArea.setEditable(true);
        outputArea.setTabSize(2);
        outputArea.setText("▶ スケッチを生成 ボタンを押すとここにコードが表示されます。");
        JScrollPane outScroll = new JScrollPane(outputArea);
        setupScrollSpeed(outScroll);
        JPanel outPanel = titled("生成されたArduinoスケッチ (Arduino IDEにコピペして使用)");
        outPanel.setLayout(new BorderLayout());
        outPanel.add(outScroll, BorderLayout.CENTER);
        tab.add(outPanel, BorderLayout.CENTER);

        // 生成ボタンのアクション
        genBtn.addActionListener(e -> {
            try {
                QuestSketchParams p = new QuestSketchParams();
                p.resultScreenAPressCount = Integer.parseInt(fResultA.getText().trim());
                p.aPressIntervalMs = Integer.parseInt(fAInterval.getText().trim());
                p.bcStartWaitMs = Integer.parseInt(fBCWait.getText().trim());
                p.bcToArea4TravelMs = Integer.parseInt(fBC2A4.getText().trim());
                p.areaLoadWaitMs = Integer.parseInt(fAreaLoad.getText().trim());
                p.area4InnerMoveMs = Integer.parseInt(fA4Inner.getText().trim());
                p.diggingAPressCount = Integer.parseInt(fDigCount.getText().trim());
                p.diggingAPressIntervalMs = Integer.parseInt(fDigInterval.getText().trim());
                p.modoriDaArrowDownCount = Integer.parseInt(fModoriDown.getText().trim());
                p.modoriDaUseWaitMs = Integer.parseInt(fModoriWait.getText().trim());
                p.deliveryBoxTravelMs = Integer.parseInt(fDeliveryMove.getText().trim());
                p.deliveryAPressCount = Integer.parseInt(fDeliveryA.getText().trim());
                p.rewardScreenAPressCount = Integer.parseInt(fRewardA.getText().trim());

                String sketch = generateQuestSketch(p);
                outputArea.setText(sketch);
                outputArea.setCaretPosition(0);
                statusLabel.setText("スケッチ生成完了");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(tab,
                    "数値を正しく入力してください: " + ex.getMessage(),
                    "入力エラー", JOptionPane.ERROR_MESSAGE);
            }
        });

        // コピーボタン
        copyBtn.addActionListener(e -> {
            String text = outputArea.getText();
            if (text.isEmpty() || text.startsWith("▶")) {
                JOptionPane.showMessageDialog(tab,
                    "先に「スケッチを生成」を押してください",
                    "情報", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
            statusLabel.setText("クリップボードにコピーしました");
        });

        // リセットボタン
        resetBtn.addActionListener(e -> {
            QuestSketchParams d = new QuestSketchParams();
            fResultA.setText(String.valueOf(d.resultScreenAPressCount));
            fAInterval.setText(String.valueOf(d.aPressIntervalMs));
            fBCWait.setText(String.valueOf(d.bcStartWaitMs));
            fBC2A4.setText(String.valueOf(d.bcToArea4TravelMs));
            fAreaLoad.setText(String.valueOf(d.areaLoadWaitMs));
            fA4Inner.setText(String.valueOf(d.area4InnerMoveMs));
            fDigCount.setText(String.valueOf(d.diggingAPressCount));
            fDigInterval.setText(String.valueOf(d.diggingAPressIntervalMs));
            fModoriDown.setText(String.valueOf(d.modoriDaArrowDownCount));
            fModoriWait.setText(String.valueOf(d.modoriDaUseWaitMs));
            fDeliveryMove.setText(String.valueOf(d.deliveryBoxTravelMs));
            fDeliveryA.setText(String.valueOf(d.deliveryAPressCount));
            fRewardA.setText(String.valueOf(d.rewardScreenAPressCount));
            statusLabel.setText("デフォルト値に戻しました");
        });

        return tab;
    }

    // ================================================================
    // Combo Snipe Tab UI
    // ================================================================
    JPanel buildComboSnipeTab() {
        JPanel tab = new JPanel(new BorderLayout(8,8));
        tab.setBackground(BG);
        tab.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        JPanel settings = titled("調合スナイプ（Lv2通常弾の個数列から現在フレーム特定）");
        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));

        // 説明
        JPanel descRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        descRow.setOpaque(false);
        JLabel desc = new JLabel(
            "<html><body style='width:800px; color:#8888aa;'>" +
            "<b>★ココット村でセーブ必須★</b>（ロード後は村スタート → コードが自宅へ移動します）<br>" +
            "ハリの実 + カラの実 を<b>各15個以上</b>用意し、調合書を持って自宅で<br>" +
            "<b>Lv2通常弾を連続調合</b>。Switch録画で弾数の変化を記録し、現在フレームを逆算。<br>" +
            "前提: ルームサービス=モガの村の看板娘、ペット/オトモなし。<br>" +
            "<b>入力形式:</b> 累積弾数（0始まり、例: <code>0 2 4 7 10 13 16</code>）<br>" +
            "　　 or 個数列（各回の調合数、例: <code>2,2,3,3,3,3</code>）— 自動判定します。" +
            "</body></html>");
        desc.setFont(FONT_SMALL);
        descRow.add(desc);
        settings.add(descRow);

        // 個数列入力
        comboCountsField = makeField("", 60);
        comboCountsField.setToolTipText("累積弾数（0始まり）or 個数列（2,3,4）を入力。自動判定します");

        // クリック入力UI（録画を見ながらボタンで累積）
        JPanel clickRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        clickRow.setOpaque(false);
        clickRow.add(label("クリック入力:"));
        JButton plus2Btn = makeButton(" +2 ", BTN_BG);
        plus2Btn.setToolTipText("次の調合で+2 (キーボード: 2)");
        JButton plus3Btn = makeButton(" +3 ", BTN_BG);
        plus3Btn.setToolTipText("次の調合で+3 (キーボード: 3)");
        JButton plus4Btn = makeButton(" +4 ", BTN_BG);
        plus4Btn.setToolTipText("次の調合で+4 (キーボード: 4)");
        JButton undoBtn = makeButton("← 取消", BTN_BG);
        undoBtn.setToolTipText("直前の入力を取消 (キーボード: Backspace)");
        JButton clearBtn = makeButton("クリア", BTN_BG);
        clearBtn.setToolTipText("入力をリセット");
        clickRow.add(plus2Btn);
        clickRow.add(plus3Btn);
        clickRow.add(plus4Btn);
        clickRow.add(new JLabel("  "));
        clickRow.add(undoBtn);
        clickRow.add(clearBtn);
        JLabel clickStatusLabel = new JLabel("（0回入力）");
        clickStatusLabel.setForeground(new java.awt.Color(0x88, 0x88, 0xaa));
        clickRow.add(clickStatusLabel);
        settings.add(clickRow);

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row1.setOpaque(false);
        row1.add(label("累積弾数 or 個数列:"));
        row1.add(comboCountsField);
        settings.add(row1);

        // クリック入力のロジック
        // 累積列を内部状態として管理。テキストフィールドと同期させる。
        java.util.List<Integer> cumulativeList = new java.util.ArrayList<>();
        cumulativeList.add(0); // 初期値0
        // 既にデフォルト値がテキストフィールドにあるので、それを初期累積列としてパース
        try {
            String defaultText = comboCountsField.getText().trim();
            String[] defaultParts = defaultText.split("[,，\\s、]+");
            int[] parsed = new int[defaultParts.length];
            for (int i = 0; i < defaultParts.length; i++) parsed[i] = Integer.parseInt(defaultParts[i].trim());
            // 累積列として妥当か確認
            boolean isCum = parsed.length >= 1 && parsed[0] == 0;
            if (isCum) {
                for (int i = 1; i < parsed.length; i++) {
                    if (parsed[i] <= parsed[i-1]) { isCum = false; break; }
                }
            }
            if (isCum) {
                cumulativeList.clear();
                for (int v : parsed) cumulativeList.add(v);
            }
        } catch (NumberFormatException ignored) {}

        Runnable syncToField = () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cumulativeList.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(cumulativeList.get(i));
            }
            comboCountsField.setText(sb.toString());
            int rolls = cumulativeList.size() - 1;
            if (rolls < 0) rolls = 0;
            clickStatusLabel.setText("（" + rolls + "回入力, 累積=" 
                + (cumulativeList.isEmpty() ? "-" : cumulativeList.get(cumulativeList.size() - 1)) + "）");
        };
        // 初期同期: comboCountsFieldは空欄で開始したいので書き換えず、ステータスラベルのみ更新
        {
            int rolls = cumulativeList.size() - 1;
            if (rolls < 0) rolls = 0;
            clickStatusLabel.setText("（" + rolls + "回入力, 累積=" 
                + (cumulativeList.isEmpty() ? "-" : cumulativeList.get(cumulativeList.size() - 1)) + "）");
        }

        java.util.function.IntConsumer addAmount = amount -> {
            int last = cumulativeList.isEmpty() ? 0 : cumulativeList.get(cumulativeList.size() - 1);
            cumulativeList.add(last + amount);
            syncToField.run();
        };

        plus2Btn.addActionListener(e -> addAmount.accept(2));
        plus3Btn.addActionListener(e -> addAmount.accept(3));
        plus4Btn.addActionListener(e -> addAmount.accept(4));

        undoBtn.addActionListener(e -> {
            if (cumulativeList.size() > 1) {
                cumulativeList.remove(cumulativeList.size() - 1);
                syncToField.run();
            }
        });

        clearBtn.addActionListener(e -> {
            cumulativeList.clear();
            cumulativeList.add(0);
            syncToField.run();
        });

        // キーボードショートカット（タブ全体に対して、テキストフィールド以外のフォーカス時のみ）
        // KeyBindingsを使ってWHEN_IN_FOCUSED_WINDOWで反応させる
        InputMap inputMap = tab.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = tab.getActionMap();
        // 数字2,3,4 + Backspace
        inputMap.put(KeyStroke.getKeyStroke('2'), "add2");
        inputMap.put(KeyStroke.getKeyStroke('3'), "add3");
        inputMap.put(KeyStroke.getKeyStroke('4'), "add4");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "undo");
        actionMap.put("add2", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                // テキストフィールドにフォーカスがある時は通常入力を優先
                java.awt.Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focused == comboCountsField) return;
                addAmount.accept(2);
            }
        });
        actionMap.put("add3", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                java.awt.Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focused == comboCountsField) return;
                addAmount.accept(3);
            }
        });
        actionMap.put("add4", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                java.awt.Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focused == comboCountsField) return;
                addAmount.accept(4);
            }
        });
        actionMap.put("undo", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                java.awt.Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focused == comboCountsField) return;
                if (cumulativeList.size() > 1) {
                    cumulativeList.remove(cumulativeList.size() - 1);
                    syncToField.run();
                }
            }
        });

        // テキストフィールド側で手入力した時は内部リストを再同期する
        comboCountsField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                String input = comboCountsField.getText().trim();
                String[] parts = input.split("[,，\\s、]+");
                java.util.List<Integer> newList = new java.util.ArrayList<>();
                try {
                    int[] parsed = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) parsed[i] = Integer.parseInt(parts[i].trim());
                    boolean isCum = parsed.length >= 1 && parsed[0] == 0;
                    if (isCum) {
                        for (int i = 1; i < parsed.length; i++) {
                            if (parsed[i] <= parsed[i-1]) { isCum = false; break; }
                        }
                    }
                    if (isCum) {
                        for (int v : parsed) newList.add(v);
                        cumulativeList.clear();
                        cumulativeList.addAll(newList);
                        // ステータスのみ更新（テキスト再書き込みは不要）
                        int rolls = cumulativeList.size() - 1;
                        if (rolls < 0) rolls = 0;
                        clickStatusLabel.setText("（" + rolls + "回入力, 累積=" 
                            + cumulativeList.get(cumulativeList.size() - 1) + "）");
                    } else {
                        // 個数列モード：内部累積リストとは切り離す
                        clickStatusLabel.setText("（個数列モード - クリック入力は累積列専用）");
                    }
                } catch (NumberFormatException ignored) {
                    clickStatusLabel.setText("（入力が不正）");
                }
            }
        });

        // 検索範囲 + 目標F + ボタン
        JTextField rangeField = makeField("1000000", 12);
        rangeField.setToolTipText(
            "<html>検索するフレーム数の上限。<br>" +
            "目標Fを入力した場合は自動で「目標F+マージン」に上書きされる。<br>" +
            "範囲が広すぎると偽マッチが大量発生する点に注意。</html>");
        comboTargetFField = makeField("", 12);
        comboTargetFField.setToolTipText(
            "<html><b>狙うお守りのフレーム位置（任意）</b><br>" +
            "入力すると検索範囲を「目標F+10万」に自動設定し、偽マッチを大幅削減。<br>" +
            "候補ハイライト（目標F以下で最も近い候補を緑）にも使う。</html>");
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row2.setOpaque(false);
        row2.add(label("検索範囲:"));
        row2.add(rangeField);
        row2.add(label("F  目標F:"));
        row2.add(comboTargetFField);
        JButton searchBtn = makeButton("▶ 逆算開始", ACCENT);
        row2.add(searchBtn);
        JButton cancelBtn = makeButton("■ 中止", BTN_BG);
        row2.add(cancelBtn);
        settings.add(row2);

        // 観測数推定行
        JPanel row2b = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row2b.setOpaque(false);
        JLabel estimateLabel = new JLabel("");
        estimateLabel.setForeground(new java.awt.Color(0x88, 0x88, 0xaa));
        row2b.add(estimateLabel);
        settings.add(row2b);

        // 入力変化に応じた推定候補数の更新
        Runnable updateEstimate = () -> {
            try {
                String input = comboCountsField.getText().trim();
                String[] parts = input.split("[,，\\s、]+");
                int observed;
                // 累積列か個数列かを推定。0始まりなら累積（観測数 = 要素数 - 1）
                if (parts.length >= 2 && parts[0].trim().equals("0")) {
                    observed = parts.length - 1;
                } else {
                    observed = parts.length;
                }
                long range;
                String tgtStr = comboTargetFField.getText().trim();
                if (!tgtStr.isEmpty()) {
                    try {
                        long tgt = Long.parseLong(tgtStr);
                        range = tgt + 100_000;
                    } catch (NumberFormatException nfe) {
                        range = Long.parseLong(rangeField.getText().trim());
                    }
                } else {
                    range = Long.parseLong(rangeField.getText().trim());
                }
                // 1/3^N で偽マッチ予測（理論値）
                double expectedFalse = (double)range / Math.pow(3, observed);
                // 推奨観測数: 期待偽マッチ < 0.5 になる N
                int recommendN = Math.max(1, (int)Math.ceil(Math.log(2.0 * range) / Math.log(3.0)));
                String color = expectedFalse < 1 ? "#88cc88" : (expectedFalse < 10 ? "#cccc88" : "#cc8888");
                estimateLabel.setText(String.format(
                    "<html>観測数 %d 回 / 検索範囲 %,d F → 想定候補数 <span style='color:%s;'>約 %s 件</span>　推奨観測数: %d 回以上</html>",
                    observed, range, color, 
                    expectedFalse < 1 ? String.format("%.2f", expectedFalse) : String.format("%.0f", expectedFalse),
                    recommendN));
            } catch (NumberFormatException ex) {
                estimateLabel.setText("（入力を読み取れません）");
            }
        };
        comboCountsField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateEstimate.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateEstimate.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateEstimate.run(); }
        });
        comboTargetFField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { 
                // 目標F入力時、検索範囲を自動更新
                String tgtStr = comboTargetFField.getText().trim();
                if (!tgtStr.isEmpty()) {
                    try {
                        long tgt = Long.parseLong(tgtStr);
                        // 目標Fを超えている可能性も考慮し、最低1億Fは確保
                        long autoRange = Math.max(tgt + 100_000, 100_000_000L);
                        rangeField.setText(String.valueOf(autoRange));
                    } catch (NumberFormatException ignored) {}
                }
                updateEstimate.run();
            }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { 
                String tgtStr = comboTargetFField.getText().trim();
                if (!tgtStr.isEmpty()) {
                    try {
                        long tgt = Long.parseLong(tgtStr);
                        long autoRange = Math.max(tgt + 100_000, 100_000_000L);
                        rangeField.setText(String.valueOf(autoRange));
                    } catch (NumberFormatException ignored) {}
                }
                updateEstimate.run();
            }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateEstimate.run(); }
        });
        rangeField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateEstimate.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateEstimate.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateEstimate.run(); }
        });
        // 初回呼び出し
        SwingUtilities.invokeLater(updateEstimate);

        // 結果サマリ
        JLabel summaryLabel = new JLabel("");
        summaryLabel.setFont(FONT_LARGE);
        summaryLabel.setForeground(SUCCESS);
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row3.setOpaque(false);
        row3.add(summaryLabel);
        settings.add(row3);

        tab.add(settings, BorderLayout.NORTH);

        // 結果テーブル
        DefaultTableModel comboModel = new DefaultTableModel(
                new String[]{"フレーム","消費後フレーム","待ち時間","→周辺のお守り（マカ錬金用）"}, 0);
        JTable comboTable = makeTable(comboModel);
        comboTable.setToolTipText(
            "<html>ダブルクリック→周辺表示にジャンプ / 右クリック→Arduinoコード生成<br>" +
            "<b>「調合Arduino」タブの「現在F」には「消費後フレーム」列の値を入力</b></html>");
        comboTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = comboTable.getSelectedRow();
                    if (row >= 0) {
                        int modelRow = comboTable.convertRowIndexToModel(row);
                        Object val = comboModel.getValueAt(modelRow, 0);
                        aroundFrame.setText(val.toString());
                        aroundOrigin.setSelectedIndex(0); // マカ錬金
                        tabs.setSelectedIndex(1);
                        showAround();
                    }
                }
            }
        });
        addArduinoContextMenu(comboTable, comboModel, 0);

        JScrollPane sp = new JScrollPane(comboTable);
        setupScrollSpeed(sp);
        sp.getViewport().setBackground(BG2);
        JPanel rp = titled("候補フレーム（調合個数列が一致するフレーム）");
        rp.setLayout(new BorderLayout());
        rp.add(sp, BorderLayout.CENTER);
        tab.add(rp, BorderLayout.CENTER);

        // 検索ボタンのアクション
        searchBtn.addActionListener(e -> {
            cancelFlag.set(false);
            comboModel.setRowCount(0);

            // 個数列のパース（カンマ・スペース・日本語句読点も許容）
            // 累積列（0始まり、単調増加）と個数列（各要素2〜4）を自動判定
            String input = comboCountsField.getText().trim();
            String[] parts = input.split("[,，\\s、]+");
            int[] counts;
            try {
                int[] raw = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    raw[i] = Integer.parseInt(parts[i].trim());
                }

                // 累積列判定: 先頭が0で単調増加ならば累積列
                boolean isCumulative = raw.length >= 3 && raw[0] == 0;
                if (isCumulative) {
                    for (int i = 1; i < raw.length; i++) {
                        if (raw[i] <= raw[i-1]) { isCumulative = false; break; }
                    }
                }

                if (isCumulative) {
                    // 累積列 → 差分に変換
                    counts = new int[raw.length - 1];
                    for (int i = 0; i < counts.length; i++) {
                        counts[i] = raw[i+1] - raw[i];
                        if (counts[i] < 2 || counts[i] > 4) {
                            throw new NumberFormatException(
                                "累積列の差分が範囲外: " + raw[i] + "→" + raw[i+1] + " (差=" + counts[i] + ")");
                        }
                    }
                    summaryLabel.setText("累積列として解釈（" + counts.length + "回分）");
                    summaryLabel.setForeground(FG);
                } else {
                    // 個数列として解釈
                    counts = raw;
                    for (int c : counts) {
                        if (c < 2 || c > 4) {
                            throw new NumberFormatException("個数は2, 3, 4のみ有効: " + c);
                        }
                    }
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(tab,
                    "入力を正しく入力してください。\n" +
                    "個数列: 2,3,4をカンマ/スペース区切り（例: 3,3,2,4,3）\n" +
                    "累積列: 0始まりの累積弾数（例: 0 2 4 7 10 13）\n\n" + ex.getMessage(),
                    "入力エラー", JOptionPane.ERROR_MESSAGE);
                return;
            }

            long maxF;
            try {
                maxF = Long.parseLong(rangeField.getText().trim());
                if (maxF <= 0) throw new NumberFormatException("検索範囲は正の値");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(tab, "検索範囲は数値で入力してください",
                    "入力エラー", JOptionPane.ERROR_MESSAGE);
                return;
            }

            final int[] fCounts = counts;
            final long fMaxF = maxF;

            CharmData cd = new CharmData();
            cd.setBlue(); // 風化したお守り

            statusLabel.setText("調合スナイプ逆算中...");
            progressBar.setValue(0);
            progressBar.setVisible(true);
            searchBtn.setEnabled(false);

            Thread.ofVirtual().start(() -> {
                long t0 = System.currentTimeMillis();
                List<Long> results = reverseSearchCombo(fCounts, fMaxF, cancelFlag);

                SwingUtilities.invokeLater(() -> {
                    int consumedPerRoll = 5;
                    int totalConsumed = fCounts.length * consumedPerRoll;
                    for (Long f : results) {
                        long afterFrame = f + totalConsumed;
                        // 消費後のフレームでマカ錬金のお守りを計算
                        RNG charmRng = new RNG();
                        charmRng.jump(afterFrame);
                        Charm charm = getCharm(charmRng, cd, 0); // マカ錬金

                        String charmStr = charm.s1Name() + charm.sp1()
                            + (charm.s2Name() != null ? " " + charm.s2Name() + charm.sp2() : "")
                            + " s" + charm.slot();

                        comboModel.addRow(new Object[]{
                            f, afterFrame, framesToTime(f), charmStr
                        });
                    }

                    double elapsed = (System.currentTimeMillis() - t0) / 1000.0;
                    progressBar.setVisible(false);
                    searchBtn.setEnabled(true);
                    statusLabel.setText(String.format("調合スナイプ完了: %d件 (%.2f秒)",
                        results.size(), elapsed));

                    // 目標F取得（任意）
                    Long targetF = null;
                    String tgtStr = comboTargetFField.getText().trim();
                    if (!tgtStr.isEmpty()) {
                        try { targetF = Long.parseLong(tgtStr); } catch (NumberFormatException ignored) {}
                    }
                    final Long fTargetF = targetF;

                    // 目標Fに最も近い候補（目標F以下）を探す
                    Long bestCandidate = null;
                    if (fTargetF != null) {
                        for (Long f : results) {
                            if (f <= fTargetF) {
                                if (bestCandidate == null || f > bestCandidate) {
                                    bestCandidate = f;
                                }
                            }
                        }
                    }
                    final Long fBestCandidate = bestCandidate;

                    // 行レンダラーで目標F近傍をハイライト
                    comboTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
                        @Override
                        public java.awt.Component getTableCellRendererComponent(
                                JTable table, Object value, boolean isSelected,
                                boolean hasFocus, int row, int column) {
                            java.awt.Component c = super.getTableCellRendererComponent(
                                    table, value, isSelected, hasFocus, row, column);
                            if (!isSelected) {
                                c.setBackground(BG2);
                                c.setForeground(FG);
                                if (fTargetF != null && row < table.getRowCount()) {
                                    int modelRow = table.convertRowIndexToModel(row);
                                    Object frameVal = table.getModel().getValueAt(modelRow, 0);
                                    if (frameVal instanceof Long) {
                                        long f = (Long) frameVal;
                                        if (fBestCandidate != null && f == fBestCandidate) {
                                            c.setBackground(new java.awt.Color(0x2a, 0x4a, 0x2a)); // 緑系: 推奨
                                            c.setForeground(new java.awt.Color(0xa0, 0xff, 0xa0));
                                        } else if (f > fTargetF) {
                                            c.setForeground(new java.awt.Color(0xcc, 0x66, 0x66)); // 赤系: 目標を超過
                                        }
                                    }
                                }
                            }
                            return c;
                        }
                    });
                    comboTable.repaint();

                    if (results.isEmpty()) {
                        summaryLabel.setText(String.format(
                            "候補なし — 個数列が誤っているか、検索範囲(%,d F)が狭すぎる可能性",
                            fMaxF));
                        summaryLabel.setForeground(WARN);
                    } else if (results.size() == 1) {
                        summaryLabel.setText("現在フレーム特定: " + results.get(0) + "F");
                        summaryLabel.setForeground(SUCCESS);
                    } else if (fBestCandidate != null) {
                        // 目標F指定あり: 推奨候補を強調
                        // ただし複数候補がある時は偽マッチ警告
                        summaryLabel.setText(String.format(
                            "<html>%d件の候補 — 推奨: <b>%d F</b>（目標 %d F 以下で最も近い、緑色行）" +
                            "　<span style='color:#cc8888;'>※複数候補がある時は偽マッチ含む可能性。観測数を増やすと絞り込めます</span></html>",
                            results.size(), fBestCandidate, fTargetF));
                        summaryLabel.setForeground(SUCCESS);
                    } else {
                        summaryLabel.setText("<html>" + results.size() + "件の候補（目標Fを入力すると推奨候補をハイライトします）" +
                            "　<span style='color:#cc8888;'>※候補が多い時は観測数を増やすことを推奨</span></html>");
                        summaryLabel.setForeground(WARN);
                    }
                });
            });
        });

        cancelBtn.addActionListener(e -> {
            cancelFlag.set(true);
            statusLabel.setText("調合スナイプを中止しました");
        });

        return tab;
    }

    // ================================================================
    // Combo Arduino Tab (調合スナイプ2コード方式)
    // ================================================================
    JPanel buildComboArduinoTab() {
        JPanel tab = new JPanel(new BorderLayout(8, 8));
        tab.setBackground(BG);
        tab.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // 上部: パラメータ入力
        JPanel top = new JPanel();
        top.setBackground(BG);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel settings = titled("調合スナイプ Arduino 2コード方式");
        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));

        // 説明
        JPanel descRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        descRow.setOpaque(false);
        descRow.add(new JLabel(
            "<html><body style='width:800px; color:#8888aa;'>" +
            "<b>★前提: ココット村でセーブ必須★</b>（ロード後は村スタート → コード1が自宅へ移動）<br>" +
            "<b>ワークフロー（全3ステップ）:</b><br>" +
            "① <b>コード1</b>をArduinoに書き込み→実行: Continue連打→自宅移動→調合→30秒録画→HOME中断<br>" +
            "② <b>手動</b>: 録画確認→累積弾数を「調合スナイプ」タブに入力→現在F特定→現在Fと目標Fを入力<br>" +
            "③ <b>コード2</b>をArduinoに書き込み→実行: HOME復帰→マカ錬金準備→投入確認で待機→投入確定→ケルビ→鑑定" +
            "</body></html>"));
        settings.add(descRow);

        // --- コード1パラメータ ---
        JPanel code1Header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        code1Header.setOpaque(false);
        JLabel c1Label = new JLabel("【コード1】フレーム消費 + 調合 + 録画");
        c1Label.setFont(FONT_UI_BOLD);
        c1Label.setForeground(ACCENT);
        code1Header.add(c1Label);
        settings.add(code1Header);

        JPanel c1Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        c1Row.setOpaque(false);
        c1Row.add(label("Continue回数:"));
        comboNcField = makeField("", 8);
        comboNcField.setToolTipText("<html>Continue連打回数。目標Fが大きいほど多くする。<br>目安: 目標F ÷ 714</html>");
        c1Row.add(comboNcField);
        c1Row.add(label("  調合回数（目安）:"));
        comboNumField = makeField("20", 5);
        comboNumField.setToolTipText(
            "<html>10秒のA長押しで実機が何回くらい調合するかの<b>目安</b>。<br>" +
            "（生成コードのA長押し時間は10秒固定。このフィールドはメモ用）</html>");
        c1Row.add(comboNumField);
        c1Row.add(label("  Lv2弾までの↓数:"));
        comboDownKeysField = makeField("0", 4);
        comboDownKeysField.setToolTipText(
            "<html>「リストから調合」を選んだ後、<br>" +
            "アイテムリスト画面でLv2通常弾までカーソルを移動するための↓キー回数。<br>" +
            "<b>実機で要調整</b>。先頭にあれば0、3番目なら2。</html>");
        c1Row.add(comboDownKeysField);
        JButton gen1Btn = makeButton("▶ コード1を生成", BTN_BG);
        c1Row.add(gen1Btn);
        settings.add(c1Row);

        // --- コード2パラメータ ---
        JPanel code2Header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        code2Header.setOpaque(false);
        JLabel c2Label = new JLabel("【コード2】ゲーム復帰 + 待機 + マカ錬金");
        c2Label.setFont(FONT_UI_BOLD);
        c2Label.setForeground(ACCENT);
        code2Header.add(c2Label);
        settings.add(code2Header);

        JPanel c2Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        c2Row.setOpaque(false);
        c2Row.add(label("現在F (消費後):"));
        JTextField comboCurrentFField = makeField("", 10);
        comboCurrentFField.setToolTipText(
            "<html>「調合スナイプ」タブのテーブル「<b>消費後フレーム</b>」列の値を入力。<br>" +
            "（調合終了直後の乱数位置。HOME中断中は乱数が進まない前提で<br>" +
            "コード2の待機開始フレームとなる）</html>");
        c2Row.add(comboCurrentFField);
        c2Row.add(label("  目標F:"));
        JTextField comboTargetFField = makeField("", 10);
        comboTargetFField.setToolTipText("「お守り検索」や「有名お守り」タブで見つけた目標フレーム");
        c2Row.add(comboTargetFField);
        c2Row.add(label("  → 待ち:"));
        JLabel comboWaitLabel = new JLabel("---");
        comboWaitLabel.setFont(FONT_LARGE);
        comboWaitLabel.setForeground(ACCENT);
        c2Row.add(comboWaitLabel);
        JButton gen2Btn = makeButton("▶ コード2を生成", ACCENT);
        c2Row.add(gen2Btn);
        settings.add(c2Row);

        top.add(settings);
        tab.add(top, BorderLayout.NORTH);

        // 下部: コード表示エリア
        JTextArea codeArea = new JTextArea();
        codeArea.setFont(FONT_MONO_SMALL);
        codeArea.setBackground(BG2);
        codeArea.setForeground(FG);
        codeArea.setCaretColor(FG);
        codeArea.setEditable(true);
        codeArea.setTabSize(2);
        codeArea.setText("// コード1またはコード2を生成するとここに表示されます。\n// Arduino IDEにコピペして書き込んでください。");
        JScrollPane sp = new JScrollPane(codeArea);
        setupScrollSpeed(sp);
        JPanel codePanel = titled("生成されたArduinoスケッチ");
        codePanel.setLayout(new BorderLayout());
        codePanel.add(sp, BorderLayout.CENTER);

        // コピーボタンをコードパネルの上に
        JPanel codeBtnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        codeBtnRow.setOpaque(false);
        JButton copyBtn = makeButton("📋 クリップボードにコピー", BTN_BG);
        codeBtnRow.add(copyBtn);
        codePanel.add(codeBtnRow, BorderLayout.SOUTH);

        tab.add(codePanel, BorderLayout.CENTER);

        // --- アクションリスナー ---

        // コード1生成
        gen1Btn.addActionListener(e -> {
            try {
                int nc = Integer.parseInt(comboNcField.getText().trim());
                int downKeys = Integer.parseInt(comboDownKeysField.getText().trim());
                String code = generateComboCode1(nc, downKeys);
                codeArea.setText(code);
                codeArea.setCaretPosition(0);
                statusLabel.setText("コード1を生成しました");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(tab, "数値を正しく入力してください", "エラー", JOptionPane.ERROR_MESSAGE);
            }
        });

        // 待ち時間の自動計算
        Runnable updateWait = () -> {
            try {
                long curF = Long.parseLong(comboCurrentFField.getText().trim());
                long tgtF = Long.parseLong(comboTargetFField.getText().trim());
                long diffF = tgtF - curF;
                if (diffF <= 0) {
                    comboWaitLabel.setText("目標Fが現在F以下");
                    comboWaitLabel.setForeground(WARN);
                } else {
                    long waitMs = Math.round(diffF / 30.0 * 1000);
                    comboWaitLabel.setText(String.format("%,d ms (%.1f秒)", waitMs, waitMs / 1000.0));
                    comboWaitLabel.setForeground(ACCENT);
                }
            } catch (NumberFormatException ex) {
                comboWaitLabel.setText("---");
                comboWaitLabel.setForeground(FG);
            }
        };
        comboCurrentFField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateWait.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateWait.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateWait.run(); }
        });
        comboTargetFField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateWait.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateWait.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateWait.run(); }
        });

        // コード2生成
        gen2Btn.addActionListener(e -> {
            try {
                long curF = Long.parseLong(comboCurrentFField.getText().trim());
                long tgtF = Long.parseLong(comboTargetFField.getText().trim());
                long diffF = tgtF - curF;
                if (diffF <= 0) {
                    JOptionPane.showMessageDialog(tab, "目標Fが現在F以下です", "エラー", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                long waitMs = Math.round(diffF / 30.0 * 1000);
                String code = generateComboCode2(waitMs);
                codeArea.setText(code);
                codeArea.setCaretPosition(0);
                statusLabel.setText(String.format("コード2を生成（待機 %,d ms = %.1f秒）", waitMs, waitMs / 1000.0));
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(tab, "現在Fと目標Fを正しく入力してください", "エラー", JOptionPane.ERROR_MESSAGE);
            }
        });

        // クリップボードにコピー
        copyBtn.addActionListener(e -> {
            String text = codeArea.getText();
            if (text.startsWith("//") && text.contains("コード1またはコード2")) {
                JOptionPane.showMessageDialog(tab, "先にコードを生成してください", "情報", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
            statusLabel.setText("クリップボードにコピーしました");
        });

        return tab;
    }

    // ================================================================
    // Combo Snipe (調合スナイプ: Lv2通常弾の個数列から現在フレーム特定)
    //
    // 仕様 (サイファー氏 & apmnnn氏の調査より):
    //   Lv2通常弾 = ハリの実 + カラの実（各15個以上用意）
    //   調合書を持ってアイテムボックス内（ココット村自宅）で連続調合
    //   (val & 0xFFFF) % 100 の値で個数が決まる:
    //     0-24  → 2個
    //     25-74 → 3個
    //     75-99 → 4個
    //   1回の調合で乱数5消費
    // 注意:
    //   ペット/ルームサービスがアイルー/オトモアイルー同行では挙動が変わる可能性
    // ================================================================

    /** 乱数値（16bit）から調合個数を決定する */
    public static int comboCountFromValue(int val) {
        int v = val % 100;
        if (v < 25) return 2;
        if (v < 75) return 3;
        return 4;
    }

    /**
     * 指定フレームから連続調合して個数列を取得する（テスト用）。
     * @param startFrame 調合開始フレーム
     * @param numRolls 調合回数
     * @return 各回の個数列（長さnumRolls）
     */
    public static int[] simulateCombo(long startFrame, int numRolls) {
        RNG rng = new RNG();
        rng.jumpRaw(startFrame);
        int[] counts = new int[numRolls];
        for (int i = 0; i < numRolls; i++) {
            // 各回の1回目の乱数で個数を決定、残り4回は消費（合計5消費/回）
            rng.ascend();
            int v = (int)((rng.w & 0xFFFF) % 100);
            counts[i] = comboCountFromValue(v);
            // 残り4回消費
            for (int j = 0; j < 4; j++) rng.ascend();
        }
        return counts;
    }

    /**
     * 調合個数列から一致するフレームを逆算する。
     * @param targetCounts 実機で得た個数列
     * @param maxFrames 検索範囲
     * @param cancel キャンセルフラグ（nullable）
     * @return 一致する候補フレームのリスト
     */
    public static List<Long> reverseSearchCombo(int[] targetCounts, long maxFrames,
            java.util.concurrent.atomic.AtomicBoolean cancel) {
        List<Long> results = new ArrayList<>();
        if (targetCounts == null || targetCounts.length == 0) return results;
        // バリデーション: 全て2,3,4であること
        for (int c : targetCounts) {
            if (c < 2 || c > 4) return results;
        }

        int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (maxFrames < (long)nThreads * 1000) nThreads = 1;

        long chunkSize = maxFrames / nThreads;
        List<Long> allResults = Collections.synchronizedList(new ArrayList<>());
        ExecutorService exec = Executors.newFixedThreadPool(nThreads);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < nThreads; t++) {
            final long startFrame = (long)t * chunkSize;
            final long endFrame = (t == nThreads - 1) ? maxFrames : (long)(t + 1) * chunkSize;
            futures.add(exec.submit(() -> {
                RNG rng = new RNG();
                rng.jumpRaw(startFrame);

                long localCount = endFrame - startFrame;
                for (long i = 0; i < localCount; i++) {
                    if (cancel != null && cancel.get()) break;
                    long currentFrame = startFrame + i;

                    // 状態を退避
                    long sx = rng.x, sy = rng.y, sz = rng.z, sw = rng.w;

                    // targetCountsと一致するかチェック
                    boolean match = true;
                    for (int k = 0; k < targetCounts.length; k++) {
                        rng.ascend();
                        int v = (int)((rng.w & 0xFFFF) % 100);
                        int count = comboCountFromValue(v);
                        if (count != targetCounts[k]) {
                            match = false;
                            break;
                        }
                        // 残り4回消費
                        for (int j = 0; j < 4; j++) rng.ascend();
                    }

                    if (match) allResults.add(currentFrame);

                    // 状態を復元して1F進める
                    rng.x = sx; rng.y = sy; rng.z = sz; rng.w = sw;
                    rng.ascend();
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        exec.shutdown();
        allResults.sort(Long::compare);
        return allResults;
    }

    // ================================================================
    // Quest Auto-Loop Sketch Generator (Phase 1-B)
    //
    // G★2「火山の採集ツアー」を自動周回するArduinoスケッチを生成する。
    //
    // 想定フロー:
    //   [状態0] クエスト結果画面（ユーザが初回はここまで手動で到達）
    //     ↓ A連打（報酬受取〜次クエスト受注〜出発）
    //   [状態1] BC着 → 前方ダッシュでエリア4へ
    //   [状態2] エリア4ロード待機 → 採掘ポイントへ前進
    //   [状態3] 採掘A連打 → モドリ玉使用（メニュー→アイテム→モドリ玉→A）
    //   [状態4] BC帰還 → 納品ボックスへ前進 → A連打で納品
    //   [状態5] クエストクリア → 報酬画面 → 結果画面 → 状態0へループ
    //
    // 注意:
    //   デフォルト値は机上推定。実機で試しながら調整する前提のパラメータ。
    // ================================================================

    /** クエスト周回スケッチのパラメータ */
    public static class QuestSketchParams {
        // 結果画面〜クエスト開始
        public int resultScreenAPressCount = 15;   // 報酬受取〜出発までのA連打回数
        public int aPressIntervalMs = 500;          // A連打の間隔

        // クエスト開始後のBC待機
        public int bcStartWaitMs = 3000;            // BCロード完了待ち

        // BC→エリア4移動
        public int bcToArea4TravelMs = 5000;        // ダッシュ時間

        // エリア4ロード
        public int areaLoadWaitMs = 5000;           // エリア切替ロード待機

        // エリア4内移動（採掘ポイント前）
        public int area4InnerMoveMs = 2000;         // エリア境界から採掘ポイントまで

        // 採掘
        public int diggingAPressCount = 10;         // ピッケル1本で最大採掘回数
        public int diggingAPressIntervalMs = 300;   // 採掘アクション間隔

        // モドリ玉使用
        public int modoriDaArrowDownCount = 0;      // メニューでアイテム欄に移動するまでの↓回数（実機調整）
        public int modoriDaUseWaitMs = 5000;        // モドリ玉使用後BC帰還までの待機

        // BC内納品ボックス移動
        public int deliveryBoxTravelMs = 2000;      // BC帰還後、納品ボックスへ移動
        public int deliveryAPressCount = 8;         // 納品A連打回数

        // 報酬画面〜結果画面
        public int rewardScreenAPressCount = 20;    // 報酬画面でのA連打回数（結果画面まで戻る）

        public QuestSketchParams() {}
    }

    /**
     * クエスト自動周回Arduinoスケッチを生成する。
     * @param p パラメータ
     * @return 生成されたC++スケッチコード
     */
    public static String generateQuestSketch(QuestSketchParams p) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ================================================================\n");
        sb.append("// MHXX クエスト自動周回スケッチ (G★2 火山の採集ツアー)\n");
        sb.append("// ================================================================\n");
        sb.append("// 実行前提:\n");
        sb.append("//   1. 集会所受付でG★2「火山の採集ツアー」を1回クリアしておく\n");
        sb.append("//   2. クエスト結果画面でこのスケッチを搭載したArduinoを接続\n");
        sb.append("//   3. アイテムポーチに「モドリ玉」が入っていること\n");
        sb.append("//   4. ピッケル（鉄/グレート）を複数本所持していること\n");
        sb.append("//   5. クーラードリンクの所持を推奨（火山は灼熱エリアあり）\n");
        sb.append("//   6. オトモはなし（お守り入手率を上げるため）\n");
        sb.append("//\n");
        sb.append("// 落とし穴対策:\n");
        sb.append("//   - setup()で十分にB連打してSwitch認識を待つ（10回）\n");
        sb.append("//   - 各フェーズ開始時にB連打してメニュー/ダイアログをクリア\n");
        sb.append("//   - エリア境界でのA押下は複数回（空振り対策）\n");
        sb.append("//   - 採掘後にB連打（「ピッケルが壊れた」等のメッセージクリア）\n");
        sb.append("//   - 報酬画面の終盤はB連打（「鑑定する」誤爆を防止）\n");
        sb.append("//\n");
        sb.append("// 注意:\n");
        sb.append("//   デフォルトのタイミング値は机上推定値。実機で試しながら調整すること。\n");
        sb.append("//   特に移動時間・採掘位置・モドリ玉操作は機体個体差があるため要調整。\n");
        sb.append("// ================================================================\n");
        sb.append("#include <NintendoSwitchControlLibrary.h>\n");
        sb.append("\n");
        sb.append("void setup() {\n");
        sb.append("  // 誤動作防止：Switchがマイコンを認識するまで十分にB連打（10回）\n");
        sb.append("  pushButton(Button::B, 500, 10);\n");
        sb.append("}\n");
        sb.append("\n");
        sb.append("void loop() {\n");
        sb.append("  // ---- フェーズ0: メニュー/ダイアログをBでクリア（フェーズ間ガード） ----\n");
        sb.append("  pushButton(Button::B, 200, 3);\n");
        sb.append("\n");
        sb.append("  // ---- フェーズ1: クエスト結果画面 → 次クエスト受注 → 出発 ----\n");
        sb.append("  // 報酬受取〜受注画面〜出発までをAボタン連打で進行\n");
        sb.append("  pushButton(Button::A, ").append(p.aPressIntervalMs)
                .append(", ").append(p.resultScreenAPressCount).append(");\n");
        sb.append("\n");
        sb.append("  // ---- フェーズ2: BCロード待機 ----\n");
        sb.append("  delay(").append(p.bcStartWaitMs).append(");\n");
        sb.append("  // フェーズ開始前のクリア\n");
        sb.append("  pushButton(Button::B, 200, 2);\n");
        sb.append("\n");
        sb.append("  // ---- フェーズ3: BC → エリア4 へダッシュ ----\n");
        sb.append("  // 左スティック上 + Rボタン（ダッシュ）\n");
        sb.append("  tiltLeftStick(Stick::NEUTRAL, Stick::MIN, ").append(p.bcToArea4TravelMs)
                .append(", Button::R);\n");
        sb.append("\n");
        sb.append("  // ---- フェーズ4: エリア境界でA（複数回、空振り対策） → エリア切替ロード ----\n");
        sb.append("  pushButton(Button::A, 300, 3);\n");
        sb.append("  delay(").append(p.areaLoadWaitMs).append(");\n");
        sb.append("\n");
        sb.append("  // ---- フェーズ5: エリア4内で採掘ポイントへ前進 ----\n");
        sb.append("  // フェーズ開始前のクリア\n");
        sb.append("  pushButton(Button::B, 200, 2);\n");
        sb.append("  tiltLeftStick(Stick::NEUTRAL, Stick::MIN, ").append(p.area4InnerMoveMs).append(");\n");
        sb.append("\n");
        sb.append("  // ---- フェーズ6: 採掘（A連打） ----\n");
        sb.append("  pushButton(Button::A, ").append(p.diggingAPressIntervalMs)
                .append(", ").append(p.diggingAPressCount).append(");\n");
        sb.append("  // 採掘後のメッセージクリア（ピッケル破損・採掘完了など）\n");
        sb.append("  pushButton(Button::B, 200, 5);\n");
        sb.append("\n");
        sb.append("  // ---- フェーズ7: モドリ玉使用でBCへ帰還 ----\n");
        sb.append("  // メニューを開く\n");
        sb.append("  pushButton(Button::PLUS, 800);\n");
        for (int i = 0; i < p.modoriDaArrowDownCount; i++) {
            sb.append("  pushHat(Hat::DOWN, 200);\n");
        }
        sb.append("  // TODO: 実機で「アイテム」→「モドリ玉」までの↓回数を合わせる\n");
        sb.append("  pushButton(Button::A, 500);  // アイテム選択\n");
        sb.append("  pushButton(Button::A, 500);  // モドリ玉使用\n");
        sb.append("  pushButton(Button::A, 500);  // 使用確認\n");
        sb.append("  delay(").append(p.modoriDaUseWaitMs).append(");\n");
        sb.append("\n");
        sb.append("  // ---- フェーズ8: BC内で納品ボックスへ移動 ----\n");
        sb.append("  // フェーズ開始前のクリア（モドリ玉のメッセージなど）\n");
        sb.append("  pushButton(Button::B, 200, 3);\n");
        sb.append("  tiltLeftStick(Stick::NEUTRAL, Stick::MIN, ").append(p.deliveryBoxTravelMs).append(");\n");
        sb.append("\n");
        sb.append("  // ---- フェーズ9: 納品ボックスでネコタクチケット納品 ----\n");
        sb.append("  pushButton(Button::A, ").append(p.aPressIntervalMs)
                .append(", ").append(p.deliveryAPressCount).append(");\n");
        sb.append("\n");
        sb.append("  // ---- フェーズ10: 報酬画面 → 結果画面（次ループへ） ----\n");
        sb.append("  // 前半はA連打（報酬表示を進める）\n");
        int firstHalf = Math.max(p.rewardScreenAPressCount / 2, 1);
        int secondHalf = Math.max(p.rewardScreenAPressCount - firstHalf, 1);
        sb.append("  pushButton(Button::A, ").append(p.aPressIntervalMs)
                .append(", ").append(firstHalf).append(");\n");
        sb.append("  // 後半はB連打（「鑑定する」誤爆を防止しながら画面を進める）\n");
        sb.append("  pushButton(Button::B, ").append(p.aPressIntervalMs)
                .append(", ").append(secondHalf).append(");\n");
        sb.append("\n");
        sb.append("  // ループ末尾のクリア\n");
        sb.append("  pushButton(Button::B, 200, 2);\n");
        sb.append("  delay(500);\n");
        sb.append("}\n");
        return sb.toString();
    }
}
