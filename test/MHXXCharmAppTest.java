import java.util.List;

/** 依存ライブラリなしで実行できる回帰テスト。 */
public final class MHXXCharmAppTest {
    private static int assertions;

    public static void main(String[] args) {
        testJumpMatchesSequentialRng();
        testKnownCharmAndRangeSearch();
        testSecondSkillNormalization();
        testComboReverseSearchAndStartFrame();
        testComboSketchGeneration();
        testComboBackupSketchGeneration();
        testRewardConsumptionAndStartFrame();
        System.out.println("OK: " + assertions + " assertions");
    }

    private static void testJumpMatchesSequentialRng() {
        long[] frames = {0, 1, 7, 1716, 4723, 100_000};
        for (long frame : frames) {
            MHXXRng sequential = new MHXXRng();
            for (long i = 0; i < frame; i++) sequential.ascend();

            MHXXRng jumped = new MHXXRng();
            jumped.jumpRaw(frame);
            equal(sequential.x, jumped.x, "jumpRaw x @" + frame);
            equal(sequential.y, jumped.y, "jumpRaw y @" + frame);
            equal(sequential.z, jumped.z, "jumpRaw z @" + frame);
            equal(sequential.w, jumped.w, "jumpRaw w @" + frame);
        }
    }

    private static void testKnownCharmAndRangeSearch() {
        CharmData data = new CharmData();
        data.setBlue();

        MHXXCharmApp.RNG rng = new MHXXCharmApp.RNG();
        rng.jump(1716);
        MHXXCharmApp.Charm charm = MHXXCharmApp.getCharm(rng, data, 0);
        equal("斬れ味", charm.s1Name(), "1716F skill1");
        equal(5, charm.sp1(), "1716F sp1");
        equal("痛撃", charm.s2Name(), "1716F skill2");
        equal(4, charm.sp2(), "1716F sp2");
        equal(3, charm.slot(), "1716F slot");

        List<Object[]> hits = MHXXCharmApp.searchCharm(
                data, "斬れ味", 5, "痛撃", 4, 3, 0,
                1_700L, 30, false, null, null, null);
        check(hits.stream().anyMatch(row -> ((Long)row[0]) == 1716L),
                "start frame search must include known 1716F charm");

        List<MHXXCharmApp.SearchCondition> conditions = List.of(
                new MHXXCharmApp.SearchCondition("斬れ味", 5, "痛撃", 4, 3));
        List<Object[]> multiHits = MHXXCharmApp.searchCharmMulti(
                data, conditions, 0, 1_700L, 30, null, null, null);
        check(multiHits.stream().anyMatch(row -> ((Long)row[0]) == 1716L),
                "multi search start frame must include known 1716F charm");
    }

    private static void testSecondSkillNormalization() {
        CharmData data = new CharmData();
        data.setBlue();
        MHXXCharmApp.RNG rng = new MHXXCharmApp.RNG();
        rng.jump(0);

        boolean sawInvalidSecondSkill = false;
        boolean sawNegativeSecondSkill = false;
        int invalidFrame = -1;
        int negativeFrame = -1;
        MHXXCharmApp.Charm invalidCharm = null;
        MHXXCharmApp.Charm negativeCharm = null;
        for (int frame = 0; frame < 200_000; frame++) {
            int id1 = (int)(rng.r0 % data.skill1.length);
            int id2 = (int)(rng.r3 % data.skill2.length);
            boolean hasSecondRoll = (rng.r2 % 100) >= data.th;
            if (hasSecondRoll) {
                int rawSp2;
                if (rng.r4 % 2 == 0) {
                    rawSp2 = (int)(rng.r5 % (data.sp2[id2][0] + 1)) - data.sp2[id2][0];
                } else {
                    rawSp2 = (int)(rng.r5 % data.sp2[id2][1]) + 1;
                }

                boolean invalid = data.skill1[id1] == data.skill2[id2] || rawSp2 == 0;
                MHXXCharmApp.Charm charm = MHXXCharmApp.getCharm(rng, data, 1);
                if (invalid && frame > 0) {
                    equal(null, charm.s2Name(), "duplicate/zero second skill is absent");
                    equal(0, charm.sp2(), "absent second skill has 0 SP");
                    sawInvalidSecondSkill = true;
                    invalidFrame = frame;
                    invalidCharm = charm;
                } else if (rawSp2 < 0 && frame > 0) {
                    check(charm.s2Name() != null, "negative second skill remains visible");
                    equal(rawSp2, charm.sp2(), "negative second skill SP");
                    int expectedFill = charm.sp1() * 10 / data.sp1[id1][1];
                    equal(expectedFill, charm.fill(), "negative SP does not reduce fill");
                    sawNegativeSecondSkill = true;
                    negativeFrame = frame;
                    negativeCharm = charm;
                }
            }
            if (sawInvalidSecondSkill && sawNegativeSecondSkill) break;
            rng.roll();
        }
        check(sawInvalidSecondSkill, "must encounter duplicate/zero second skill test case");
        check(sawNegativeSecondSkill, "must encounter negative second skill test case");

        final int expectedInvalidFrame = invalidFrame;
        List<Object[]> noSecondHits = MHXXCharmApp.searchCharm(
                data, invalidCharm.s1Name(), invalidCharm.sp1(), MHXXCharmApp.S2_NONE, 0,
                invalidCharm.slot(), 1, invalidFrame - 1L, 1, false, null, null, null);
        check(noSecondHits.stream().anyMatch(row -> ((Long)row[0]) == expectedInvalidFrame),
                "no-second-skill search includes duplicate/zero roll");

        final int expectedNegativeFrame = negativeFrame;
        List<Object[]> negativeHits = MHXXCharmApp.searchCharm(
                data, negativeCharm.s1Name(), negativeCharm.sp1(), negativeCharm.s2Name(),
                negativeCharm.sp2(), negativeCharm.slot(), 1,
                negativeFrame - 1L, 1, false, null, null, null);
        check(negativeHits.stream().anyMatch(row -> ((Long)row[0]) == expectedNegativeFrame),
                "exact search supports negative second SP");
    }

