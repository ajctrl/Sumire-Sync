# Sumire Sync

Sumire のクリップボード履歴を、月次 SQLite アーカイブとして WebDAV / Nextcloud へ片方向同期する Android アプリです。

```text
Sumire → Sumire Sync → 月次 SQLite → WebDAV / Nextcloud
```

## 主な機能

- テキスト履歴を増分取得し、重複を防いで保存
- 作成日時の UTC 年月ごとにアーカイブし、10 MB を目安にファイルを分割
- 履歴更新の通知をまとめて処理し、起動時・端末再起動時・6 時間ごとにも未取得分を確認
- WebDAV 未設定でも端末内への保存を継続し、設定後に未送信分をアップロード
- 任意でコピー元候補のアプリを推定（Usage Access の許可が必要、初期状態は OFF）

画像は未対応としてスキップします。削除同期、Pin 変更同期、PC から Android への逆同期、AccessibilityService は対象外です。

## 利用条件

- **Android 11（API 30）以降**
- 連携用 ContentProvider / Broadcast を実装した Sumire
- **Sumire と Sumire Sync を同じ署名証明書で署名すること**
- クラウドへ送信する場合は、HTTPS と条件付き PUT に対応した WebDAV / Nextcloud サーバー

1 つの Sumire Sync APK で、次の 3 つの package ID に対応します。variant ごとの再ビルドは不要です。

```text
com.example.sumire
com.example.sumire.lite
com.example.sumire.lite.fdroid
```

Sumire 本体とは `signature` permission で保護された ContentProvider と明示的 Broadcast で連携します。署名証明書が異なる場合は接続できません。Provider の所有パッケージと署名も問い合わせ前に検証します。

## はじめに

1. 同じ署名証明書で署名した Sumire と Sumire Sync を端末へインストールします。
2. Sumire Sync を開き、Nextcloud サーバーの HTTPS URL、ユーザー名、アプリパスワードを入力して設定を保存します。URL は `https://cloud.example.com:443` のようにサーバー部分までを指定します。
3. 画面の「最終ローカル保存」と「最終同期完了」で保存・送信の状態を確認します。

WebDAV の保存先はアプリが自動的に追加するため、URL に入力する必要はありません。

```text
/remote.php/dav/files/{ユーザー名}/Sumire/{インストールUUID}/
```

フォルダーがなければ自動作成します。アプリパスワードは Android Keystore の AES-GCM 鍵で暗号化して保存します。

WebDAV を設定しない場合もローカル保存は続き、画面には「Nextcloud未設定」と表示されます。「最終同期完了」は、ローカル同期と全アーカイブの送信がエラーなく完了した場合に更新されます。

## アーカイブと運用上の注意

ファイルは通常の SQLite 形式で、圧縮しません。同じ月のデータが 10 MB（10,000,000 バイト）以上になると、次の新規項目から連番付きファイルへ保存します。1 項目の本文は分割しないため、このサイズを超える場合があります。

```text
Sumire/{インストールUUID}/
├── clipboard-2026-10.sqlite
├── clipboard-2026-10-001.sqlite
└── clipboard-2026-11.sqlite
```

- 保存月は UTC で判定します。読み取り側は連番付きの分割ファイルも対象にしてください。
- インストール UUID はアプリ更新で維持されます。別端末・再インストール・アプリデータ消去では別 UUID になります。
- サーバー URL またはユーザー名を変更すると、保存済みの全月次アーカイブを新しい接続先へ再送します。
- DB の破損を検出した場合はファイルを保持し、自動削除・再作成しません。同期管理 DB が破損した場合はローカル保存・アップロードを停止します。
- UUID フォルダーの複数端末での共有や、外部からのアーカイブ編集・統合は対象外です。

## 開発・詳細ドキュメント

| ドキュメント | 内容 |
| --- | --- |
| [同期・保存の詳細仕様](docs/sync-behavior.md) | Provider API、SQLite schema、再試行・競合・破損時の動作、状態表示 |
| [開発・リリース手順](docs/development.md) | ビルド環境、テスト、署名、Dependabot による自動更新・公開 |
| [Sumire-Sync連携仕様書](docs/Sumire-Sync連携仕様書.md) | Sumire 本体側の連携設計・API 契約 |

## ライセンス

[MIT License](LICENSE)
