# Sumire Sync

Sumireのクリップボード履歴を、月次SQLiteアーカイブとしてWebDAV/Nextcloudへ片方向同期するAndroidアプリです。Sumire本体とはsignature permissionで保護されたContentProviderと明示的Broadcastだけで連携します。

対応OSは **Android 11（API 30）以降** です。

## 依存関係の自動更新・公開

Dependabot alertsとsecurity updatesをGitHub側で有効にしています。
main更新時にGradleの依存一覧をGitHubへ送信し、間接依存も監視します。
通常pushと自動リリース後の送信は共通のsnapshot識別子を使い、解析したcheckoutのSHAを記録します。旧workflow別のsnapshotは、新しい依存一覧の送信成功後に空のsnapshotで置き換え、更新前の依存が監視対象に残らないようにします。
push・手動実行・自動リリース後の依存送信は、共通のconcurrency groupでcheckoutから直列化します。送信直前にmainのSHAを確認し、解析中にmainが進んだ場合は古いsnapshotと旧snapshotの置き換えを送信しません。
`.github/dependabot.yml`でOkHttp・JUnit・Robolectric・Bouncy Castleの更新を平日に毎日確認し、メジャー更新も含めてPRを自動作成します。
Dependabotが作成したPRのうち、`app/build.gradle.kts`の`implementation`・`testImplementation`の安定版パッチ番号だけを上げる変更を自動処理します。
加えて、OkHttpおよびテスト依存のJUnit・Robolectric・Bouncy Castleは、安定版のマイナー・メジャー更新も自動処理します。特定のメジャー番号には限定せず、テスト・ビルド成功を条件に自動取り込みします。Bouncy Castleの`1.86 → 1.87`やRobolectricの`4.14.1 → 4.17`のように、パッチ番号を省略したバージョンにも対応します。
それ以外の依存のマイナー・メジャー更新、プレリリース、Gradleプラグイン・GitHub Actionsの変更、手動コミットを含むPRは手動確認が必要です。SDKやビルド設定の変更が必要でチェックに失敗する更新は、自動取り込みされません。

- `main`の必須チェック`Build APK`に成功し、最新のmainに対してマージ可能なPRを自動マージします。
- `versionCode`を1、`versionName`のパッチ番号を1増やしたコミットを準備し、そのコミットで単体テストと署名付きAPKのビルド・署名検証を行います。
- 成功したコミットのチェック結果を記録してmainを更新し、`vX.Y.Z`タグとAPK付きGitHub Releaseを公開します。
- ビルド中にmainが進んだ場合は公開を止め、次回に新しいmainで再検証します。途中で公開が失敗した場合も、公開待ちのバージョンを再利用します。

処理はAndroid buildの成功後に起動し、毎時17分・47分（UTC）にも再確認します。
再試行はActionsの`Dependabot dependency release` → `Run workflow`から実行できます。
署名には既存の`ANDROID_KEYSTORE_*`・`ANDROID_KEY_*`リポジトリSecretsを使います。
単体テストとdebugビルドには署名Secretsを渡しません。タグのリリースビルドと、自動リリースのテスト成功後の署名ビルドにだけ渡し、展開したkeystoreは処理終了時に削除します。
タグpushによる通常の手動リリースも引き続き利用できます。

## 実装範囲

- `apiVersion`、`databaseInstanceId`、`clipboardGeneration`、`currentSequence`の検証
- `id > lastId`、昇順、最大500件の増分取得
- テキストのContentProvider stream取得（画像itemは未対応としてskip）
- `createdAt`のUTC年月を基準にしたSQLite保存と、10 MBを目安にしたファイル分割
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

Sumire Sync側でも、Providerの所有パッケージと署名の一致を問い合わせ前に検証します。候補の名前を名乗る別署名アプリは接続先に採用しません。

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

`createdAt`はUnix epoch millisecondsで、1970年から9999年までのUTC日時を受け付けます。範囲外は保存予約を作る前にエラーとし、元の項目を修正・削除した後は再試行できます。旧処理が残した不正なファイル名の予約は、未作成かつ送信履歴がない場合に限って管理情報を取り除きます。保存済みファイルには触れません。itemsは`id ASC`、`limit`は最大500です。Providerの破壊的な契約変更時は`apiVersion`を更新してください。

Broadcast actionは`${packageId}.action.CLIPBOARD_CHANGED`、permissionは`${packageId}.permission.CLIPBOARD_SYNC`です。3 variant分を個別に宣言しています。BroadcastにClipboard本文を含めてはいけません。

## WebDAV設定