    private static void testComboReverseSearchAndStartFrame() {
        int[] cumulative = {
                0, 2, 4, 7, 10, 13, 16, 18, 22,
                25, 28, 30, 32, 35, 39, 42, 45
        };
        equal(List.of(3831L),
                MHXXCharmApp.reverseSearchCombo(cumulative, 0L, 10_000L, null),
                "published combo example");
        equal(List.of(3831L),
                MHXXCharmApp.reverseSearchCombo(cumulative, 3_800L, 100L, null),
                "combo search start frame");
    }

    private static void testComboSketchGeneration() {
        String code1 = MHXXCharmApp.generateComboCode1(3, 2, 90_000, true);
        check(code1.contains("i < 3"), "code1 Continue count");
        check(code1.contains("while (millis() - cycle_start < 500UL) delay(1);"),
                "code1 Continue cycle aligned to 500ms");
        check(code1.indexOf("delay(90000);") > code1.indexOf("i < 3"),
                "code1 pre-load wait follows Continue loop");
        check(code1.indexOf("delay(90000);") < code1.indexOf("pushButton(Button::A, 250, 4);"),
                "code1 pre-load wait precedes final Continue");
        check(code1.contains("tiltLeftStick(Stick::MIN, 10, 1100);"), "code1 optional Poogie route");
        check(code1.contains("tiltLeftStick(Stick::MAX, Stick::MAX, 2000);"), "code1 carries Poogie outside");
        check(code1.indexOf("tiltLeftStick(Stick::MIN, 10, 2000);") < code1.indexOf("pushButton(Button::PLUS, 500);"),
                "code1 moves to item box before crafting");
        check(code1.contains("holdButton(Button::A, 4000);"), "code1 four-second craft");
        check(!MHXXCharmApp.generateComboCode1(0, 0).contains("tiltLeftStick(Stick::MIN, 10, 1100);"),
                "code1 leaves absent Poogie alone");

        String code2 = MHXXCharmApp.generateComboCode2(12_345, 200, true);
        check(code2.contains("unsigned long wait_ms = 12345;"), "code2 wait literal");
        check(code2.indexOf("delay(wait_ms);") < code2.indexOf("tiltLeftStick(Stick::MAX, 190, 2500);"),
                "code2 waits at item box before walking to room service");
        check(code2.indexOf("tiltLeftStick(Stick::MAX, 190, 2500);") < code2.indexOf("// Step 4: ルームサービスに話しかけ"),
                "code2 uses room service after walking");
        check(code2.indexOf("pushButton(Button::X, 250);     // 自宅から村へ戻る") >
                code2.indexOf("// Step 5: A連打で投入確定"), "code2 exits home after melding");
        check(code2.contains("pressButton(Button::L);") && code2.contains("pressButton(Button::R);"),
                "code2 optional simultaneous L+R pairing");
        check(!code2.contains("waitWithKeepAlive"), "code2 wait does not press a menu button");
        check(!MHXXCharmApp.generateComboCode2(0, -1).contains("pressButton(Button::L);"),
                "code2 does not assume pairing screen by default");
        expectInvalid(() -> MHXXCharmApp.generateComboCode1(-1, 0, 0, false), "negative Continue rejected");
        expectInvalid(() -> MHXXCharmApp.generateComboCode1(0, 0, -1, false), "negative pre-load wait rejected");
        expectInvalid(() -> MHXXCharmApp.generateComboCode2(-1, 0), "negative code2 wait rejected");
        expectInvalid(() -> MHXXCharmApp.generateComboCode2(0x1_0000_0000L, 0), "wait overflow rejected");
    }

