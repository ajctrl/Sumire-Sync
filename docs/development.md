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

## タグによるリリース

タグを push すると、Android build が単体テスト、署名付き APK のビルド・署名検証を実行し、APK 付き GitHub Release を公開します。署名には Sumire 本体と同じ証明書を使い、次のリポジトリ Secrets を設定してください。

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## 依存関係の自動更新・公開

### 依存関係の監視

Dependabot alertsとsecurity updatesをGitHub側で有効にしています。
main更新時にGradleの依存一覧をGitHubへ送信し、間接依存も監視します。
通常pushと自動リリース後の送信は共通のsnapshot識別子を使い、解析したcheckoutのSHAを記録します。旧workflow別のsnapshotは、新しい依存一覧の送信成功後に空のsnapshotで置き換え、更新前の依存が監視対象に残らないようにします。
push・手動実行・自動リリース後の依存送信は、共通のconcurrency groupでcheckoutから直列化します。送信直前にmainのSHAを確認し、解析中にmainが進んだ場合は古いsnapshotと旧snapshotの置き換えを送信しません。
依存関係の更新対象は [`.github/dependabot.yml`](../.github/dependabot.yml) で管理しています。OkHttp・JUnit・Robolectric・Bouncy Castleに加え、ビルドツール関連の依存も更新対象に含み、平日に毎日確認してメジャー更新も含めたPRを自動作成します。

### 自動取り込みの対象

Dependabotが作成したPRのうち、`app/build.gradle.kts`の`implementation`・`testImplementation`の安定版パッチ番号だけを上げる変更を自動処理します。
加えて、OkHttpおよびテスト依存のJUnit・Robolectric・Bouncy Castleは、安定版のマイナー・メジャー更新も自動処理します。特定のメジャー番号には限定せず、テスト・ビルド成功を条件に自動取り込みします。Bouncy Castleの`1.86 → 1.87`やRobolectricの`4.14.1 → 4.17`のように、パッチ番号を省略したバージョンにも対応します。
それ以外の依存のマイナー・メジャー更新、プレリリース、Gradleプラグイン・GitHub Actionsの変更、手動コミットを含むPRは手動確認が必要です。SDKやビルド設定の変更が必要でチェックに失敗する更新は、自動取り込みされません。

### 自動リリースの流れ

- `main`の必須チェック`Build APK`に成功し、最新のmainに対してマージ可能なPRを自動マージします。
- `versionCode`を1、`versionName`のパッチ番号を1増やしたコミットを準備し、そのコミットで単体テストと署名付きAPKのビルド・署名検証を行います。
- 成功したコミットのチェック結果を記録してmainを更新し、`vX.Y.Z`タグとAPK付きGitHub Releaseを公開します。
- ビルド中にmainが進んだ場合は公開を止め、次回に新しいmainで再検証します。途中で公開が失敗した場合も、公開待ちのバージョンを再利用します。

### 実行タイミングと再試行

処理はAndroid buildの成功後に起動し、毎時17分・47分（UTC）にも再確認します。
再試行はActionsの`Dependabot dependency release` → `Run workflow`から実行できます。

### 署名情報の扱い

署名には既存の`ANDROID_KEYSTORE_*`・`ANDROID_KEY_*`リポジトリSecretsを使います。
単体テストとdebugビルドには署名Secretsを渡しません。タグのリリースビルドと、自動リリースのテスト成功後の署名ビルドにだけ渡し、展開したkeystoreは処理終了時に削除します。
タグpushによる通常の手動リリースも引き続き利用できます。