アプリ画面で、NextcloudサーバーのHTTPS URL（`https://url.com:port` まで）、ユーザー名、アプリパスワードを入力します。WebDAVのパス `/remote.php/dav/files/{ユーザー名}/Sumire/{インストールUUID}/` はアプリが自動的に追加します。パスワードはAndroid KeystoreのAES-GCM鍵で暗号化して保存します。書き込み中のDB本体ではなく、一時スナップショットだけをアップロードします。

`Sumire`とUUIDのフォルダーがない場合は、親から順に作成します。UUIDは初回に生成してバックアップ対象外の領域へ保存し、アプリ更新では維持します。別端末・再インストール・アプリデータ消去では別UUIDとなり、以前のアーカイブを上書きしません。サーバーURLまたはユーザー名を変更すると、保存済みの全月次アーカイブを新しい接続先へ再送します。変更前の接続先への送信完了は、新しい接続先の送信済み状態には反映しません。通信エラーの記録・消去も接続先の世代を確認し、変更前の接続先から遅れて返ったエラーを現在の状態へ反映しません。URLを空にしてアップロードを無効化した場合は、古いアップロードエラーだけを消去し、ローカル同期のエラーや未送信データは保持します。

設定保存が失敗した場合は、メモリ上の接続先・認証情報・世代も変更前へ戻します。保存結果が確定するまでジョブからの接続設定の読み取りと同じ設定ファイルへの別の書き込みを待たせ、未保存の世代が同期管理DBへ記録されることを防ぎます。

内部DB **version 8** は、保存先をインストールUUIDの専用フォルダーへ切り替える方針に合わせ、旧版との後方互換性を持たせません（アプリのバージョン番号やProviderの`apiVersion`とは別です）。端末内も `noBackupFilesDir/sync-v8/` に分離し、内部DB・月次アーカイブを新規作成します。旧DBの移行、旧アーカイブの索引取り込み、旧データの新フォルダーへの再送は行いません。同期進捗は0から開始し、Sumire側に残っている履歴だけを再取得します。旧版にしか残っていない履歴は新保存先へ引き継ぎません。旧ローカルファイルと従来の `Sumire/clipboard-YYYY-MM.sqlite` は自動削除・変更しません。WebDAV設定は維持します。読み取り側は `Sumire/{インストールUUID}/clipboard-*.sqlite` を対象に、連番付きの分割ファイルも読み込んでください。

アップロードにはジョブごとに独立したスナップショットを使用し、停止時にはjobIdに対応する実行の通信を取り消します。停止済み実行は送信済み状態を更新せず、同じjobIdの後続実行とも区別します。既存ファイルはHEADで取得した強いETagを条件にPUTし、新規ファイルは未作成であることを条件にPUTします。競合時は未送信のまま再試行します。HEADの失敗や強いETagの欠落時は既存ファイルを上書きしません。条件付きPUTを正しく処理するサーバーが必要です。UUIDフォルダーを複数端末で共有する運用や、外部からのアーカイブ編集・統合は対象外です。

月次DBの保存前に復旧用の記録を永続化するため、保存途中に終了して元の履歴が削除されても、保存済みアーカイブを送信待ちへ回収できます。
新規DBのschemaは一時ファイルで初期化・永続化してから正式名へ原子的に変更します。初期化中に終了して残った一時ファイルは送信対象にせず、次回の保存で作り直します。既存の正式ファイルが破損している場合は保持し、自動再作成しません。

月次DBの破損を検出した場合は、ファイルを保持してエラーを表示し、そのアーカイブの保存・送信を停止します。索引登録済みの確定したファイルは、新規項目の重複確認で開き直さないため、古い分割DBの破損は正常な書き込み先や翌月への保存を妨げません。既存ファイルを空DBとして自動再作成せず、送信前にもSQLiteの整合性を確認します。破損DBの自動復元は行いません。
アップロードでは、破損・欠損やファイル単位のHTTP競合・拒否を検出しても、ほかの正常なファイルの送信を続けます。今回のジョブで失敗したファイル数と直近1件のファイル名・理由だけを、合計最大2,000文字（超過分は省略）で保存・表示します。ファイルごとのエラー全文は蓄積しません。失敗したファイルは未送信のまま次のジョブで再試行し、そのジョブの結果で表示を更新します。全件成功時にアップロードエラーを消去します。既存の巨大なエラー値も状態取得のSELECT内でSQLにより切り詰め、全文を画面用に読み出しません。状態表示のためにDBを書き換えません。認証エラー、通信断、送信先フォルダーの作成失敗、サーバー障害などではジョブ全体を終了して再試行します。失敗ファイルが残る間は同期完了を記録しません。

