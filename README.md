# Sumire Sync

Sumireのクリップボード履歴を、月次SQLiteアーカイブとしてWebDAV/Nextcloudへ片方向同期するAndroidアプリです。Sumire本体とはsignature permissionで保護されたContentProviderと明示的Broadcastだけで連携します。

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

WebDAV未設定でもSumireから月次SQLiteへのローカル保存は継続します。設定後、未アップロードのrevisionが順次送信されます。

## 月次SQLite schema

主要テーブルは`clipboard_items`です。元データの識別情報、type、作成時刻、preview、本文の格納方式・サイズ、任意のforeground package/app labelを保持します。テキスト本文は`clipboard_content_chunks`へ64 KiB単位で保存され、同じ元データキーの`chunk_index`昇順で復元できます。旧schemaから移行した本文だけは`content_storage = INLINE`として`clipboard_items.content`に残ります。

同期制御情報と欠損ログは別の内部DB `sync-state.sqlite` に保存され、アップロード対象には含まれません。`IMAGE`は未対応として欠損ログへ記録し、本文を開かずに後続IDへ進みます。未知の`itemType`はデータ損失を避けるため同期を停止します。
