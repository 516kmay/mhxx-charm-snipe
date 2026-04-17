import java.awt.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * UIテーマ定数（色・フォント）。
 * ユニバーサルデザイン対応・WCAG AA準拠の配色。
 *
 * 使い方:
 *   import static Theme.*;
 *   panel.setBackground(BG);
 */
public final class Theme {

    // ================================================================
    // Color Theme (ユニバーサルデザイン対応・WCAG AA準拠)
    // ================================================================
    public static final Color BG       = new Color(0x1a, 0x1a, 0x2e);   // 背景
    public static final Color BG2      = new Color(0x16, 0x21, 0x3e);   // パネル背景
    public static final Color FG       = new Color(0xe8, 0xe8, 0xe8);   // テキスト (BG上13.9:1 AAA)
    public static final Color ACCENT   = new Color(0xc0, 0x39, 0x2b);   // ボタン/ヘッダー背景 (白文字5.4:1 AA)
    public static final Color ACCENT_T = new Color(0xff, 0x99, 0x88);   // テキスト用アクセント (BG2上7.7:1 AAA)
    public static final Color ACCENT2  = new Color(0x1a, 0x44, 0x7a);   // 選択/紺 (白文字9.8:1 AAA)
    public static final Color SUCCESS  = new Color(0x00, 0xd2, 0xff);   // 水色 (BG2上8.8:1 AAA, 色覚安全)
    public static final Color WARN     = new Color(0xff, 0xc1, 0x07);   // 黄 (BG2上9.8:1 AAA, 色覚安全)
    public static final Color BTN_BG   = new Color(0x1a, 0x44, 0x7a);   // ボタン背景 = ACCENT2
    public static final Color GREEN    = new Color(0x00, 0x7a, 0x6e);   // ティール (白文字5.2:1 AA, 赤緑色覚安全)
    public static final Color DIM      = new Color(0x88, 0x88, 0xaa);   // 無効テキスト (BG上5.0:1 AA)

    // ================================================================
    // Fonts
    // ================================================================
    public static final Font FONT_UI;
    public static final Font FONT_UI_BOLD;
    public static final Font FONT_MONO;
    public static final Font FONT_MONO_SMALL;
    public static final Font FONT_LARGE;
    public static final Font FONT_TIMER;
    public static final Font FONT_SMALL;
    public static final Font FONT_HEADER;

    static {
        // 日本語対応フォントの候補（優先順）
        String[] candidates = {"Noto Sans JP", "Noto Sans CJK JP", "Yu Gothic UI",
                "Meiryo", "Hiragino Sans", "MS Gothic", "SansSerif"};
        String fontName = "SansSerif";
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Set<String> available = new HashSet<>(Arrays.asList(ge.getAvailableFontFamilyNames()));
        for (String c : candidates) {
            if (available.contains(c)) { fontName = c; break; }
        }
        // 等幅フォントの候補（日本語対応）
        String[] monoCandidates = {"Noto Sans Mono CJK JP", "MS Gothic", "Monospaced"};
        String monoName = "Monospaced";
        for (String c : monoCandidates) {
            if (available.contains(c)) { monoName = c; break; }
        }
        FONT_UI        = new Font(fontName, Font.PLAIN, 14);
        FONT_UI_BOLD   = new Font(fontName, Font.BOLD, 14);
        FONT_MONO      = new Font(monoName, Font.BOLD, 44);
        FONT_MONO_SMALL= new Font(monoName, Font.PLAIN, 13);
        FONT_LARGE     = new Font(fontName, Font.BOLD, 16);
        FONT_TIMER     = new Font(monoName, Font.PLAIN, 13);
        FONT_SMALL     = new Font(fontName, Font.PLAIN, 13);
        FONT_HEADER    = new Font(fontName, Font.BOLD, 20);
    }

    private Theme() {} // インスタンス化禁止
}
