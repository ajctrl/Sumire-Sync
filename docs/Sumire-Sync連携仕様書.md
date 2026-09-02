# Sumire-Sync連携仕様書

**Version: v0.7**

## 目的

Sumire本体の変更を最小限に抑えつつ、別アプリ **Sumire Sync** からクリップボード履歴を取得できるようにする。

Sumire本体は引き続き **ネットワーク通信を行わない**。

---

## 基本方針

- 同期処理は **Sumire Sync** に分離する
- Sumire本体のRoom DBは外部アプリから直接アクセスさせない
- Sumire側には **read-only ContentProvider** を追加する
- `signature` permission により、同じ署名証明書を持つ信頼済みアプリのみアクセス可能にする
- Providerのauthority / permission名は `${applicationId}` ベースで定義し、fork名やapplicationId変更に追従できるようにする
- 既存のクリップボード保存処理、Room schema、Repositoryの挙動は極力変更しない
- 増分取得はfork専用の `ClipboardSyncProvider` からread-onlyでSQLiteを直接参照し、既存DAOは変更しない
- upstream Sumireとの差分を最小化し、将来の追従を容易にする
- 同期契機は基本的にコピー保存時のイベント通知とし、常時ポーリングは行わない
- コピー元候補のアプリ情報はSumire本体では取得せず、必要な場合のみSumire Sync側で直近foregroundアプリを推定する
- 初期実装の同期対象はテキストのみとし、画像は後回しにする
- 画像itemはメタデータで判別して未対応として記録・skipし、後続テキストの同期を妨げない

---

## 追加する機能

### ClipboardSyncProvider

Sumireのクリップボード履歴を読み出すための専用ContentProviderを追加する。

役割は以下のみ。

- クリップボード履歴のメタデータ読み取り
- `id > lastId` による増分取得
- 件数制限（LIMIT）
- Provider API version（`apiVersion`）取得
- 現在の採番値（`currentSequence`）取得
- データ領域識別子（`databaseInstanceId`）取得
- Clipboardテーブル世代（`clipboardGeneration`）取得
- テキスト本文へのstreamアクセス

同期状態やNextcloud通信は担当しない。

```text
Sumire Room DB / ClipboardFileStore
              ↓
     ClipboardSyncProvider
              ↓ signature IPC
          Sumire Sync
```

---

## Clipboard更新通知

Sumireで新しいClipboard履歴の保存が完了した時点で、**Sumire Syncへ明示的Broadcastを送信する**。

BroadcastにはClipboard本文・画像などの実データを含めない。

通知の役割は以下のみ。

```text
「Clipboard履歴が更新された」
```

実データはSumire Syncが `ClipboardSyncProvider` 経由で取得する。

構成:

```text
Clipboard変更
    ↓
Sumireが既存処理で履歴保存
    ↓
Explicit Broadcast
    ↓
Sumire SyncのBroadcastReceiver起動
    ↓
ContentProviderから lastId より新しい項目を取得
```

BroadcastはSumire Syncのpackageを明示的に指定し、signature permissionで保護する。

例:

```kotlin
val sumirePackage = context.packageName
val action = "$sumirePackage.action.CLIPBOARD_CHANGED"
val permission = "$sumirePackage.permission.CLIPBOARD_SYNC"

val intent = Intent(action).apply {
    setPackage(SUMIRE_SYNC_PACKAGE_ID)
}

context.sendBroadcast(
    intent,
    permission
)
```

`SUMIRE_SYNC_PACKAGE_ID` はfork側の設定値として管理する。

Sumire側は通知後の同期状態を保持しない。

## アクセス制御

専用のsignature permissionを定義する。

```xml
<permission
    android:name="${applicationId}.permission.CLIPBOARD_SYNC"
    android:protectionLevel="signature" />
```

Providerにもこの権限を設定する。

```xml
<provider
    android:name=".syncbridge.ClipboardSyncProvider"
    android:authorities="${applicationId}.syncbridge.clipboard"
    android:exported="true"
    android:readPermission="${applicationId}.permission.CLIPBOARD_SYNC" />
```

