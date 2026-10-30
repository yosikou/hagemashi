# ChatGPT接続の技術調査

確認日: 2026-10-30（UTC）。公式資料の現行ページを確認した結果。

後続更新: ユーザーの指示で[手動接続の検証版](manual-chatgpt-test.md)を実装した。以下の「未実装・保留」は初回調査時点の記録。Androidの正式適用と実機動作は引き続き未確認。

更新: ユーザーはMIT採用を決定した。以下のLICENSE未設定・ライセンス未決定の記述は初回調査時点の記録であり、現在の採用方針ではない。[MIT採用後の適用条件](siwc-applicability-after-mit.md)を最新の判断とする。MIT採用だけでSIWCの利用条件を満たしたとは判断していない。

## 結論

ChatGPTアカウントの利用枠で推論する正式な候補として、Sign in with ChatGPT（SIWC）の **ChatGPT plan usage / open-source client flow** が存在する。一般のOpenAI APIキーを要求する方式には変更しない。

ただし、今回の調査だけではhagemashiのAndroid配布と無操作の毎日生成について適用条件を確定できなかった。**実現不能とは判定していないが、実装開始の条件「正式方式の適用に問題なし」は未成立として、今回は調査資料のみを提出する。** 認証・生成・バックグラウンド操作は未実装。接続検証用APKはない。CIが出力するAPKは既存の「Hello Android」のみである。

保留の理由は次の3点。このうち、LICENSEに関する扱いは本調査の判断であり、OpenAIが特定のライセンスを指定しているという意味ではない。

1. 公式OSSフローはオープンソース／ローカルホストアプリ向け。現リポジトリはpublicだがLICENSEがなく、公開可視性だけでOSS適格性を満たすとは判断しない。採用ライセンスや将来の有料化・ホスティング方針は未決定。
2. 指定資料にAndroidでの動作保証・Android用コールバック手順は見つからなかった。指定されるHTTP loopback listenerを、外部ブラウザへ切り替えたAndroidアプリで安全に維持できるかは未検証。カスタムURIやApp Linksに置き換えてよいとは記載されていない。
3. `offline_access`と更新仕様はあるが、Androidからユーザー操作なしで毎日実行する用途の条件・制限を確定できる記述は確認できなかった。禁止とも許可済みとも断定しない。APIの`background`未対応は、Androidでの予約実行そのものが禁止という意味ではない。

## リポジトリと作業基点

