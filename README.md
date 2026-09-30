# Swipe Drawer for Claude

[日本語](#日本語)

Swipe right in the conversation area of the **Claude Android app** to open its sidebar — like on iPad — instead of reaching for the menu button in the top-left corner.

> **Unofficial.** This project is not affiliated with or endorsed by Anthropic. "Claude" is a trademark of Anthropic. The Claude app itself is not modified.

## How it works

It is an accessibility service. While Claude is on screen:

- Touches inside the swipe area are held briefly. A right swipe presses Claude's menu button for you; anything else (scrolling, taps, long presses) is handed straight back to Claude.
- Touches outside the swipe area go to Claude untouched.
- When you switch to another app, the service stops watching touches.

While the keyboard is open the service steps back (keyboards stop accepting normal taps otherwise). On Claude's home screen, where the keyboard opens automatically, a transparent catcher over the swipe area still lets you swipe.

## Privacy

- **No internet permission.** Nothing on your screen can leave your device.
- The app reads the layout of the Claude app only to locate the menu button and the message box. It does not read, store or send your conversations.

## Install

1. Download the APK from [Releases](../../releases) and open it (for example from the Downloads folder in your file manager).
   - If you are asked to allow installing unknown apps, allow it for that app.
   - If Play Protect shows a warning, choose to install anyway.
2. **Allow restricted settings.** Android blocks accessibility access for apps installed from a file. Go to **Settings → Apps → Swipe Drawer for Claude**, tap **⋮** in the top right and choose **Allow restricted settings**.
3. Open **Settings → Accessibility → Installed apps → Swipe Drawer for Claude** and turn it on.
4. Open the **Swipe Drawer for Claude** app to adjust the swipe area (width, left-edge gap, top and bottom margins). Turn on "Show the swipe area" to see it on Claude's screen while you adjust.

Requires Android 13 or later. The app's own screen is available in English and Japanese (switch in the app, or in Settings → Apps → Swipe Drawer for Claude → Language).

## Limitations

- It finds Claude's buttons by their accessibility labels. If a Claude update renames or moves them, the swipe stops working until this app is updated.
- Tested with Claude 1.260928.20 (English and Japanese UI) on a Galaxy S24 Ultra (Android 16). Other languages and devices are best effort — the button labels are only known for English and Japanese.
- While the service is active, Android reports "touch exploration" as on to other apps, the same signal TalkBack uses. This only happens while Claude is on screen.

Issues and pull requests are welcome, but support is best effort.

## Support

If this app saves your thumb some stretching, you can [buy me a coffee](https://buymeacoffee.com/watatatata810).

## Build from source

```bash
./gradlew assembleDebug
```

AGP 8.13 / Gradle 8.13 / Kotlin 2.2 / JDK 17+. For a signed release build, put `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in the project root and run `./gradlew assembleRelease`.

## License

[MIT](LICENSE)

---

## 日本語

**Android 版 Claude アプリ**の会話エリアを右にスワイプすると、iPad と同じようにサイドバーが開きます。左上のメニューボタンまで指を伸ばす必要がなくなります。

> **非公式アプリです。** Anthropic とは無関係で、同社の承認も受けていません。「Claude」は Anthropic の商標です。Claude アプリ自体は改変しません。

### 仕組み

アクセシビリティサービス (ユーザー補助) として動きます。Claude が画面に出ている間だけ:

- 判定範囲の中のタッチを一瞬預かり、右スワイプならメニューボタンを代わりに押します。スクロール・タップ・長押しなどはそのまま Claude に返します。
- 判定範囲の外のタッチは、そのまま Claude に届きます。
- 他のアプリに切り替えると、タッチの監視をやめます。

キーボード表示中は動作を控えます (キーボードが普通のタップを受け付けなくなるため)。キーボードが自動で開く Claude のトップ画面では、判定範囲に透明な受け口を置いてスワイプを受けます。

### プライバシー

- **インターネット権限を持っていません。** 画面の内容が端末の外に出ることはありません。
- Claude アプリの画面構造を読むのは、メニューボタンと入力欄の位置を知るためだけです。会話の内容を読んだり、保存・送信したりはしません。

### インストール

1. [Releases](../../releases) から APK をダウンロードして開きます (ファイル管理アプリの「ダウンロード」から開くなど)。
   - 「不明なアプリのインストール」の許可を求められたら、そのアプリに許可します。
   - Play プロテクトの警告が出たら、「インストールする」を選びます。
2. **制限付き設定を許可します。** ファイルから入れたアプリは、Android がユーザー補助の利用を止めています。**設定 → アプリ → Swipe Drawer for Claude** を開き、右上の **⋮** から**制限付き設定を許可**を選びます。
3. **設定 → ユーザー補助 → インストール済みアプリ → Swipe Drawer for Claude** をオンにします。
4. **Swipe Drawer for Claude** アプリを開くと、判定範囲 (幅・左端の除外・上下の余白) を調整できます。「判定範囲を Claude の画面に表示する」をオンにすると、調整中に範囲が見えます。

Android 13 以上が必要です。アプリの画面は英語と日本語に対応しています (アプリ内、または 設定 → アプリ → Swipe Drawer for Claude → 言語 で切り替え)。

### 制限事項

- Claude のボタンをアクセシビリティ上の名前で探しています。Claude の更新でボタンの名前や配置が変わると、このアプリが更新されるまで動かなくなります。
- 動作確認は Claude 1.260928.20 (英語 UI・日本語 UI)・Galaxy S24 Ultra (Android 16) です。ボタン名を把握しているのは英語と日本語だけで、他の言語・機種はベストエフォートです。
- 動作中は、TalkBack と同じ「タッチ探索が有効」という合図が他のアプリにも伝わります。Claude が画面に出ている間だけです。

Issue・プルリクエストは歓迎しますが、対応はベストエフォートです。

### 応援

親指の負担が減ったと感じたら、[コーヒー 1 杯分の応援](https://buymeacoffee.com/watatatata810)をいただけると励みになります。

### ライセンス

[MIT](LICENSE)
