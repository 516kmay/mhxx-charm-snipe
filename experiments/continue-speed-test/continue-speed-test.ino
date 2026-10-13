// Continue周期: 公称150ms（A 75ms + B 75ms、USB送信時間などは別）
// MHXX Charm Snipe Tool - 高速Continue実機テスト用
// 起動 → Continue → ロード → 投入 → ケルビ納品 → 鑑定
// ユーザーの実機テストで選んだA/B各75msに、本番・校正用コードと統一した。
// 50回送信しても50回受け付けたとは限らないため、録画で確認する。
// 周期変更後の消費Fは未校正。従来500ms用の校正値は転用しない。
#include <NintendoSwitchControlLibrary.h>

unsigned long num_continue = 50;
unsigned long wait_ms = 10000;
unsigned long continue_hold_ms = 75; // A・Bそれぞれの保持時間。公称1周はこの2倍。

void waitWithKeepAlive(unsigned long total_ms,
                       uint16_t button = Button::X,
                       unsigned long interval_ms = 5000,
                       unsigned long press_ms = 100)
{
    if (total_ms <= 10000) { delay(total_ms); return; }
    unsigned long cycle = interval_ms;
    unsigned long n = total_ms / cycle;
    unsigned long rem = total_ms % cycle;
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

    // 高速Continue: Aだけ → Bだけを直接切り替える。
    for (unsigned long i = 0; i < num_continue; i++) {
        SwitchControlLibrary().releaseButton(Button::B);
        SwitchControlLibrary().pressButton(Button::A);
        SwitchControlLibrary().sendReport();
        delay(continue_hold_ms);
        SwitchControlLibrary().releaseButton(Button::A);
        SwitchControlLibrary().pressButton(Button::B);
        SwitchControlLibrary().sendReport();
        delay(continue_hold_ms);
    }
    SwitchControlLibrary().releaseButton(Button::B);
    SwitchControlLibrary().sendReport();

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

    // 投入確認ダイアログで待機（wait_ms、初期値10000ms）
    waitWithKeepAlive(wait_ms);

    // A連打で投入確定（このタイミングで乱数決定）
    pushButton(Button::A, 100, 2);
    pushButton(Button::B, 100, 5);

    // ケルビ納品クエスト
    SwitchControlLibrary().pressButton(Button::R);
    SwitchControlLibrary().sendReport();
    tiltLeftStick(Stick::NEUTRAL, Stick::MIN, 3000);
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