これにより、同じ署名証明書を持つ信頼済みアプリ以外からのアクセスを防ぐ。

Sumire Syncだけに限定したい場合は、必要に応じてProvider側でも呼び出し元パッケージを検証する。

### BroadcastReceiver側の保護

Sumire Sync側のReceiverも同じsignature permissionで保護する。

例:

```xml
<uses-permission
    android:name="com.example.sumire.permission.CLIPBOARD_SYNC" />

<receiver
    android:name=".sync.ClipboardChangedReceiver"
    android:exported="true"
    android:permission="com.example.sumire.permission.CLIPBOARD_SYNC">
    <intent-filter>
        <action android:name="com.example.sumire.action.CLIPBOARD_CHANGED" />
    </intent-filter>
</receiver>
```

実際のpermission名・action名は、接続先Sumireのpackage IDを基準に組み立てる。

これにより、第三者アプリから偽Broadcastを大量送信されてSumire Syncが不要にwakeされることを防ぐ。

### Sumire Sync側のauthority解決

Sumire Sync側は、自分自身の `applicationId` ではなく、**接続先Sumireのpackage ID** を基準にauthorityを組み立てる。

例:

```text
SUMIRE_PACKAGE_ID = com.example.sumire

authority =
com.example.sumire.syncbridge.clipboard
```

Sumire Syncは専用連携アプリなので、初期実装では接続先Sumireのpackage IDを **build-time設定値** として持つ。

permission名・Broadcast action名・Provider authorityも、このpackage IDからビルド時に決定する。

実行時の自動検出は初期実装では行わない。

---

## 増分取得

既存DAOは変更しない。

増分取得は、fork専用の `ClipboardSyncProvider` から `AppDatabase.openHelper.readableDatabase` を経由してread-onlyで直接queryする。

例:

```kotlin
private fun queryAfterId(
    database: AppDatabase,
    afterId: Long,
    limit: Int
): Cursor {
    val db = database.openHelper.readableDatabase

    return db.query(
        """
        SELECT
            id,
            itemType,
            timestamp AS createdAt,
            isPinned,
            preview
        FROM clipboard_history
        WHERE id > ?
        ORDER BY id ASC
        LIMIT ?
        """.trimIndent(),
        arrayOf(afterId.toString(), limit.toString())
    )
}
```

Provider側では `SELECT *` を使用せず、同期APIとして必要なcolumnだけを明示的に取得する。

これにより、upstream側で内部DBにcolumnが追加されてもProvider APIへの影響を最小化できる。

Providerが外部へ返すcolumn名は、内部DB schemaそのものではなく **Sync API contract** として固定する。

`timestamp` は外部APIでは `createdAt` として返す。

`createdAt` の単位は **Unix epoch milliseconds** とする。

実装時にSumire内部の `timestamp` が同じ単位であることを確認し、異なる場合のみProvider側で変換する。

`contentUri` はDB columnではなく、各 `id` からProvider側で生成する。

例:

```text
id = 1253
→ content://${applicationId}.syncbridge.clipboard/items/1253/content
```

これにより、以下を維持する。

```text
既存DAO変更なし
Room schema変更なし
Entity変更なし
Repository変更なし
```

`currentSequence` も同じ `AppDatabase.openHelper.readableDatabase` 経由で `sqlite_sequence` を参照する。

---

## 取得方式

Sumire Sync側が `lastId` を保持する。

```text
lastId = 1250
```

次回取得:

```text
id > 1250
ORDER BY id ASC
LIMIT 500
```

取得結果:

```text
1251
1252
1253
```

正常にSumire Sync側へ保存できた時点で:

```text
lastId = 1253
```

と更新する。

`lastId` は **Sumire Sync側SQLiteへの永続化がID順に連続して成功した最大ID** とする。

例:

```text
1251 保存成功
1252 保存成功
1253 取得失敗

→ lastId = 1252
```

途中で失敗した場合は、原則としてそのIDで処理を止める。

ただし、Sumire側ですでに削除されているなど、テキスト本文が明確に存在しない場合は、そのIDを欠損としてログへ記録してskipし、次のIDへ進める。