    private static void testComboBackupSketchGeneration() {
        String code1 = MHXXCharmApp.generateComboCode1(0, 4, 0, false);
        String code3 = MHXXCharmApp.generateComboCode3(600_000, 4, 2, 1, 3, 1, true);
        check(code3.contains("unsigned long wait_ms = 600000;"), "code3 wait literal");
        check(code3.indexOf("delay(wait_ms);") < code3.indexOf("// アイテムマイセットで所持品を復元"),
                "code3 waits before resetting inventory");
        check(code3.contains("pushHat(Hat::DOWN, 100, 2); // 箱メニュー"),
                "code3 box menu position");
        check(code3.contains("pushHat(Hat::DOWN, 100, 1); // マイセットの呼び出し"),
                "code3 recall menu position");
        check(code3.contains("pushHat(Hat::DOWN, 100, 2); // 登録番号 3"),
                "code3 item set slot");
        check(code3.contains("pushButton(Button::A, 250, 1); // 呼び出し確認"),
                "code3 confirmation count");
        check(code3.indexOf("pushButton(Button::B, 250, 3); // 箱メニューを閉じる") <
                code3.indexOf("// Step 5: +ボタンでメニュー"),
                "code3 closes box before crafting");
        String craftMarker = "    // Step 5: +ボタンでメニューを開く";
        equal(code1.substring(code1.indexOf(craftMarker)), code3.substring(code3.indexOf(craftMarker)),
                "code3 crafting through HOME must match code1 exactly");
        check(code3.contains("pressButton(Button::L);") && code3.contains("pressButton(Button::R);"),
                "code3 optional controller pairing");
        check(code3.contains("新しい録画から調合基準Fを再特定"),
                "code3 directs new frame measurement");
        String firstSlot = MHXXCharmApp.generateComboCode3(0, 0, 0, 0, 1, 0, false);
        check(!firstSlot.contains("// 登録番号 "), "first item set slot needs no movement");
        check(!firstSlot.contains("// 呼び出し確認"), "confirmation step can be disabled");
        String directList = MHXXCharmApp.generateComboCode3(0, 0, 2, -1, 1, 0, false);
        check(!directList.contains("// 呼び出し一覧へ"), "direct myset list skips intermediate menu");
        expectInvalid(() -> MHXXCharmApp.generateComboCode3(-1, 0, 2, 0, 1, 1, false),
                "code3 negative wait rejected");
        expectInvalid(() -> MHXXCharmApp.generateComboCode3(0x1_0000_0000L, 0, 2, 0, 1, 1, false),
                "code3 wait overflow rejected");
        expectInvalid(() -> MHXXCharmApp.generateComboCode3(0, 0, 2, 0, 25, 1, false),
                "code3 invalid item set slot rejected");
        expectInvalid(() -> MHXXCharmApp.generateComboCode3(0, 0, 2, -2, 1, 1, false),
                "code3 invalid recall menu position rejected");
    }

    private static void expectInvalid(Runnable action, String message) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, message);
    }

    private static void testRewardConsumptionAndStartFrame() {
        String[] rewards = {
                "力の成長餌", "釣りミミズ", "生肉", "謎の骨",
                "謎の骨", "生肉", "釣りミミズ"
        };
        List<MHXXCharmApp.RewardSearchResult> results = MHXXCharmApp.reverseSearchRewards(
                rewards, 100_000L, 10_000, 28, null, null);
        equal(1, results.size(), "reward search narrowed by start frame");
        MHXXCharmApp.RewardSearchResult result = results.get(0);
        equal(107_374L, result.generationFrame(), "reward generation frame");
        equal(107_385L, result.currentFrame(), "reward generation consumes 11 frames for 7 rewards");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(long expected, long actual, String message) {
        assertions++;
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