- main: `7c34a2ce1652cdab6be96a2746a9948ed1344bd2`。READMEのみ。
- [PR #1](https://github.com/yosikou/hagemashi/pull/1): open、未マージ。headは `feat/minimal-compose-app` / `f1b47712629d2c41778dc404c9c9162e5ffbe2fc`。
- mainとPR #1のREADME、PR #1のルートAGENTS.md、全ファイル一覧と既存CIを確認。mainにAGENTS.mdはなく、PR #1に子ディレクトリのAGENTS.mdはない。LICENSE / COPYINGもない。
- 既存チェックアウトがなかったためcloneし、変更のない状態を確認してPR #1の先端から `feat/chatgpt-connection-spike` を作成。PRのbaseは `feat/minimal-compose-app` とする。PR #1とmainは変更・マージしない。PR #1が先にマージされたら差分を再確認してbaseをmainへ変更する。

## 1. 正式な方式と公開条件

[Quickstart](https://developers.openai.com/siwc/quickstart)は、本人確認だけの連携とChatGPTプラン利用を区別している。今回必要なのは後者。identityのscopeだけでは推論権限がない。

[OSS Overview](https://developers.openai.com/siwc/token-sharing-open-source)では、OSS／ローカルホストアプリ向けに動的OAuth登録を説明する。有料またはリモートホストアプリは掲載されたinterest formの対象。[Request a client ID](https://developers.openai.com/siwc/request-client-id)の商用向け提供は選定パートナーへの限定提供であり、publicリポジトリにしただけで商用公開承認を得たことにはならない。

今回の候補は端末から正式エンドポイントへ直接接続するpublic client。client secretやpartner API keyをAPKへ埋め込む設計は採用しない。非公開`backend-api`、Cookieの流用、ChatGPT画面の自動操作は使用しない。Google Play配布の適否・必要な審査・商用化の承認は別途確認が必要。

## 2. 対象プランと利用枠

Quickstartが明記する対象は **eligibleなChatGPT Plus／Proユーザー**。Free、Go、Business、Enterprise、Eduなどの対応は今回の資料から確認しておらず、利用可能と表示しない。プラン名だけで権限を保証せず、返却scope、アカウント／workspace policy、実際の応答を確認する。

[Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)によると、Plusの5時間枠は他の対象アプリと共有される。Proにはこの5時間枠は適用されないが、無制限と解釈しない。アプリ別制限もあり、上限エラーだけでリセット時刻を推測しない。利用枠とクレジットの設定はChatGPT SettingsのUsageでユーザーが管理する。hagemashiにAPI課金への自動切り替えを設けない。

## 3. 認証・Androidコールバック・保存の設計候補（未実装）

[Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)に沿う候補:

1. ボタン名は `Continue with ChatGPT`。パスワードは収集せず外部のシステムブラウザで認証する。
2. 端末インストール単位で不透明な `ext_agent_host_id` を先に生成・保存し、再起動や再認証でも維持する。初回のみ `client_id=dynamic_agent_client` と実アプリ名の `agent_name_hint` を指定する。
3. 認証試行ごとにstate、nonce、PKCE S256を生成する。`https://auth.openai.com/api/accounts/authorize`へ遷移する前に、`127.0.0.1`にのみbindしたlistenerを開始する。
4. callback例は `http://127.0.0.1:<port>/auth/callback`。後から変えられるのはportだけ。認可要求と交換時に完全に同じURIを使う。`localhost`や独自schemeへ変更しない。
5. scopeは `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`、resourceは `https://api.openai.com/v1`。stateとOAuth errorを検査し、初回callbackの発行済みclient IDでコードを交換する。`dynamic_agent_client`を交換や永続登録IDに使わない。
6. `https://auth.openai.com/api/accounts/oauth/token`へformでコード・verifier等を送る。ID tokenの署名を公式JWKSで検証し、issuer、発行済みclient IDに対するaudience、有効期限、nonceを確認。検証済みsubで本人を識別する。メールだけで登録を統合せず、登録ごとのclient IDと本人情報を対応付ける。
7. 接続状態と、検証済みtokenで確認できた名前・メール等のみ表示する。プラン利用scopeがなければ「サインイン済み／生成権限なし」を区別する。別登録・workspaceへの切替時に資格情報を混ぜない。

Android固有の検証事項: ブラウザ滞在中のプロセス終了、画面回転、キャンセル、port競合、callback重複・不正state、listenerのtimeout／終了処理。プロセス終了後は安全に新しい認証からやり直す設計が必要。これらの挙動やブラウザからloopbackへの到達性は公式フローを読んだだけでは確認済みにできない。

保存候補は [Android Keystore](https://developer.android.com/privacy-and-security/keystore) の非エクスポート鍵で資格情報をAES-GCM暗号化し、アプリ専用の `noBackupFilesDir` に原子的に保存する方式。Keystoreにトークン文字列を直接格納するという意味ではない。鍵・暗号文が失われたら再認証とし、平文へfallbackしない。

access／refresh／保持するID token、発行済みclient ID、検証済みidentity、scope、期限は一組として管理。state・nonce・verifierは短命な認証試行に限定する。ログ、解析、例外本文、APK固定値、Git、WorkManagerのData、通知、スクリーンショットに資格情報やcallback全体を出さない。`id_token_hint`を含む認可URLも機密として扱う。

[Auto Backup](https://developer.android.com/identity/data/autobackup)は`noBackupFilesDir`を除外する。既存の`allowBackup=false`も維持し、Android 12以降の端末間転送を含めた除外規則と復元試験が必要。無操作実行用の鍵に毎回の生体認証を必須にすると予約生成と両立しないため、端末ロック時の利用方針は実装前に設計する。

## 4. 更新・失効・解除

[Token reference](https://developers.openai.com/siwc/token-sharing-open-source/token-reference)の現行値はaccess token 1時間、refresh token 30日。成功した更新で置き換えrefresh tokenと新しい30日間が返る。実装では固定値だけに依存せず返却期限を保存する。

Accounts and sessionsに従い、発行済みclient ID・refresh token・resourceを使ってtoken endpointへ更新する。同一sessionの更新は直列化し、期限・scope・回転refresh tokenをまとめて置換する。

解除時は予約処理と通信を止め、`https://auth.openai.com/.well-known/openid-configuration`の`revocation_endpoint`へrefresh token、token type hint、client IDをform POSTする。空の200が成功。ネットワーク失敗時の再試行には上限を設け、ローカル削除だけで終了したら「サーバー側失効未確認」と表示してChatGPT Settingsでの解除を案内する。client登録の削除とsession失効は別で、再認証用の対応表とhost IDは保持する設計が公式例に沿う。

[Errors and recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)では、設定画面からの解除通知は現在ない。確定した失効やterminalなrefresh errorで停止・再認証へ進む。通信障害だけで資格情報を消さない。401、403、429、503を同一扱いにせず、上限時はUsageへ誘導し、不適格時にOAuthや生成を繰り返さない。

## 5. モデル取得とテキスト生成

[Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)の方式はOAuth bearerで `GET https://api.openai.com/v1/models`。このフローの応答は`models`配列で、`visibility=list`の`display_name`を表示し、`slug`を生成時のmodelに使う。アカウント切替時は再取得し、固定モデルを利用可能と仮定しない。

生成は `POST https://api.openai.com/v1/responses`。`store=false`、`stream=true`、配列の`input`、`instructions`またはdeveloperメッセージを使用。SSEを最後まで読み、`response.completed`を確認して初めて成功とする。部分出力、切断、失敗イベントを成功にしない。personaと測定日時つきダミーデータだけで試し、過去の励ましは送らない。

[Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)によると、`background`、`max_output_tokens`、`temperature`、HTTPの`previous_response_id`などは利用できない。文字数はプロンプトで指定し、実際の長さを検査する必要がある。既存のAPIキー用クライアントにtokenだけ差し替えて任意のAPI機能が使えるわけではない。

## 6. Android予約実行とAPI backgroundの違い

[API Background mode](https://developers.openai.com/api/docs/guides/background)は、サーバーで非同期生成して状態をpollする機能。今回のSIWCルートでは上記preview制約により使用しない。

Android側の予約は、OSに後の実行を依頼して、起動したworkerが通常のSSE生成を実行する別の仕組み。APIの`background=true`は不要。ただし、この構成で無操作の反復利用が正式に認められるかは未確認で、`offline_access`だけをその根拠にしない。

適用確認後の最小検証候補は、ユーザーが明示的に予約する「数分後に一度だけ」のWorkManager処理。毎日の処理は今回作らない。OSの遅延を認め、指定分数どおりの実行を保証しない。[通知権限](https://developer.android.com/develop/ui/views/notifications/notification-permission)はAndroid 13以降で別途許可が必要。通知拒否と生成失敗を区別する。強制停止・電池制限・オフライン・ロック中・再起動も別条件として検証する。

## Android端末のみの確認手順

**現時点では接続機能がないため、以下の接続試験を現在のAPKで実施することはできない。** Actions成果物で確認できるのは既存のHello Androidの起動だけ。接続機能を追加した後に、この手順を実際の画面名と対応付けて更新する。

1. AndroidブラウザでPRのActions成功実行を開き、ログインしてArtifactsの`hagemashi-debug-apk`を取得。ZIPを展開してAPKをインストールする。署名違いによる再インストールはローカルデータを削除するので注意する。
2. 接続検証版ではContinue with ChatGPTを押し、外部ブラウザで本人の認証と同意を行う。アプリへ戻って接続状態・確認可能な本人情報・利用枠の説明を確認する。トークンやcallback URLを撮影しない。
3. アカウントのモデル一覧から選択して固定ダミーデータの生成を実行。全文、成功状態、モデルを記録する。切断・拒否・上限は成功と混ぜない。
4. アプリ終了・再起動後に接続が保持され、再生成できるか確認。端末の再起動も別に試す。単に画面に「接続済み」が残るだけでは合格にしない。
5. 通知を許可し、一度だけの予約を操作してホームへ戻り画面を閉じる。予約時刻・実行時刻・到着時刻と、通知を開いた結果を記録する。強制停止は通常の画面終了と区別する。予約操作自体がない現APKでは未実行。
6. 解除後に生成・保留中の予約が止まること、ローカル資格情報が消えることを確認。ChatGPT側の解除後の挙動も別に試す。サーバー失効確認の成否を記録する。
7. 期限をまたぐ更新、refresh失効・回転競合、利用上限、通知拒否、通信失敗、端末ロック中の鍵利用、端末バックアップ／転送は、それぞれ試験した条件だけを報告する。

## 検証結果

| 区分 | 結果 |
| --- | --- |
| 成功 | main／PR #1／README／AGENTS／既存CIと公式資料を確認。既存ユーザー変更なしを確認して作業ブランチを作成 |
| 成功 | `git diff --check`（文書のみの差分。PR提出前に実行） |
| 失敗 | ローカルの必須Gradleコマンド。既定のGradle homeは書込権限エラー、書込可能な`GRADLE_USER_HOME`で再試行すると配布ZIP取得が`Connection refused`。ローカルはJDK 21のみでJDK 17／Android SDK／接続デバイスも未準備 |
| 別途PRに記録 | GitHub ActionsによるJDK 17／API 35／Build Tools 35.0.0での `:app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` の結果と成果物リンク |
| 未実行 | `connectedDebugAndroidTest`。接続Android端末なし。既存UIとMainActivityTestは変更なし |
| 未実装・未実行 | サインイン、アカウント表示、モデル取得、生成、再起動後の接続維持、予約生成・通知、解除 |
| 未検証 | Androidへの正式適用、無操作反復生成の条件、更新・失効、上限、地域／workspace制限、公開審査、Keystore／バックアップの実装と実機動作 |

CIでテストAPKをビルドできても、UIテストを実行したことにはならない。Android実機での動作確認済みとは報告しない。

## MVPへ進む判断と次の作業

現時点は **接続部分を保留**。MVP全体へ着手する前に、次を解消する。

1. MIT採用はユーザーが決定済み。将来有料化やサーバーホストを選ぶ場合は公式interest formで適用を確認する。MITとSIWCの利用条件は別に判断する。
2. OpenAIの公式窓口／更新資料で、Android public clientのHTTP loopback利用と、同意後の無操作予約生成の適用範囲・制限を確認する。App Links等が必要なら正式に登録できる方式を確認する。
3. 適用が確認できたらKeystore保存・署名検証・更新・失効を含む最小接続版を実装し、CIでAPKを提出する。APIキーや非公開手段へ置き換えない。
4. ユーザーがAndroidのみで上記の実機試験を実施する。サインイン・回答・再起動保持・画面終了中の予約通知の実測結果で次段階を判断する。

健康データ実取得、目標・人格編集、履歴、毎日の通知、CSVは本調査で実装しない。