初期実装では画像itemも未対応としてログへ記録してskipし、そのIDまで `lastId` を進める。画像本体のstreamは取得しない。

このための専用Outboxや削除同期テーブルは初期実装では追加しない。

500件取得できた場合は、最後のIDを使って続けて取得する。

```text
取得件数 < 500
→ 現在の最新まで追いついた

取得件数 == 500
→ まだ続きがある可能性があるため再取得
```

複雑なcursor paginationや同期状態管理はSumire本体には追加しない。

---

## Sumire DBリセット対策

通常の単件削除・自動削除・全件削除では、`AUTOINCREMENT` の採番値は基本的に巻き戻らない。

そのため、通常の増分同期は引き続き `lastId` を利用する。

ただし、以下の場合はID系列そのものがリセットされる可能性がある。

- Sumireの再インストール
- アプリデータ消去
- `clipboard_history` テーブルのDROP / 再作成
- DBの再生成

この対策として、Sumire側のProviderから以下を取得できるようにする。

```text
apiVersion
databaseInstanceId
clipboardGeneration
currentSequence
```

### databaseInstanceId

Sumireのデータ領域ごとに一意となるUUIDを生成する。

保存先は自動バックアップ対象にならない `noBackupFilesDir` を使用する。

例:

```text
noBackupFilesDir/
└── syncbridge-instance-id
```

内容:

```text
550e8400-e29b-41d4-a716-446655440000
```

アプリデータ消去・再インストールによってこの値が変化した場合、Sumire Syncは新しいデータ領域と判断する。

### clipboardGeneration

`clipboard_history` のID namespaceが変わったことを識別する、Sumire側の固定version値。

初期値:

```kotlin
private const val CLIPBOARD_GENERATION = 1
```

通常の以下の操作では変更しない。

- 単件削除
- 自動削除
- 全件削除
- Pin変更
- 通常のINSERT

将来、`clipboard_history` を `DROP TABLE` → `CREATE TABLE` するmigrationなど、ID系列そのものを再生成する変更を取り込む場合のみ、コード側で明示的に値を上げる。

例:

```kotlin
private const val CLIPBOARD_GENERATION = 2
```

`clipboardGeneration` は永続ストレージに保存して加算する値ではなく、**そのSumireバージョンが使用するClipboard ID namespaceのversion** として扱う。

これにより、Syncが長時間停止している間にテーブルが再作成され、その後 `currentSequence` が旧 `lastId` を超えるまで進んだ場合でも世代交代を検出できる。

### currentSequence

`clipboard_history` が `AUTOINCREMENT` を使用しているため、SQLite内部の `sqlite_sequence` から現在の採番値を取得する。

Room schemaやEntityは変更しない。

例:

```kotlin
private fun getCurrentSequence(database: AppDatabase): Long {
    val db = database.openHelper.readableDatabase

    db.query(
        "SELECT seq FROM sqlite_sequence WHERE name = ?",
        arrayOf("clipboard_history")
    ).use { cursor ->
        return if (cursor.moveToFirst()) {
            cursor.getLong(0)
        } else {
            0L
        }
    }
}
```

`currentSequence` は主に整合性確認・異常検知に使用する。

### apiVersion

SumireとSumire Syncは別リポジトリとして管理するため、Provider APIの互換性判定用にversionを返す。

初期値:

```text
apiVersion = 1
```

Providerの返却columnやURI仕様に破壊的変更がある場合のみversionを更新する。

### status API

Providerでは、例えば以下のstatusを返す。

```text
content://${applicationId}.syncbridge.clipboard/status
```

返却例:

```text
apiVersion = 1
databaseInstanceId = 550e8400-e29b-41d4-a716-446655440000
clipboardGeneration = 1
currentSequence = 15231
```

### Sumire Sync側の判定

Sumire Syncは以下を保持する。

```text
apiVersion
databaseInstanceId
clipboardGeneration
lastId
```

通常:

```text
保存済み:
databaseInstanceId = A
clipboardGeneration = 1
lastId = 15231

Sumire:
databaseInstanceId = A
clipboardGeneration = 1
currentSequence = 15240

→ id > 15231 を取得
```

再インストール・データ消去:

