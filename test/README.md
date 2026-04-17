# test/

開発用のテストコードを格納するフォルダ。

## 実行方法

### Windows
`test/` ディレクトリで `run-tests.bat` をダブルクリック、または：

```
cd test
run-tests.bat
```

### Linux / Mac

```
cd test
./run-tests.sh
```

## テスト一覧

| ファイル | 内容 |
| --- | --- |
| `RewardReverseTest.java` | 報酬逆算エンジン（RNG + 報酬テーブル + 逆算ロジック）の正しさ検証 |
| `JumpRawTest.java` | `RNG.jumpRaw()` が `ascend()` ループと同一結果を返すことの検証 |
| `AppraiseTimerTest.java` | 鑑定タイマー関連（時刻フォーマット、報酬生成終了F計算、待機時間計算）の検証 |

## 注意

テストは `../src/*.java` を参照してコンパイルする。本体のビルドには影響しない。
