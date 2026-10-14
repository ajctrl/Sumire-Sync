# Sumire Sync

Sumireのクリップボード履歴を、月次SQLiteアーカイブとしてWebDAV/Nextcloudへ片方向同期するAndroidアプリです。Sumire本体とはsignature permissionで保護されたContentProviderと明示的Broadcastだけで連携します。

## 依存関係の自動更新・公開

Dependabot alertsとsecurity updatesをGitHub側で有効にしています。
main更新時にGradleの依存一覧をGitHubへ送信し、間接依存も監視します。
Dependabotが作成したPRのうち、`app/build.gradle.kts`の`implementation`・`testImplementation`の安定版パッチ番号だけを上げる変更を自動処理します。
メジャー・マイナー更新、プレリリース、Gradleプラグイン・GitHub Actionsの変更、手動コミットを含むPRは手動確認が必要です。

- `main`の必須チェック`Build APK`に成功し、最新のmainに対してマージ可能なPRを自動マージします。
- `versionCode`を1、`versionName`のパッチ番号を1増やしたコミットを準備し、そのコミットで単体テストと署名付きAPKのビルド・署名検証を行います。
- 成功したコミットのチェック結果を記録してmainを更新し、`vX.Y.Z`タグとAPK付きGitHub Releaseを公開します。
- ビルド中にmainが進んだ場合は公開を止め、次回に新しいmainで再検証します。途中で公開が失敗した場合も、公開待ちのバージョンを再利用します。

処理はAndroid buildの成功後に起動し、毎時17分・47分（UTC）にも再確認します。
再試行はActionsの`Dependabot patch release` → `Run workflow`から実行できます。
署名には既存の`ANDROID_KEYSTORE_*`・`ANDROID_KEY_*`リポジトリSecretsを使います。
タグpushによる通常の手動リリースも引き続き利用できます。

## 実装範囲

- `apiVersion`、`databaseInstanceId`、`clipboardGeneration`、`currentSequence`の検証
- `id > lastId`、昇順、最大500件の増分取得
- テキストのContentProvider stream取得（画像itemは未対応としてskip）
- `createdAt`基準の`clipboard-YYYY-MM.sqlite`保存
- `(databaseInstanceId, clipboardGeneration, sourceId)`による重複防止
- 削除済みIDの欠損記録と、安全な`lastId`更新
- 整合したSQLiteスナップショットのWebDAV PUT
- 明示的Broadcastを単一のキューJobへ集約し、起動時、端末再起動時、6時間ごとにもcatch-up
- Usage Access許可時だけ有効になる、任意のforegroundアプリ推定（初期状態OFF）

削除同期、Pin変更同期、PCからAndroidへの逆同期、AccessibilityServiceは対象外です。

## 対応するSumire variant

1つのSumire Sync APKで、次の3 package IDをそのまま扱います。variantごとの再ビルドは不要です。

```text
com.example.sumire
com.example.sumire.lite
com.example.sumire.lite.fdroid
```

SumireとSumire Syncは同じ署名証明書で署名してください。異なる証明書では、Androidの`signature` permissionによりProvider参照とBroadcast受信が拒否されます。

## Sumire側に必要なAPI

authorityは各package IDに`.syncbridge.clipboard`を付けた値です。

接続時は、保存済みauthorityを優先して次の候補を順番に確認します。

```text
com.example.sumire.syncbridge.clipboard
com.example.sumire.lite.syncbridge.clipboard
com.example.sumire.lite.fdroid.syncbridge.clipboard
```

接続に成功したauthorityは端末内に保存し、`items`と本文streamの取得にも同じauthorityを使います。各variantのsignature permissionと更新BroadcastもSumire Sync側のManifestに宣言しています。

| URI | 返却内容 |
| --- | --- |
| `/status` | `apiVersion`, `databaseInstanceId`, `clipboardGeneration`, `currentSequence` |
| `/items?afterId=N&limit=M` | `id`, `itemType` (`TEXT` / `IMAGE`), `createdAt`, `isPinned`, `preview`, `contentUri` |
| `/items/{id}/content` | `openInputStream()`で読める本文または画像stream |

`createdAt`はUnix epoch milliseconds、itemsは`id ASC`、`limit`は最大500です。Providerの破壊的な契約変更時は`apiVersion`を更新してください。

Broadcast actionは`${packageId}.action.CLIPBOARD_CHANGED`、permissionは`${packageId}.permission.CLIPBOARD_SYNC`です。3 variant分を個別に宣言しています。BroadcastにClipboard本文を含めてはいけません。

## WebDAV設定

アプリ画面で、NextcloudサーバーのHTTPS URL（`https://url.com:port` まで）、ユーザー名、アプリパスワードを入力します。WebDAVのパス `/remote.php/dav/files/{ユーザー名}/Sumire/` はアプリが自動的に追加します。パスワードはAndroid KeystoreのAES-GCM鍵で暗号化して保存します。書き込み中のDB本体ではなく、一時スナップショットだけをアップロードします。

`Sumire`フォルダーがない場合はアップロード前に作成します。サーバーURLまたはユーザー名を変更すると、保存済みの全月次アーカイブを新しい接続先へ再送します。変更前の接続先への送信完了は、新しい接続先の送信済み状態には反映しません。

WebDAV未設定でもSumireから月次SQLiteへのローカル保存は継続します。設定後、未アップロードのrevisionが順次送信されます。

## 月次SQLite schema

主要テーブルは`clipboard_items`です。元データの識別情報、type、作成時刻、preview、本文の格納方式・サイズ、任意のforeground package/app labelに加え、フラグがONの間に同期した項目を示す`is_flagged`を保持します。フラグのトグルは設定時間後に自動でOFFになりますが、保存済みの`is_flagged`は変更しません。テキスト本文は`clipboard_content_chunks`へ64 KiB単位で保存され、同じ元データキーの`chunk_index`昇順で復元できます。旧schemaから移行した本文だけは`content_storage = INLINE`として`clipboard_items.content`に残ります。

同期制御情報と欠損ログは別の内部DB `sync-state.sqlite` に保存され、アップロード対象には含まれません。`IMAGE`は未対応として欠損ログへ記録し、本文を開かずに後続IDへ進みます。未知の`itemType`はデータ損失を避けるため同期を停止します。