```text
保存済み:
databaseInstanceId = A

Sumire:
databaseInstanceId = B

→ 新しいデータ領域と判断
→ 保存している databaseInstanceId を更新
→ lastId = 0
```

Clipboardテーブル再作成:

```text
保存済み:
clipboardGeneration = 1
lastId = 15231

Sumire:
clipboardGeneration = 2

→ ID系列が再生成されたと判断
→ 保存している clipboardGeneration を更新
→ lastId = 0
```

同じ `databaseInstanceId` / `clipboardGeneration` のまま `currentSequence < lastId` となった場合は異常状態として扱い、安全側に倒して再同期またはユーザー通知を行う。

同期用DB内では、以下の組み合わせで元データを識別する。

```text
(databaseInstanceId, clipboardGeneration, sourceId)
```

これにより、Sumire側でIDが再利用されてもPC側で衝突しない。

`MAX(id)` は単件削除や全件削除で値が下がるため、DBリセット判定には使用しない。

---

## ContentProviderの返却内容

`query()` では本文データを直接返さず、メタデータのみ返す。画像itemも同期対象の判定に必要なメタデータは返す。

例:

```text
id
itemType (`TEXT` / `IMAGE`)
createdAt
isPinned
preview
contentUri
```

同期対象となるテキスト本文は、ContentProviderのstream経由で取得する。

例:

```text
content://${applicationId}.syncbridge.clipboard/items/1253/content
```

Sumire Sync側:

```kotlin
contentResolver.openInputStream(contentUri)
```

Provider側では `openFile()` / `openAssetFile()` 等を利用して、既存のClipboardFileStoreに保存されているテキスト本文を読み出す。

これにより、長文をCursorへ直接載せることを避ける。

画像itemについては `itemType` で判別し、Sumire Syncは `contentUri` を開かずに未対応としてskipする。画像本体のstream取得・月次SQLiteへの保存・アップロードは初期実装に含めない。

`itemType` はSync API contractとして大文字の `TEXT` / `IMAGE` を返す。未知の値は将来の新しいtypeである可能性があるため、欠損扱いで自動skipせず、そのIDで同期を停止してAPI互換性エラーとして扱う。

---

## コピー元候補アプリの推定（オプション）

コピーされたClipboardがどのアプリ由来かを補助情報として残したい場合、**Sumire本体には追加のアプリ判定処理を実装しない**。

SumireからのExplicit Broadcastで起動した **Sumire Sync側** が、コピー時刻付近のforegroundアプリを取得して同期用SQLiteへ保存する。

構成:

```text
アプリ上で「リンクをコピー」
    ↓
Android Clipboard更新
    ↓
Sumireが履歴保存
    ↓
Explicit Broadcast
    ↓
Sumire Sync wake
    ├─ ProviderからClipboard取得
    └─ 直近foregroundアプリを推定
            ↓
      月次SQLiteへ一緒に保存
```

### 取得方法

Sumire Sync側で `UsageStatsManager.queryEvents()` を利用し、Clipboardの `createdAt` 以前のUsage Eventを一定範囲さかのぼって確認する。

単に「直前数秒」のeventだけを見るのではなく、`createdAt` 時点でforegroundだった可能性が最も高いpackageをbest-effortで推定する。

foreground遷移の手掛かりにはAPI levelに応じて以下を使用する。

```text
API >= 29
→ ACTIVITY_RESUMED

API 21–28
→ MOVE_TO_FOREGROUND
```

`MOVE_TO_FOREGROUND` はAPI 29以降ではdeprecatedのため、新しいAndroidでは `ACTIVITY_RESUMED` を使用する。

保存例:

```text
foregroundPackage = com.google.android.youtube
foregroundAppName = YouTube
```

この情報はAndroid Clipboard APIが提供する「正式なコピー元」ではないため、**コピー元アプリと断定しない**。

`foregroundPackage` / `foregroundAppName` はnullableとし、推定できない場合は `null` のままClipboard同期を継続する。

`foregroundPackage` を主データとし、`foregroundAppName` はPackageManager等から取得できた場合のみ保存する表示用情報とする。

例:

