/**
 * MHXXCharmApp.RNG.jumpRaw() の正しさを検証するテスト。
 * ascendループで到達した状態と、jumpRawで到達した状態が一致するか確認する。
 */
public class JumpRawTest {
    public static void main(String[] args) {
        System.out.println("=== JumpRaw Test ===\n");
        int passed = 0, failed = 0;

        // テストケース: 複数のフレーム位置で比較
        long[] testFrames = {0, 1, 10, 100, 1000, 10000, 100000, 1000000};

        for (long frame : testFrames) {
            // 方法1: ascendループ
            MHXXCharmApp.RNG rngLoop = new MHXXCharmApp.RNG();
            // init()済み、ascend()でframe回進める
            for (long i = 0; i < frame; i++) rngLoop.ascend();
            long lx = rngLoop.x, ly = rngLoop.y, lz = rngLoop.z, lw = rngLoop.w;

            // 方法2: jumpRaw
            MHXXCharmApp.RNG rngJump = new MHXXCharmApp.RNG();
            rngJump.jumpRaw(frame);
            long jx = rngJump.x, jy = rngJump.y, jz = rngJump.z, jw = rngJump.w;

            boolean match = (lx == jx && ly == jy && lz == jz && lw == jw);
            if (match) {
                passed++;
                System.out.printf("  ✓ frame=%d: x=%08X w=%08X%n", frame, lx, lw);
            } else {
                failed++;
                System.out.printf("  ✗ frame=%d: loop(x=%08X w=%08X) != jump(x=%08X w=%08X)%n",
                    frame, lx, lw, jx, jw);
            }
        }

        // 追加: jumpRaw後にascend()で進めた値が、ループ版と一致するか
        System.out.println("\n  --- jumpRaw後のascend出力一致確認 ---");
        {
            long frame = 50000;
            MHXXCharmApp.RNG rngLoop = new MHXXCharmApp.RNG();
            for (long i = 0; i < frame; i++) rngLoop.ascend();

            MHXXCharmApp.RNG rngJump = new MHXXCharmApp.RNG();
            rngJump.jumpRaw(frame);

            boolean allMatch = true;
            for (int i = 0; i < 20; i++) {
                rngLoop.ascend();
                rngJump.ascend();
                if (rngLoop.w != rngJump.w) {
                    allMatch = false;
                    System.out.printf("  ✗ frame=%d+%d: loop.w=%08X != jump.w=%08X%n",
                        frame, i+1, rngLoop.w, rngJump.w);
                    break;
                }
            }
            if (allMatch) {
                passed++;
                System.out.println("  ✓ frame=50000後の20ステップ出力が完全一致");
            } else {
                failed++;
            }
        }

        System.out.printf("%n=== 結果: %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