内部の同期管理DB `sync-state.sqlite` の破損を検出した場合も、ファイルを保持し、自動削除・再作成しません。保存先索引や未送信情報を空にして続行せず、ローカル保存・アップロードを停止します。画面にはDBを読み取れないことを表示し、保存日時・完了日時は「読み取り不可」とします。内部DBの自動復元は行いません。
v8保存領域にアーカイブが残っている場合は、管理DBが消失・0バイトになっていても新規初期化せずに停止します。0バイト・消失の確認はDBを開く前に行い、既存の空ファイルも保持します。アーカイブのない初回作成は許可し、旧保存領域のファイルはこの判定に含めません。

ローカル同期の停止時はProviderへの問い合わせと本文取得をキャンセルし、保存途中の項目をロールバックします。次回は未完了のIDから再取得します。欠損ログと、その範囲までの進捗は同じトランザクションで保存し、中断後に欠損範囲が広がった場合も記録を更新します。欠損ログはその取得処理でProviderから取得できなかったIDや未対応の項目を記録するもので、アーカイブの未保存一覧ではありません。保存後の進捗更新に失敗し、再試行前に元の履歴が削除された場合は、保存済みIDも含まれます。

WebDAV未設定でもSumireから月次SQLiteへのローカル保存は継続します。設定後、未アップロードのrevisionが順次送信されます。

画面の「最終ローカル保存」と「最終同期完了」は別々に記録します。「最終同期完了」は、WebDAV設定済みでローカル同期と全アーカイブの送信が完了し、エラーがない場合に更新します。新しいデータがない同期も対象です。未完了・失敗時は前回の完了日時を保持し、初回の完了前（アプリ更新後を含む）は「—」を表示します。WebDAV未設定の場合はローカル処理が終わっても「Nextcloud未設定」と表示し、初回の完了記録を待つ間は「同期完了を確認中…」と表示します。状態取得と画面からの同期予約はバックグラウンドで実行します。予約失敗時に成功通知は出さず、永続化済みの要求は次回の再試行に残します。

## 月次SQLite schema

同じ月の最初のファイルは`clipboard-YYYY-MM.sqlite`、続きは`clipboard-YYYY-MM-001.sqlite`、`-002.sqlite`のように保存します。1項目の保存完了後にDBが **10 MB（10,000,000バイト）以上** になったら、次の新規項目から次のファイルを使います。1項目の本文は分割ファイルをまたがないため、巨大な項目では10 MBを超えることがあります。確定したファイルへ新規項目は追加せず、項目の識別キーと保存先を内部DBへ先に記録して、保存途中の終了や再試行でも同じファイルを使います。使用済みのファイル名も永続管理し、ローカルファイルが欠損しても名前を再利用しません。新規項目には未使用の連番を割り当て、既存のサーバーバックアップを保持します。欠損したファイルに属する項目の再試行では、自動再作成せずエラーにします。圧縮は行わず、送信ファイルは通常のSQLiteです。PUTの120秒制限は維持するため、低速回線や巨大な単一項目・既存DBではタイムアウトする場合があります。

保存月はUTCで判定するため、端末のタイムゾーン変更で保存先は変わりません。v8で記録した項目の識別キーと保存先を使って再試行し、旧保存領域の探索や一括索引作成は行いません。

主要テーブルは`clipboard_items`です。元データの識別情報、type、作成時刻、preview、本文の格納方式・サイズ、任意のforeground package/app labelに加え、フラグがONの間に同期した項目を示す`is_flagged`を保持します。フラグのトグルは設定時間後に自動でOFFになりますが、保存済みの`is_flagged`は変更しません。テキスト本文は、streamが少量ずつ返す場合も64 KiBまでまとめ、末尾の端数だけ短いチャンクとして`clipboard_content_chunks`へ保存され、同じ元データキーの`chunk_index`昇順で復元できます。月次DBのschema versionは3を使用し、旧schemaからの移行は行いません。本文は`content_storage = CHUNKS`で保存し、`clipboard_items.content`はNULLです。

同期制御情報と欠損ログは別の内部DB `sync-state.sqlite` に保存され、アップロード対象には含まれません。`IMAGE`は未対応として欠損ログへ記録し、本文を開かずに後続IDへ進みます。未知の`itemType`はデータ損失を避けるため同期を停止します。
本文streamが`null`の場合はProviderの一時障害として再試行し、その項目の`lastId`は進めません。