```text
foregroundPackage = com.example.app
foregroundAppName = null
```

この状態も正常ケースとして扱う。

foreground推定は付加情報であり、Usage Access未許可、eventなし、`SecurityException` 等が発生してもClipboard本体の保存・`lastId` 更新を妨げない。

### 権限

この機能を使用する場合のみ、Sumire Sync側で使用状況アクセスを利用する。

```text
PACKAGE_USAGE_STATS
```

ユーザーによる「使用状況へのアクセス」の明示的な許可が必要になるため、この機能は **任意・初期状態OFF** とする。

Sumire本体にはこの権限を追加しない。

### タイミング

Sumire Syncはコピー保存直後のExplicit Broadcastで起動される。

foreground推定では、起動時点のアプリだけを見るのではなく、Clipboardの `createdAt` を基準に、それ以前のUsage Eventを一定時間さかのぼって確認する。

1回のBroadcastで複数のClipboard itemをbatch取得した場合も、**各item自身の `createdAt` を基準に個別にforeground候補を判定する**。

実装上はbatch全体を包含する時間範囲のUsage Eventを一度取得し、各 `createdAt` に対して割り当ててもよい。

例:

```text
04:00 YouTube ACTIVITY_RESUMED
05:00 Clipboard createdAt
05:00 Sumire Sync wake

→ 05:00時点のforeground候補としてYouTubeを推定
```

アプリを長時間開いたままコピーするケースもあるため、「直前数秒以内にeventが存在すること」は前提にしない。

Usage Eventは永久保存される情報ではないため、長期間停止した後のcatch-upではforegroundアプリを推定できない場合がある。

その場合:

```text
foregroundPackage = null
foregroundAppName = null
```

として扱い、Clipboard本体の同期は通常どおり継続する。

```text
Clipboard取得
    ↓
foreground推定
    ├─ 成功 → package / appNameを付加
    └─ 失敗 → null
    ↓
月次SQLiteへcommit
    ↓
lastId更新
```

### 将来の代替案

UsageEvents方式で実利用上の精度が不足した場合のみ、よりリアルタイムにforeground window / packageを追跡できる **AccessibilityService** の採用を再検討する。

AccessibilityServiceは強いアクセス権限を伴うため、初期実装には含めない。

## 既知の制約

PC側はコピー履歴のアーカイブ用途とし、Sumire側で削除された履歴をPC側から削除する同期は行わない。

ただし、以下の競合では取りこぼしが発生する可能性がある。

```text
Sumire Sync
query()でID=1253を取得
        ↓
Sumire側でID=1253を削除
        ↓
Sumire Sync
openInputStream(contentUri)
        ↓
テキスト本文がすでに存在しない
```

また、Sumire Syncが取得する前に履歴そのものが削除された場合、そのClipboardはPCアーカイブへ保存されない。

テキスト本文が取得不能になっているIDは欠損としてskipし、後続IDの同期は継続する。

初期実装ではこのケースを許容する。

**コピーされたデータを必ず一度はPCへ届ける保証が必要になった場合のみ、Outbox方式を別途検討する。**

## Sumire側で保持しないもの

以下はSumire本体には実装しない。

- Nextcloud認証情報
- Nextcloud API通信
- PCとの通信
- 同期済みフラグ
- 同期キュー
- 月次SQLite DB
- 再送処理
- 暗号化処理
- 同期スケジュール
- PC側状態管理

これらはすべて **Sumire Sync** の責務とする。

---

## Sumire Sync側の想定

```text
Sumire
  ↓ ContentProvider

Sumire Sync
  ├─ BroadcastReceiver
  ├─ apiVersion管理
  ├─ databaseInstanceId管理
  ├─ clipboardGeneration管理
  ├─ lastId管理
  ├─ Broadcast受信時にcatch-up要求を永続化・集約
  ├─ CatchUpJobで小さいbatchを順次取得
  ├─ 必要に応じてforegroundアプリを推定
  ├─ createdAtで年月判定
  ├─ clipboard-YYYY-MM.sqliteへ保存
  ├─ SQLiteスナップショット生成
  └─ Nextcloud API / WebDAVへアップロード
```

