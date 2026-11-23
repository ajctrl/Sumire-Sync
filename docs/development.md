# 開発・リリース手順

[README に戻る](../README.md)

## ビルド環境

- JDK 17
- Android SDK（現在の CI では `platform-tools`、`platforms;android-37.0`、`build-tools;36.0.0` を使用）
- リポジトリに同梱した Gradle Wrapper（`./gradlew`、Windows では `gradlew.bat`）

SDK の設定は [`app/build.gradle.kts`](../app/build.gradle.kts)、CI の手順は [Android build](../.github/workflows/android.yml) を参照してください。

## テストと debug ビルド

リポジトリのルートで実行します。署名用の Secrets は不要です。

```sh
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

APK は `app/build/outputs/apk/debug/app-debug.apk` に出力されます。Sumire と連携するには、debug ビルドでも両アプリの署名証明書を一致させてください。

リリース自動化スクリプトのテストには Python 3 を使用します。

```sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
```

## リリースの起動と署名

手動公開は Actions の「Dependabot dependency release」→「Run workflow」で main を指定します。順番が来てからチェックアウトした main が対象です。先行する公開があればその実行が終わるまで待ち、順番が来ると同じ Actions 実行で採番・検証・署名・公開まで行います。署名には Sumire 本体と同じ証明書を使い、次のリポジトリ Secrets を設定してください。

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

タグ作成と Release 公開には組み込みの `GITHUB_TOKEN` を使います。実行中に main のワークフローが更新されて GitHub が公開を拒否した場合も、次の定期実行が最新の main から未公開の変更を拾います。

## 依存関係の自動更新・公開

### 依存関係の監視

Dependabot alertsとsecurity updatesをGitHub側で有効にしています。
main更新時にGradleの依存一覧をGitHubへ送信し、間接依存も監視します。
通常pushと自動リリース後の送信は共通のsnapshot識別子を使い、解析したcheckoutのSHAを記録します。旧workflow別のsnapshotは、新しい依存一覧の送信成功後に空のsnapshotで置き換え、更新前の依存が監視対象に残らないようにします。
push・手動実行・自動リリース後の依存送信は、共通のconcurrency groupでcheckoutから直列化します。送信直前にmainのSHAを確認し、解析中にmainが進んだ場合は古いsnapshotと旧snapshotの置き換えを送信しません。
依存関係の更新対象は [`.github/dependabot.yml`](../.github/dependabot.yml) で管理しています。OkHttp・JUnit・Robolectric・Bouncy Castleに加え、ビルドツール関連の依存も更新対象に含み、平日に毎日確認してメジャー更新も含めたPRを自動作成します。

### 自動取り込みの対象

Gradle の通常更新はビルドツール、Bouncy Castle、テスト依存、その他のライブラリに分けます。Dependabot のコミットだけで構成され、ルートまたは app の `build.gradle.kts` で依存・プラグインの安定版番号だけを上げる PR が自動マージの対象です。

Gradle Wrapper も、Dependabot のコミットだけで `gradle-wrapper.properties` の公式配布 URL の安定版番号を上げ、対応する SHA-256 を更新した PR は自動マージします。CI で Gradle 公式の SHA-256 と照合し、通常の依存 PR と同じく、同じコミットの push と pull_request の必須チェックが両方成功した時点で取り込みます。起動スクリプト、Wrapper JAR、その他の設定を含む変更は手動レビューの対象です。

### 公開するタイミング

10分ごとの定期実行と手動実行は同じキューで直列に動きます。順番が来た時点の main に、ダウンロードできる安定版 APK に含まれていない変更があれば公開します。通常の機能修正も対象です。

### 採番・検証・公開

1. 移行前の `versionCode` の最大値は 8 です。以後は固定値 8 にこのワークフローの `GITHUB_RUN_NUMBER` を加えて採番します。空振りや失敗した実行の番号は欠番になります。このワークフローを作り直して実行番号がリセットされる場合は、移行時にオフセットを見直してください。
2. main、既存の安定版タグおよび Release の最大の版からパッチ番号を一つ上げ、版番号だけを変更したコミットにタグを付けます。2台のランナーでこのコミットをビルドし、未署名 APK が一致したら署名します。署名前の SHA-256、署名後の manifest と埋め込み Git SHA を照合します。
3. 署名済み APK とチェックサムをドラフトに添付し、GitHub 側のダイジェストを照合してから公開します。失敗しても確保済みのタグは残します。次の定期実行または「Run workflow」が、その時点の未公開変更を新しい番号でまとめて公開します。「Re-run jobs」で古い実行から公開することはできません。
4. 公開済みの APK が対象の変更を含んでいれば、その実行は終了します。タグだけがある場合や APK を取り下げた場合は、次の実行で再び公開対象になります。

CI の失敗・タイムアウト、Wrapper の手動レビュー待ち、公開失敗は、対象 PR で理由ごとに1回だけ @ajctrl に通知します。タグの push から直接公開する経路はありません。
