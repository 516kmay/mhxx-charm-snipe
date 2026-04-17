import java.math.BigInteger;
import java.util.Arrays;

/**
 * MHXXの128bit Xorshift RNG (L15, R4, R21)。
 * 初期seedは全プラットフォーム・全セーブ共通の固定値。
 *
 * 主なメソッド:
 *   ascend()  - 1ステップRNGを進める
 *   roll()    - r[]をシフトしながらascend（お守り判定用）
 *   jump(F)   - Fフレームに高速ジャンプ（roll×7込み、お守り判定用）
 *   jumpRaw(F)- Fフレームに高速ジャンプ（roll×7なし、報酬逆算用）
 *
 * 使い方（お守り判定）:
 *   MHXXRng rng = new MHXXRng();
 *   rng.jump(1716);
 *   // rng.r[0..6] がお守り判定用の乱数値
 *
 * 使い方（報酬逆算）:
 *   MHXXRng rng = new MHXXRng();
 *   rng.jumpRaw(1000);
 *   rng.ascend();
 *   int val = (int)(rng.w % 32);
 */
public class MHXXRng {

    public static final long[] INITIAL_SEED = {0x0194FD72L, 0x79E6C985L, 0x08DD9701L, 0x41CFCE91L};
    public static final long MASK32 = 0xFFFFFFFFL;

    public long x, y, z, w, t;
    public long f;
    public long[] r = new long[7];

    public MHXXRng() { init(); }

    public void init() {
        x = INITIAL_SEED[0]; y = INITIAL_SEED[1];
        z = INITIAL_SEED[2]; w = INITIAL_SEED[3];
        t = 0; f = 0;
        Arrays.fill(r, 0);
    }

    public void ascend() {
        t = (x ^ (x << 15)) & MASK32;
        x = y; y = z; z = w;
        w = (w ^ (w >>> 21) ^ t ^ (t >>> 4)) & MASK32;
        f++;
    }

    public void descend() {
        long tt = (w ^ z ^ (z >>> 21)) & MASK32;
        tt = (tt ^ (tt >>> 4)) & MASK32;
        tt = (tt ^ (tt >>> 8)) & MASK32;
        tt = (tt ^ (tt >>> 16)) & MASK32;
        w = z; z = y; y = x;
        x = (tt ^ (tt << 15) ^ (tt << 30)) & MASK32;
        f--;
    }

    public void roll() {
        System.arraycopy(r, 1, r, 0, 6);
        r[6] = w;
        ascend();
    }

    // ----------------------------------------------------------------
    // GF(2)多項式演算（jump用）
    // ----------------------------------------------------------------
    static BigInteger polyMul(BigInteger p1, BigInteger p2) {
        BigInteger res = BigInteger.ZERO;
        while (p2.signum() > 0) {
            if (p2.testBit(0)) res = res.xor(p1);
            p1 = p1.shiftLeft(1);
            p2 = p2.shiftRight(1);
        }
        return res;
    }

    static BigInteger polyMod(BigInteger p, BigInteger m) {
        int mLen = m.bitLength();
        while (true) {
            int delta = p.bitLength() - mLen;
            if (delta < 0) break;
            p = p.xor(m.shiftLeft(delta));
        }
        return p;
    }

    static BigInteger polyPowMod(BigInteger base, BigInteger exp, BigInteger mod) {
        BigInteger res = BigInteger.ONE;
        base = polyMod(base, mod);
        while (exp.signum() > 0) {
            if (exp.testBit(0))
                res = polyMod(polyMul(res, base), mod);
            base = polyMod(polyMul(base, base), mod);
            exp = exp.shiftRight(1);
        }
        return res;
    }

    /** お守り判定用ジャンプ（roll×7込み） */
    public void jump(long frame) {
        init();
        BigInteger period = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
        BigInteger mod = new BigInteger("100000201a8362f671442057eea368001", 16);
        BigInteger fBig = BigInteger.valueOf(frame).mod(period);
        BigInteger rPoly = polyPowMod(BigInteger.TWO, fBig, mod);

        long sx = 0, sy = 0, sz = 0, sw = 0;
        while (rPoly.signum() > 0) {
            if (rPoly.testBit(0)) {
                sx ^= x; sy ^= y; sz ^= z; sw ^= w;
            }
            rPoly = rPoly.shiftRight(1);
            ascend();
        }
        x = sx; y = sy; z = sz; w = sw;
        f = frame;
        for (int i = 0; i < 7; i++) roll();
    }

    /** roll()なしでフレーム位置に直接ジャンプ。報酬逆算など非お守り用途向け。 */
    public void jumpRaw(long frame) {
        init();
        BigInteger period = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
        BigInteger mod = new BigInteger("100000201a8362f671442057eea368001", 16);
        BigInteger fBig = BigInteger.valueOf(frame).mod(period);
        BigInteger rPoly = polyPowMod(BigInteger.TWO, fBig, mod);

        long sx = 0, sy = 0, sz = 0, sw = 0;
        while (rPoly.signum() > 0) {
            if (rPoly.testBit(0)) {
                sx ^= x; sy ^= y; sz ^= z; sw ^= w;
            }
            rPoly = rPoly.shiftRight(1);
            ascend();
        }
        x = sx; y = sy; z = sz; w = sw;
        f = frame;
    }
}