DB例:

```text
clipboard-2026-09.sqlite
clipboard-2026-10.sqlite
clipboard-2026-11.sqlite
```

月の判定には同期日時ではなく、元のクリップボード項目の `createdAt` を使用する。

---

## 同期実行方式

通常時は30〜60秒周期などの定期pollingを行わない。

新しいClipboard履歴が保存された時だけ、SumireからSumire Syncへ明示的Broadcastを送信し、Sumire Syncを起動する。

```text
通常時
Sumire Sync
  process停止可
  pollingなし
  network通信なし

コピー発生
    ↓
Sumire
    ↓ Broadcast
Sumire Sync wake
    ↓
同期要求を永続化してCatchUpJobへ集約
    ↓
Providerから増分取得
    ↓
月次SQLiteへ保存
    ↓
lastId更新
```

### BroadcastReceiverの責務

BroadcastReceiverは短時間で完了する処理のみ担当し、Provider照会やstream取得は行わない。

Receiverは同期要求のrevisionをSumire Sync側SQLiteへ永続化し、CatchUpJobを予約して直ちに終了する。複数のBroadcastでJobを増殖させず、全対応APIで最大2個の固定Job IDを交互に使う。実行中のIDは再scheduleせず、後続スロットがすでに存在する場合は要求revisionの更新のみ行う。Jobは実行開始時に最新revisionを読むため、Broadcast数に比例した `JobWorkItem` キューを作らない。

通常フロー:

```text
Broadcast受信
    ↓
同期要求revisionを永続化
    ↓
CatchUpJobへenqueue
    ↓
Broadcast処理終了
```

CatchUpJobは古い未同期IDから一定件数ずつ処理する。

```text
CatchUpJob
    ↓
status確認
    ↓
query(afterId, limit)
    ↓
テキスト本文のcontent stream取得
    ↓
月次SQLiteへcommit
    ↓
lastId更新
    ↓
残件または実行中に新しい要求あり
    ↓
retry Jobへ委譲
```

1回のJob実行で処理するbatch数には上限を設ける。処理中に届いたBroadcastは永続化した要求revisionへ集約し、完了直前に要求revisionと残件を再確認する。失敗時は `jobFinished(..., true)` のbackoffに委ね、実行中のJobに対する同一IDの `schedule()` は行わない。

増分取得は常に `ORDER BY id ASC` とし、古い未同期IDから順番に処理する。

Nextcloud通信はネットワーク遅延や失敗が発生し得るため、BroadcastReceiverやCatchUpJob内で完結させない。

ローカルDBへの保存が完了した後、別のバックグラウンド処理へNextcloud uploadを依頼する。

upload要求は実行中のJobを同一IDの `schedule()` で置換しない。全対応APIで最大2個の
固定Job IDを交互に使い、後続スロットも存在する場合は追加Jobを作らない。Upload Jobは
実行開始時の要求数ではなく、永続化された `dirty_archives` が空になるまで再確認する。
このためBroadcast数に比例した `JobWorkItem` キューは作らない。

CatchUp Jobの終了時もその実行での保存件数ではなく `dirty_archives` を確認する。
これにより、月次SQLiteのcommitと `lastId` 更新後、upload予約前にプロセスが
終了した場合でも、次回catch-upで未送信アーカイブを回収できる。

```text
月次SQLite commit
    ↓
upload要求
    ↓
CatchUpJob終了

別処理
    ↓
SQLiteスナップショット生成
    ↓
Nextcloud API / WebDAV
```

### Catch-up

Broadcastの取りこぼし、端末再起動などに備え、低頻度のcatch-up処理を許容する。

ユーザーがSumire Syncを強制停止した場合は、Broadcastで自動復帰することを前提としない。ユーザーがSumire Syncを再度起動した時点でcatch-upを実行する。

catch-up時も通常と同じ `lastId` 増分取得を利用する。

候補:

- Sumire Sync起動時
- 強制停止後にユーザーがSumire Syncを再起動した時
- 端末再起動後
- ユーザー操作による手動同期
- 必要に応じて数時間単位の低頻度バックグラウンド同期

通常のリアルタイム同期はBroadcastを主経路とし、catch-upは保険として扱う。

## 月次SQLite DB

Sumire Sync側のみで同期用SQLite DBを管理する。

例:

```text
clipboard-2026-09.sqlite
clipboard-2026-10.sqlite
```

基本的には現在月のDBのみ更新される。

ただし、同期が停止していた期間を後から取得した場合は、`createdAt` に応じて過去月DBへ追加する。

例:

```text
10月2日に同期再開

9月30日のClipboard
→ clipboard-2026-09.sqlite

10月1日のClipboard
→ clipboard-2026-10.sqlite
```

Nextcloudへアップロードする際は、書き込み中のSQLiteファイルをそのまま送らず、整合性の取れたスナップショットを生成してアップロードする。

テキスト本文は固定サイズのbyte配列へ一括展開せず、一定サイズのchunkに分けて同じtransaction内でSQLiteへ保存する。これにより、長文がプロセスのメモリ量や任意のサイズ上限によって後続同期を停止させないようにする。

月次DBでは、Clipboardメタデータを `clipboard_items`、chunk化した本文を `clipboard_content_chunks` に保存する。本文は以下のキーと `chunk_index` の昇順で復元できるようにする。

```text
(databaseInstanceId, clipboardGeneration, sourceId, chunkIndex)
```

---

## upstream追従

可能であれば、追加コードはfork専用のpackage/source setへ分離する。

例:

```text
syncbridge/
└── ClipboardSyncProvider.kt
```

既存の以下のコードは極力変更しない。

- `IMEService.kt`
- `ClipboardHistoryRepository.kt`
- Clipboard HistoryのEntity
- Room schema
- 既存DAO
- 既存UI

増分取得と `currentSequence` 取得は、fork専用の `ClipboardSyncProvider` から `AppDatabase.openHelper.readableDatabase` を直接read-only参照して実装する。

`clipboardGeneration` は通常のコードでは変更せず、`clipboard_history` のID系列を再生成するmigration等が追加された場合のみ、固定version値を明示的に更新する。

目標は、upstream更新時のコンフリクトを最小限にすること。

---

## 初期実装範囲

最初は **新規テキストClipboardの片方向同期のみ** を対象とする。

対象:

- 新規テキスト

オプション:

- コピー時点のforegroundアプリ推定
  - Sumire Sync側のみで実装
  - `UsageStatsManager.queryEvents()` を使用
  - 使用状況アクセスが許可された場合のみ
  - 推定不能時はnull
  - AccessibilityServiceは初期実装に含めない

後回し:

- 新規画像の同期
- Pin状態の同期
- 削除同期
- PC → Androidの双方向同期
- 履歴編集同期

---

## 最終構成

```text
Sumire Keyboard
  ├─ Room DB
  ├─ ClipboardFileStore
  ├─ Clipboard保存完了時にExplicit Broadcast
  │
  └─ ReadOnly ClipboardSyncProvider
       ├─ readableDatabaseからquery(afterId, limit)
       ├─ apiVersion取得
       ├─ databaseInstanceId取得
       ├─ clipboardGeneration取得
       ├─ currentSequence取得
       └─ テキスト本文のcontent stream
              ↓
        signature IPC / Broadcast

Sumire Sync
  ├─ SUMIRE_PACKAGE_ID
  ├─ BroadcastReceiver
  ├─ apiVersion
  ├─ databaseInstanceId
  ├─ clipboardGeneration
  ├─ lastId
  ├─ Broadcast受信時にcatch-up要求を永続化・集約
  ├─ CatchUpJobで小さいbatchを順次取得
  ├─ 画像itemは未対応として記録・skip
  ├─ foregroundアプリ推定（任意）
  ├─ 月次SQLite DB
  ├─ SQLiteスナップショット
  └─ Nextcloud API / WebDAV
              ↓
          Nextcloud
              ↓
              PC
```

Sumire本体は、クリップボードの保存、安全な読み出し口の提供、および保存完了時の軽量な更新通知だけを担当する。

同期状態、月次DB、Nextcloud通信、PC側処理は **Sumire Sync** に完全に分離する。
